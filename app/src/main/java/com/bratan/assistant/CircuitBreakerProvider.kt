package com.bratan.assistant

import android.os.SystemClock
import kotlinx.coroutines.CancellationException
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong

/** [OPT] 3 сбоя подряд → облако отключено на 10 минут. */
class CircuitBreakerProvider(
    private val delegate: IntentProvider,
    private val failureThreshold: Int = 3,
    private val openMs: Long = 10 * 60_000L
) : IntentProvider {
    private val failures = AtomicInteger(0)
    private val openedAt = AtomicLong(0)

    override suspend fun process(text: String): CommandResult? {
        val opened = openedAt.get()
        if (opened != 0L) {
            if (SystemClock.elapsedRealtime() - opened < openMs) return null
            openedAt.set(0); failures.set(0)
        }
        return try {
            val r = delegate.process(text)
            failures.set(0)
            r
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            if (failures.incrementAndGet() >= failureThreshold) {
                openedAt.set(SystemClock.elapsedRealtime())
            }
            null
        }
    }
}
