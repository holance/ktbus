package org.holance.ktbus

/**
 * Interface for logging messages at different levels of severity.
 *
 * This interface provides a standardized way to log messages with varying levels of detail,
 * from debug information to critical errors. Implementors of this interface can define how
 * these messages are handled, such as writing them to a file, console, or remote server.
 *
 * Each log method takes a lambda expression that returns a String. This allows for lazy
 * evaluation of the log message, meaning the message is only constructed if the log level
 * is enabled. This is particularly useful when constructing complex log messages that may
 * involve string concatenation or other potentially expensive operations.
 */
@Suppress("unused")
interface Logger {
    fun debug(message: () -> String)
    fun info(message: () -> String)
    fun warning(message: () -> String)
    fun error(message: () -> String)
    fun verbose(message: () -> String)
}
@Suppress("unused")
fun Logger.d(message: String) = debug { message }
@Suppress("unused")
fun Logger.e(message: String) = error { message }
@Suppress("unused")
fun Logger.i(message: String) = info { message }
@Suppress("unused")
fun Logger.w(message: String) = warning { message }
@Suppress("unused")
fun Logger.v(message: String) = verbose { message }