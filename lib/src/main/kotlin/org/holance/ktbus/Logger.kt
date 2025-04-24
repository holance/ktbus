package org.holance.ktbus

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