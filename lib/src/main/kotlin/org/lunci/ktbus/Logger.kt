package org.lunci.ktbus


interface Logger {
    fun debug(message: String)
    fun error(message: String)
    fun info(message: String)
    fun warning(message: String)
    fun verbose(message: String)

    @Suppress("unused")
    fun d(message: String) {
        debug(message)
    }

    @Suppress("unused")
    fun e(message: String) {
        error(message)
    }

    @Suppress("unused")
    fun i(message: String) {
        info(message)
    }

    @Suppress("unused")
    fun w(message: String) {
        warning(message)
    }

    @Suppress("unused")
    fun v(message: String) {
        verbose(message)
    }
}