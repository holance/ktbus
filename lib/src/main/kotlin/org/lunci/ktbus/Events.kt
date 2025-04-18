package org.lunci.ktbus

import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean

// Helper data class for the request function's result
sealed class Response<out R> {
    data class Success<out R>(val data: R) : Response<R>()
    data class Error(val message: String, val exception: Throwable? = null) :
        Response<Nothing>()

    object Timeout : Response<Nothing>()
}

@Suppress("unused")
// Request event - carries data and a unique ID
data class Request<T, E>(
    val requestId: String = UUID.randomUUID().toString(), // Unique ID for correlation
    val data: T,
    val bus: KtBus,
    val channel: String,
) {
    private val resultSent = AtomicBoolean(false)

    /**
     * Sets the result of this RequestEvent.
     *
     * This method attempts to set the result of the event. If the result or error has already been set,
     * it throws an IllegalStateException.
     *
     * @param result The result to be set.
     * @throws IllegalStateException if the result or error has already been set for this RequestEvent.
     */
    fun setResult(result: E) {
        if (trySetResult(result).not()) {
            throw IllegalStateException("Result or error already set for this RequestEvent")
        }
    }

    /**
     * Attempts to set the result of the operation.
     *
     * This function is designed to be called once to deliver the result of an asynchronous operation.
     *
     * @param result The result of the operation to be sent.
     * @return `true` if the result was successfully set and sent, `false` if the result had already
     *         been set (and therefore this call did nothing).
     */
    fun trySetResult(result: E): Boolean {
        if (resultSent.compareAndSet(false, true)) {
            val response = ResponseEvent<E>(requestId, result)
            bus.post(response, channel)
            return true
        } else {
            return false
        }
    }


    /**
     * Indicates whether a result has already been set.
     *
     *
     * @return `true` if this is the first call and the result is now marked as set,
     *         `false` if a result has already been set.
     */
    fun hasResult(): Boolean {
        return resultSent.compareAndSet(false, true)
    }

    /**
     * Sets an error message for the request event.
     *
     * This function attempts to set the provided [message] as an error for the current request event.
     * If an error or a successful result has already been set, it throws an [IllegalStateException].
     *
     * @param message The error message to be set. Must not be null.
     * @throws IllegalStateException If a result or error has already been set for this request event.
     */
    fun setError(message: String) {
        if (trySetError(message).not()) {
            throw IllegalStateException("Result or error already set for this RequestEvent")
        }
    }

    /**
     * Attempts to set an error message for the current operation.
     *
     * This function is designed to be called only once to report an error.
     *
     * @param message The error message to be included in the response.
     * @return `true` if the error was successfully set, `false` otherwise (meaning
     *         a response has already been set).
     *
     * @see ResponseEvent
     * @see resultSent
     * @see bus
     * @see channel
     * @see requestId
     *
     * @sample
     *  // Example usage:
     *  if(!trySetError("Something went wrong")){
     *    //log error: could not send error because response already sent
     *  }
     */
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