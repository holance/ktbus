package org.lunci.ktbus

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class KtBusChannelTest {
    val bus = KtBus.getDefault()

    @Test
    fun channelTest() {
        val iteration = 100
        var test = TestClassWithChannel()
        test.setup()
        for (i in 0 until iteration) {
            bus.post(Event1(i), TestClassWithChannel.CHANNEL_1)
            bus.post(Event1(i + 1), TestClassWithChannel.CHANNEL_2)
        }
        assertEquals(iteration, test.eventCh1Result.size)
        assertEquals(iteration, test.eventCh2Result.size)
        for (i in 0 until iteration) {
            assertEquals(i, test.eventCh1Result[i].value)
            assertEquals(i + 1, test.eventCh2Result[i].value)
        }
        test.tearDown()
    }

    @Suppress("unused")
    private class TestClassWithChannel {
        val bus = KtBus.getDefault()
        companion object {
            const val CHANNEL_1 = "channel 1"
            const val CHANNEL_2 = "channel 2"
        }

        class TestChannel1 : ChannelFactory {
            override fun createChannel(obj: Any): String {
                return CHANNEL_1
            }
        }

        class TestChannel2 : ChannelFactory {
            override fun createChannel(obj: Any): String {
                return CHANNEL_2
            }
        }

        val eventChDefaultResult = mutableListOf<Event1>()
        val eventCh1Result = mutableListOf<Event1>()
        val eventCh2Result = mutableListOf<Event1>()

        fun setup() {
            bus.subscribe(this)
        }

        fun tearDown() {
            bus.unsubscribe(this)
            eventChDefaultResult.clear()
            eventCh1Result.clear()
            eventCh2Result.clear()
        }

        @Subscribe
        fun onEventChDefault(event: Event1) {
            eventChDefaultResult.add(event)
        }

        @Subscribe(channelFactory = TestChannel1::class)
        fun onEventCh1(event: Event1) {
            eventCh1Result.add(event)
        }

        @Subscribe(channelFactory = TestChannel2::class)
        fun onEventCh2(event: Event1) {
            eventCh2Result.add(event)
        }
    }
}

