package org.holance.ktbus

// --- EventBus Implementation ---

/** Custom exception for request errors */
open class RequestException(message: String, cause: Throwable? = null) : RuntimeException(message, cause)
class NoRequestHandlerException(message: String) : RequestException(message)
class RequestTimeoutException(message: String) : RequestException(message)