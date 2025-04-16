package org.lunci.ktbus

interface ChannelFactory {
    fun createChannel(obj: Any): String
}

// Default implementation
sealed class DefaultChannelFactory : ChannelFactory {
    companion object {
        const val DEFAULT_CHANNEL = ""
    }

    override fun createChannel(obj: Any): String = DEFAULT_CHANNEL
}
