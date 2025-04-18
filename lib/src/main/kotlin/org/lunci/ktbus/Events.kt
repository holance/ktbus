package org.lunci.ktbus

import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean

// Helper data class for the request function's result
sealed class RequestResult<out R> {
    data class Success<out R>(val data: R) : RequestResult<R>()
    data class Error(val message: String, val exception: Throwable? = null) :
        RequestResult<Nothing>()

    object Timeout : RequestResult<Nothing>()
}

@Suppress("unused")
// Request event - carries data and a unique ID
data class RequestEvent<T, E>(
    val requestId: String = UUID.randomUUID().toString(), // Unique ID for correlation
    val data: T,
    val bus: KtBus,
    val channel: String,
) {
    private val resultSent = AtomicBoolean(false)

    fun setResult(result: E) {
        if (trySetResult(result).not()) {
            throw IllegalStateException("Result or error already set for this RequestEvent")
        }
    }

    fun trySetResult(result: E): Boolean {
        if (resultSent.compareAndSet(false, true)) {
            val response = ResponseEvent<E>(requestId, result)
            bus.post(response, channel)
            return true
        } else {
            return false
        }
    }

    fun setError(message: String) {
        if (trySetError(message).not()) {
            throw IllegalStateException("Result or error already set for this RequestEvent")
        }
    }

    fun trySetError(message: String): Boolean {
        if (resultSent.compareAndSet(false, true)) {
            val response = ResponseEvent<E>(requestId, error = message)
            bus.post(response, channel)
            return true
        } else {
            return false
        }
    }
}

// Response event - carries data and the ID of the request it's responding to
internal data class ResponseEvent<E>(
    val correlationId: String, // Matches the requestId of the RequestEvent
    val data: E? = null,
    val error: String? = null
)