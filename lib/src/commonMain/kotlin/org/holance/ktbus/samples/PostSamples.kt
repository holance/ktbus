package org.holance.ktbus.samples

import org.holance.ktbus.KtBus

class PostSamples {
    val bus = KtBus.getDefault()
    fun postEvent() {
        bus.post(Event1("Hello, world!"))
    }
    
    fun postEventWithChannel() {
        bus.post(Event1("Hello, world!"), "myChannel")
    }

    suspend fun postEventAsync() {
        bus.postAsync(Event1("Hello, world!"))
    }

    suspend fun postEventWithChannelAsync() {
        bus.postAsync(Event1("Hello, world!"), "myChannel")
    }
}