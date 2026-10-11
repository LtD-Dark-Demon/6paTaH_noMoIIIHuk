package com.bac9j.bratan

import android.content.Context
import android.os.PowerManager
import android.util.Log
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.tensorflow.lite.DataType
import org.tensorflow.lite.Interpreter
import java.io.FileInputStream
import java.nio.MappedByteBuffer
import java.nio.channels.FileChannel
import kotlin.math.abs
import kotlin.math.exp

// ==============================================================================
// 2. СЛОЙ ДАННЫХ (РЕАЛИЗАЦИИ ИНТЕРФЕЙСОВ) - То, КАК это делается
// ==============================================================================

/**
 * Реализация локальной модели через TensorFlow Lite (Оптимизировано для G99).
 *
 * Нужны файлы в assets (см. README): mobilebert_quant.tflite, vocab.txt, intents.txt.
 * Если их нет — класс тихо отключается (analyzeCommand = null), работают правила и облако.
 */
class TFLiteModelProcessor(
    private val context: Context,
    private val modelAsset: String = "mobilebert_quant.tflite",
    private val vocabAsset: String = "vocab.txt",
    private val intentsAsset: String = "intents.txt"
) : LocalModelProvider {

    private class IntentLabel(val intent: String, val actions: List<String>)
    private enum class InputKind { IDS, MASK, SEGMENT }

    /** [OPT] Object pooling: все буферы создаются один раз вместе с интерпретатором и переиспользуются под mutex. */
    private class Session(
        val interpreter: Interpreter,
        val threads: Int,
        val kinds: List<InputKind>,
        val inputs: Array<Any>,
        val ids: IntArray,
        val mask: IntArray,
        val seg: IntArray,
        val output: Array<FloatArray>
    )

    private val mutex = Mutex() // Защита потоков (TFLite не thread-safe)
    private var session: Session? = null
    private var tokenizer: BertTokenizer? = null
    private var labels: List<IntentLabel> = emptyList()

    @Volatile private var unavailable = false
    @Volatile private var released = false
    @Volatile private var desiredThreads = DEFAULT_THREADS

    /** Вызывать под mutex. Пересоздаёт интерпретатор, если изменилось число потоков (нагрев). */
    private fun obtainSession(threads: Int): Session? {
        session?.let {
            if (it.threads == threads) return it
            it.interpreter.close()
            session = null
        }
        return try {
            if (tokenizer == null) {
                tokenizer = BertTokenizer(readAssetLines(vocabAsset))
                labels = parseLabels(readAssetLines(intentsAsset))
            }
            val options = Interpreter.Options().apply {
                setNumThreads(threads) // 2 потока оптимально для Helio G99
                setUseXNNPACK(true)    // Ускорение вычислений CPU
            }
            val interp = Interpreter(loadModelFile(modelAsset), options)

            val n = interp.inputTensorCount
            val seqLen = interp.getInputTensor(0).shape().last().let { if (it >= 2) it else 128 }
            val kinds = (0 until n).map { i ->
                val name = interp.getInputTensor(i).name().lowercase()
                when {
                    n == 1 -> InputKind.IDS
                    "mask" in name -> InputKind.MASK
                    "type" in name || "segment" in name -> InputKind.SEGMENT
                    else -> InputKind.IDS
                }
            }
            val inputs = Array<Any>(n) { i ->
                if (interp.getInputTensor(i).dataType() == DataType.FLOAT32) arrayOf(FloatArray(seqLen))
                else arrayOf(IntArray(seqLen))
            }
            val nOut = interp.getOutputTensor(0).shape().last()
            Session(interp, threads, kinds, inputs, IntArray(seqLen), IntArray(seqLen), IntArray(seqLen), arrayOf(FloatArray(nOut)))
                .also { session = it }
        } catch (e: Exception) {
            // [FIX] Нет модели/словаря в assets — отключаемся один раз, а не падаем на каждой команде.
            unavailable = true
            Log.i(TAG, "Локальная модель недоступна: ${e.message}")
            null
        }
    }

    private fun loadModelFile(filename: String): MappedByteBuffer {
        // Чтение файла через memory-mapping (не грузит всю модель в ОЗУ сразу)
        // [FIX] AssetFileDescriptor не закрывался (утечка). Нужен noCompress "tflite" в build.gradle.
        context.assets.openFd(filename).use { fd ->
            FileInputStream(fd.fileDescriptor).use { input ->
                return input.channel.map(FileChannel.MapMode.READ_ONLY, fd.startOffset, fd.declaredLength)
            }
        }
    }

    private fun readAssetLines(name: String): List<String> =
        context.assets.open(name).bufferedReader().use { it.readLines() }

    private fun parseLabels(lines: List<String>): List<IntentLabel> =
        lines.map { it.trim() }
            .filter { it.isNotEmpty() && !it.startsWith("#") }
            .map { line ->
                val parts = line.split('|', limit = 2)
                val actions = parts.getOrNull(1)?.split(',')?.map { it.trim() }?.filter { it.isNotEmpty() } ?: emptyList()
                IntentLabel(parts[0].trim(), actions)
            }

    override suspend fun analyzeCommand(text: String): CommandResult? = withContext(Dispatchers.Default) {
        if (unavailable || released) return@withContext null
        try {
            // [FIX] Получение интерпретатора и инференс — под одним mutex (раньше между ними мог вклиниться release).
            mutex.withLock {
                val s = obtainSession(desiredThreads) ?: return@withLock null
                tokenizer!!.encodeInto(text, s.ids, s.mask, s.seg)
                fill(s)
                s.interpreter.runForMultipleInputsOutputs(s.inputs, mapOf(0 to s.output)) // Выполнение нейросети
                decode(s)
            }
        } catch (e: CancellationException) {
            throw e // [FIX] Нельзя глотать отмену корутины
        } catch (e: Exception) {
            Log.e(TAG, "Ошибка инференса: ${e.message}")
            null
        }
    }

    private fun fill(s: Session) {
        for (i in s.kinds.indices) {
            val src = when (s.kinds[i]) {
                InputKind.IDS -> s.ids
                InputKind.MASK -> s.mask
                InputKind.SEGMENT -> s.seg
            }
            when (val row = (s.inputs[i] as Array<*>)[0]) {
                is IntArray -> src.copyInto(row)
                is FloatArray -> for (k in src.indices) row[k] = src[k].toFloat()
            }
        }
    }

    /** Расшифровка выхода: argmax + (softmax, если выход — логиты). Без аллокаций. */
    private fun decode(s: Session): CommandResult? {
        val out = s.output[0]
        val n = minOf(out.size, labels.size)
        if (n == 0) return null
        var best = 0
        var sum = 0f
        var inRange = true
        for (i in 0 until n) {
            val v = out[i]
            sum += v
            if (v < 0f || v > 1f) inRange = false
            if (v > out[best]) best = i
        }
        val confidence = if (inRange && abs(sum - 1f) < 0.01f) {
            out[best].toDouble()
        } else {
            var denom = 0.0
            for (i in 0 until n) denom += exp((out[i] - out[best]).toDouble())
            1.0 / denom
        }
        val label = labels[best]
        if (label.intent == "UNKNOWN") return null
        return CommandResult(label.intent, label.actions, confidence, CommandResult.Source.LOCAL)
    }

    /** [OPT] При нагреве снижаем потоки с 2 до 1 (применится на следующей команде). */
    override fun adaptToThermal(status: Int) {
        desiredThreads = if (status >= PowerManager.THERMAL_STATUS_MODERATE) 1 else DEFAULT_THREADS
    }

    override fun releaseResources() {
        released = true
        // [FIX] Закрытие под тем же mutex, чтобы не убить интерпретатор посреди инференса.
        runBlocking {
            mutex.withLock {
                session?.interpreter?.close()
                session = null
            }
        }
    }

    private companion object {
        const val TAG = "TFLite"
        const val DEFAULT_THREADS = 2
    }
}
