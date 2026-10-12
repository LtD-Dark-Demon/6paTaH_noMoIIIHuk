package com.bratan.assistant

import android.app.ActivityManager
import android.content.Context
import android.content.res.AssetFileDescriptor
import android.os.PowerManager
import android.os.Process
import android.os.SystemClock
import android.util.Log
import androidx.core.content.ContextCompat
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.tensorflow.lite.DataType
import org.tensorflow.lite.Interpreter
import java.io.FileInputStream
import java.nio.MappedByteBuffer
import java.nio.channels.FileChannel
import java.util.concurrent.Executors
import kotlin.math.exp

/**
 * Ленивая загрузка, выгрузка при нехватке памяти и простое, инференс в приоритетном потоке.
 * Только XNNPACK (CPU): NNAPI на MediaTek среднего сегмента нестабилен.
 */
class TFLiteModelProcessor(private val context: Context) : IntentProvider {

    private val mutex = Mutex()
    private var interpreter: Interpreter? = null
    private var afd: AssetFileDescriptor? = null
    private var buffer: MappedByteBuffer? = null
    private var tokenizer: BertTokenizer? = null
    private var intents: List<Pair<String, List<String>>> = emptyList()
    private val maxLen = 64

    @Volatile private var configured = false
    @Volatile private var desiredThreads = 2
    private var currentThreads = 0
    @Volatile private var lastUsedAt = 0L

    private val idsF = arrayOf(FloatArray(maxLen))
    private val maskF = arrayOf(FloatArray(maxLen))
    private val zeroF = arrayOf(FloatArray(maxLen))
    private val zeroI = arrayOf(IntArray(maxLen))
    private var out: Array<FloatArray> = emptyArray()

    private val inference = Executors.newSingleThreadExecutor { r ->
        Thread({
            Process.setThreadPriority(Process.THREAD_PRIORITY_DISPLAY)
            r.run()
        }, "bratan-tflite")
    }.asCoroutineDispatcher()

    val available: Boolean get() = configured

    fun prepare(): Boolean {
        return try {
            val assets = context.assets.list("")?.toSet().orEmpty()
            if (MODEL !in assets || VOCAB !in assets || INTENTS !in assets)
                return fail("нет файлов в assets")
            intents = context.assets.open(INTENTS).bufferedReader().readLines()
                .filter { it.isNotBlank() && !it.startsWith("#") }
                .map { line ->
                    val p = line.split('|')
                    p[0].trim() to (p.getOrNull(1)?.split(',')
                        ?.map { it.trim() }?.filter { it.isNotBlank() } ?: emptyList())
                }
            if (intents.isEmpty()) return fail("intents.txt пуст")
            out = arrayOf(FloatArray(intents.size))
            registerThermal()
            configured = true
            true
        } catch (e: Exception) {
            fail(e.message ?: e.javaClass.simpleName)
        }
    }

    private fun fail(reason: String): Boolean {
        Log.i("Bratan", "Локальная модель недоступна: $reason")
        configured = false
        return false
    }

    private fun registerThermal() {
        val pm = context.getSystemService(Context.POWER_SERVICE) as PowerManager
        pm.addThermalStatusListener(ContextCompat.getMainExecutor(context)) { status ->
            desiredThreads = if (status >= PowerManager.THERMAL_STATUS_SEVERE) 1 else 2
        }
    }

    private fun enoughMemory(): Boolean {
        val am = context.getSystemService(ActivityManager::class.java)
        val mi = ActivityManager.MemoryInfo().also { am.getMemoryInfo(it) }
        return !mi.lowMemory && mi.availMem > MIN_FREE_BYTES
    }

    private fun ensureLoadedLocked(): Boolean {
        if (interpreter != null) {
            if (currentThreads != desiredThreads) createInterpreter(desiredThreads)
            return true
        }
        if (!enoughMemory()) {
            ServiceBus.post("TFLite пропущен: мало свободной памяти")
            return false
        }
        return try {
            afd = context.assets.openFd(MODEL)
            buffer = FileInputStream(afd!!.fileDescriptor).channel.map(
                FileChannel.MapMode.READ_ONLY, afd!!.startOffset, afd!!.declaredLength)
            tokenizer = BertTokenizer.fromAsset(context, VOCAB) ?: throw IllegalStateException("vocab")
            createInterpreter(desiredThreads)
            ServiceBus.post("TFLite загружен")
            true
        } catch (e: Exception) {
            Log.w("Bratan", "TFLite load: ${e.message}")
            unloadLocked()
            false
        }
    }

    private fun createInterpreter(threads: Int) {
        interpreter?.close()
        interpreter = Interpreter(buffer!!, Interpreter.Options().apply {
            setNumThreads(threads)
            setUseXNNPACK(true)
        })
        currentThreads = threads
    }

    override suspend fun process(text: String): CommandResult? = withContext(inference) {
        mutex.withLock {
            if (!configured || !ensureLoadedLocked()) return@withLock null
            lastUsedAt = SystemClock.elapsedRealtime()
            val itp = interpreter!!
            val (ids, mask) = tokenizer!!.encode(text, maxLen)
            for (i in 0 until maxLen) {
                idsF[0][i] = ids[i].toFloat()
                maskF[0][i] = mask[i].toFloat()
            }

            val inputs = arrayOfNulls<Any>(itp.inputTensorCount)
            for (i in 0 until itp.inputTensorCount) {
                val tensor = itp.getInputTensor(i)
                val name = tensor.name().lowercase()
                val isFloat = tensor.dataType() == DataType.FLOAT32
                inputs[i] = when {
                    "mask" in name -> if (isFloat) maskF else arrayOf(mask)
                    "type" in name || "segment" in name -> if (isFloat) zeroF else zeroI
                    else -> if (isFloat) idsF else arrayOf(ids)
                }
            }
            itp.runForMultipleInputsOutputs(inputs, mapOf(0 to out))

            val probs = softmax(out[0])
            val best = probs.indices.maxBy { probs[it] }
            val (intent, actions) = intents[best]
            if (intent == "UNKNOWN" || probs[best] < 0.5f) null
            else CommandResult(intent, probs[best], actions, emptyMap(), CommandResult.Source.TFLITE)
        }
    }

    private fun softmax(x: FloatArray): FloatArray {
        val max = x.max()
        val e = DoubleArray(x.size) { exp((x[it] - max).toDouble()) }
        val sum = e.sum()
        return FloatArray(x.size) { (e[it] / sum).toFloat() }
    }

    suspend fun unloadIfIdle(idleMs: Long) {
        if (interpreter == null) return
        if (SystemClock.elapsedRealtime() - lastUsedAt < idleMs) return
        mutex.withLock {
            unloadLocked()
            ServiceBus.post("TFLite выгружен (простой)")
        }
    }

    suspend fun unload() = mutex.withLock { unloadLocked() }

    private fun unloadLocked() {
        interpreter?.close(); interpreter = null
        buffer = null
        afd?.close(); afd = null
        tokenizer = null
        currentThreads = 0
    }

    fun release() {
        runBlocking { mutex.withLock { unloadLocked() } }
        inference.close()
    }

    companion object {
        const val MODEL = "mobilebert_quant.tflite"
        const val VOCAB = "vocab.txt"
        const val INTENTS = "intents.txt"
        private const val MIN_FREE_BYTES = 512L * 1024 * 1024
    }
}
