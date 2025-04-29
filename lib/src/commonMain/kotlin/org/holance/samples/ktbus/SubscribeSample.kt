package org.holance.samples.ktbus

import org.holance.ktbus.*

class SubscribeSample {
    val bus = KtBus.getDefault()

    fun setup() {
        bus.subscribe(this)
    }

    fun tearDown() {
        bus.unsubscribe(this)
    }

    @Subscribe
    fun onEvent(event: Event1) {
        println("Received event: ${event.message}")
    }

    @Subscribe(channel = "myChannel")
    fun onEventFromMyChannel(event: Event1) {
        println("Received event from myChannel: ${event.message}")
    }

    @Subscribe(scope = DispatcherTypes.IO)
    fun onEventOnIO(event: Event1) {
        println("Received event and process on IO thread: ${event.message}")
    }
}

