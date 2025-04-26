package org.holance.ktbus

// --- EventBus Implementation ---
/** Marker interface for request objects */
interface Request<R : Any> // R is the expected Response type

/** Custom exception for request errors */
open class RequestException(message: String, cause: Throwable? = null) : RuntimeException(message, cause)
class NoRequestHandlerException(message: String) : RequestException(message)
class RequestTimeoutException(message: String) : RequestException(message)