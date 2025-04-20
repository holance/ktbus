package org.holance.ktbus

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.fail

class KtBusChannelTest {
    val bus = KtBus.getDefault()

    @Test
    fun channelTest() {
        val iteration = 100
        var test1 = TestClassWithChannel("Channel1")
        var test2 = TestClassWithChannel("Channel2")
        val tests = listOf(test1, test2)
        tests.forEach { it.setup() }
        for (i in 0 until iteration) {
            bus.post(Event1(i), "Channel1")
            bus.post(Event1(i + 1), "Channel2")
            bus.post(Event1(i + 2))
        }
        assertEquals(iteration, test1.eventCh1Result.size)
        assertEquals(iteration, test2.eventCh1Result.size)
        assertEquals(iteration, test1.eventChDefaultResult.size)
        assertEquals(iteration, test2.eventChDefaultResult.size)
        for (i in 0 until iteration) {
            assertEquals(i, test1.eventCh1Result[i].value)
            assertEquals(i + 1, test2.eventCh1Result[i].value)
            assertEquals(i + 2, test1.eventChDefaultResult[i].value)
            assertEquals(i + 2, test2.eventChDefaultResult[i].value)
        }
        tests.forEach { it.tearDown() }
    }

    @Suppress("unused")
    private class TestClassWithChannel(val channel: String) {
        class Factory : ChannelFactory {
            override fun createChannel(obj: Any): String {
                return if (obj is TestClassWithChannel) {
                    obj.channel
                } else {
                    fail("obj is not TestClassWithChannel")
                }
            }
        }

        val bus = KtBus.getDefault()

        val eventChDefaultResult = mutableListOf<Event1>()
        val eventCh1Result = mutableListOf<Event1>()

        fun setup() {
            bus.subscribe(this)
        }

        fun tearDown() {
            bus.unsubscribe(this)
            eventChDefaultResult.clear()
            eventCh1Result.clear()
        }

        @Subscribe
        fun onEventChDefault(event: Event1) {
            eventChDefaultResult.add(event)
        }

        @Subscribe(channelFactory = Factory::class)
        fun onEventCh1(event: Event1) {
            eventCh1Result.add(event)
        }
    }
}

