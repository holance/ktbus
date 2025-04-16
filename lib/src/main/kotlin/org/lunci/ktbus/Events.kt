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

// Request event - carries data and a unique ID
internal data class RequestEvent<T, E>(
    val requestId: String = UUID.randomUUID().toString(), // Unique ID for correlation
    val data: T,
    val bus: KtBus,
    val channel: String,
) {
    private val resultSent = AtomicBoolean(false)

    fun setResult(result: E) {
        if (resultSent.compareAndSet(false, true)) {
            val response = ResponseEvent<E>(requestId, result)
            bus.post(response, channel)
        } else {
            throw IllegalStateException("Result or error already set for this RequestEvent")
        }
    }

    fun setError(message: String) {
        if (resultSent.compareAndSet(false, true)) {
            val response = ResponseEvent<E>(requestId, error = message)
            bus.post(response, channel)
        } else {
            throw IllegalStateException("Result or error already set for this RequestEvent")
        }
    }
}

// Response event - carries data and the ID of the request it's responding to
internal data class ResponseEvent<E>(
    val correlationId: String, // Matches the requestId of the RequestEvent
    val data: E? = null,
    val error: String? = null
)