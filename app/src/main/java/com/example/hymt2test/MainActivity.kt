package com.example.hymt2test

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.os.StatFs
import android.view.ViewGroup
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import dev.ffmpegkit.llama.Llama
import dev.ffmpegkit.llama.LlamaConfig
import dev.ffmpegkit.llama.LlamaModel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.util.Locale
import kotlin.math.min

class MainActivity : ComponentActivity() {
    private val uiScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    private lateinit var status: TextView
    private lateinit var modelLabel: TextView
    private lateinit var input: EditText
    private lateinit var output: TextView
    private lateinit var chooseButton: Button
    private lateinit var loadButton: Button
    private lateinit var translateButton: Button

    private var modelFile: File? = null
    private var loadedModel: LlamaModel? = null
    private var busy = false

    private val openModel = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) importModel(uri)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        buildUi()
        status.text = "Native engine: ${Llama.getSystemInfo()}"
        val saved = File(getExternalFilesDir("models"), MODEL_NAME)
        if (saved.isFile && saved.length() > MIN_MODEL_BYTES) {
            modelFile = saved
            modelLabel.text = "Модель: ${saved.name} (${formatBytes(saved.length())})"
            status.append("\nНайден ранее скопированный GGUF-файл.")
        }
    }

    private fun buildUi() {
        val pad = (16 * resources.displayMetrics.density).toInt()
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pad, pad, pad, pad)
        }
        val scroll = ScrollView(this).apply { addView(root) }
        setContentView(scroll)

        root.addView(TextView(this).apply {
            text = "Hy-MT2 · проверка готовой llama-android AAR"
            textSize = 21f
        }, match())

        modelLabel = TextView(this).apply {
            text = "Модель ещё не выбрана. Выбери файл Hy-MT2-1.8B-Q4_K_M.gguf."
            textSize = 14f
            setPadding(0, pad / 2, 0, pad / 2)
        }
        root.addView(modelLabel, match())

        chooseButton = button("1. Выбрать GGUF-файл") {
            openModel.launch(arrayOf("application/octet-stream", "application/x-gguf", "*/*"))
        }
        root.addView(chooseButton, match())

        loadButton = button("2. Загрузить модель в память") { loadModel() }
        loadButton.isEnabled = false
        root.addView(loadButton, match())

        root.addView(TextView(this).apply {
            text = "Английский текст"
            textSize = 16f
            setPadding(0, pad, 0, pad / 3)
        }, match())

        input = EditText(this).apply {
            minLines = 4
            maxLines = 10
            gravity = android.view.Gravity.TOP
            setText("You're right. The kill shot probably isn't necessary. And we've a great deal to discuss, in a very short time.")
            textSize = 16f
        }
        root.addView(input, match())

        translateButton = button("3. Перевести на русский") { translate() }
        translateButton.isEnabled = false
        root.addView(translateButton, match())

        root.addView(TextView(this).apply {
            text = "Результат"
            textSize = 16f
            setPadding(0, pad, 0, pad / 3)
        }, match())
        output = TextView(this).apply {
            text = "Здесь появится перевод."
            textSize = 17f
            setTextIsSelectable(true)
            setPadding(pad / 2, pad / 2, pad / 2, pad / 2)
        }
        root.addView(output, match())

        status = TextView(this).apply {
            textSize = 12f
            setPadding(0, pad, 0, pad)
        }
        root.addView(status, match())
    }

    private fun button(label: String, action: () -> Unit): Button = Button(this).apply {
        text = label
        setOnClickListener { action() }
    }

    private fun match() = LinearLayout.LayoutParams(
        ViewGroup.LayoutParams.MATCH_PARENT,
        ViewGroup.LayoutParams.WRAP_CONTENT,
    ).apply { bottomMargin = (8 * resources.displayMetrics.density).toInt() }

    private fun importModel(uri: Uri) {
        if (busy) return
        busy = true
        chooseButton.isEnabled = false
        loadButton.isEnabled = false
        translateButton.isEnabled = false
        status.text = "Проверяю выбранный файл…"

        uiScope.launch {
            try {
                contentResolver.takePersistableUriPermission(
                    uri,
                    Intent.FLAG_GRANT_READ_URI_PERMISSION,
                )
            } catch (_: SecurityException) {
                // Some providers grant only temporary access; the immediate copy still works.
            }

            try {
                val target = withContext(Dispatchers.IO) { copyAndValidateModel(uri) }
                modelFile = target
                modelLabel.text = "Модель: ${target.name} (${formatBytes(target.length())})"
                status.text = "GGUF скопирован и проверен. Следующий шаг — загрузка в память."
                loadButton.isEnabled = true
            } catch (t: Throwable) {
                status.text = "Ошибка импорта: ${t.message ?: t.javaClass.simpleName}"
            } finally {
                busy = false
                chooseButton.isEnabled = true
            }
        }
    }

    @Throws(Exception::class)
    private fun copyAndValidateModel(uri: Uri): File {
        val folder = getExternalFilesDir("models") ?: File(filesDir, "models")
        if (!folder.exists() && !folder.mkdirs()) error("Не удалось создать папку моделей")

        val afd = contentResolver.openAssetFileDescriptor(uri, "r")
        val size = afd?.use { it.length } ?: -1L
        val stat = StatFs(folder.absolutePath)
        val free = stat.availableBytes
        if (size > 0 && free < size + 128L * 1024 * 1024) {
            error("Недостаточно свободного места. Нужно около ${formatBytes(size + 128L * 1024 * 1024)}.")
        }

        val temp = File(folder, "$MODEL_NAME.part")
        val destination = File(folder, MODEL_NAME)
        temp.delete()

        val src = contentResolver.openInputStream(uri) ?: error("Проводник не открыл файл")
        src.use { rawIn ->
            BufferedInputStream(rawIn, 1024 * 1024).use { inputStream ->
                BufferedOutputStream(FileOutputStream(temp), 1024 * 1024).use { out ->
                    val buffer = ByteArray(1024 * 1024)
                    var total = 0L
                    var lastReport = 0L
                    while (true) {
                        val n = inputStream.read(buffer)
                        if (n < 0) break
                        out.write(buffer, 0, n)
                        total += n
                        if (total - lastReport >= 64L * 1024 * 1024) {
                            lastReport = total
                            val done = total
                            runOnUiThread { status.text = "Копирование модели: ${formatBytes(done)}" }
                        }
                    }
                }
            }
        }

        if (temp.length() < MIN_MODEL_BYTES) {
            val badSize = temp.length()
            temp.delete()
            error("Файл слишком мал для Hy-MT2 GGUF (${formatBytes(badSize)})")
        }
        FileInputStream(temp).use { stream ->
            val magic = ByteArray(4)
            if (stream.read(magic) != 4 || magic[0] != 0x47.toByte() ||
                magic[1] != 0x47.toByte() || magic[2] != 0x55.toByte() || magic[3] != 0x46.toByte()
            ) {
                temp.delete()
                error("Это не GGUF-файл (нет сигнатуры GGUF)")
            }
        }
        if (destination.exists() && !destination.delete()) {
            temp.delete()
            error("Не удалось заменить предыдущую модель")
        }
        if (!temp.renameTo(destination)) {
            temp.copyTo(destination, overwrite = true)
            temp.delete()
        }
        return destination
    }

    private fun loadModel() {
        val file = modelFile ?: return
        if (busy || loadedModel != null) return
        busy = true
        chooseButton.isEnabled = false
        loadButton.isEnabled = false
        translateButton.isEnabled = false
        status.text = "Загрузка модели. На первом запуске это может занять время…"

        uiScope.launch {
            try {
                val threads = min(4, Runtime.getRuntime().availableProcessors()).coerceAtLeast(2)
                val loaded = Llama.loadModel(
                    modelPath = file.absolutePath,
                    config = LlamaConfig(
                        contextSize = 2048,
                        threads = threads,
                        gpuLayers = 0,
                        temperature = 0.7f,
                        topP = 0.6f,
                        topK = 20,
                    ),
                )
                loadedModel = loaded
                status.text = "Модель загружена. Потоков CPU: $threads. Теперь можно переводить."
                translateButton.isEnabled = true
            } catch (t: Throwable) {
                status.text = "Ошибка загрузки модели: ${t.message ?: t.javaClass.simpleName}\n${t.javaClass.name}"
                loadButton.isEnabled = true
            } finally {
                busy = false
                chooseButton.isEnabled = true
            }
        }
    }

    private fun translate() {
        val model = loadedModel ?: return
        val source = input.text.toString().trim()
        if (source.isEmpty() || busy) return

        busy = true
        chooseButton.isEnabled = false
        loadButton.isEnabled = false
        translateButton.isEnabled = false
        output.text = "Перевод выполняется на устройстве…"

        uiScope.launch {
            try {
                val prompt = """Translate the following text into Russian. Note that you should only output the translated result without any additional explanation:

$source""".trimIndent()
                val result = Llama.complete(
                    model = model,
                    prompt = prompt,
                    systemPrompt = "You are a professional English-to-Russian comic translator. Use natural idiomatic Russian. Preserve the speaker's tone, humor, sarcasm, names and meaning. Do not add explanations.",
                    maxTokens = 512,
                )
                output.text = result.text.trim()
                status.text = String.format(
                    Locale.US,
                    "Готово. %d токенов · %.2f ток/с · prompt %d мс · генерация %d мс",
                    result.tokensGenerated,
                    result.tokensPerSecond,
                    result.promptEvalTimeMs,
                    result.generateTimeMs,
                )
            } catch (t: Throwable) {
                output.text = "Ошибка перевода: ${t.message ?: t.javaClass.simpleName}"
                status.text = t.javaClass.name
            } finally {
                busy = false
                chooseButton.isEnabled = true
                loadButton.isEnabled = modelFile != null && loadedModel == null
                translateButton.isEnabled = loadedModel != null
            }
        }
    }

    override fun onDestroy() {
        loadedModel?.let { runCatching { Llama.releaseModel(it) } }
        loadedModel = null
        uiScope.cancel()
        super.onDestroy()
    }

    private fun formatBytes(n: Long): String = when {
        n >= 1024L * 1024 * 1024 -> String.format(Locale.US, "%.2f ГБ", n / (1024.0 * 1024 * 1024))
        n >= 1024L * 1024 -> String.format(Locale.US, "%.0f МБ", n / (1024.0 * 1024))
        else -> "$n Б"
    }

    companion object {
        private const val MODEL_NAME = "Hy-MT2-1.8B-Q4_K_M.gguf"
        private const val MIN_MODEL_BYTES = 100L * 1024 * 1024
    }
}
