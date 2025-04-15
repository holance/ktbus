package org.lunci.ktbus

import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Assertions.*

class KtBusExceptionTests {
    val bus = KtBus.getDefault()

    @Test
    fun busSubscription() {
        val iteration = 100
        val test = TestClass()
        test.setup()
        for (i in 0 until iteration) {
            bus.post(Event1(i))
            bus.post(Event2(i + 1))
            bus.post(Event3(i + 2))
        }
        assertEquals(iteration, test.event1Result.size)
        assertEquals(iteration, test.event2Result.size)
        assertEquals(iteration, test.event3Result.size)
        assertEquals(iteration, test.errorResult.size)
        for (i in 0 until iteration) {
            assertEquals(i, test.event1Result[i].value)
            assertEquals(i + 1, test.event2Result[i].value)
            assertEquals(i + 2, test.event3Result[i].value)
        }
        test.tearDown()
        bus.post(Event1(1))
        bus.post(Event2(2))
        bus.post(Event3(3))
        assertEquals(0, test.event1Result.size)
        assertEquals(0, test.event2Result.size)
        assertEquals(0, test.event3Result.size)
        assertEquals(0, test.errorResult.size)
    }

    @Suppress("unused")
    private class TestClass {
        val bus = KtBus.getDefault()

        val event1Result = mutableListOf<Event1>()
        val event2Result = mutableListOf<Event2>()
        val event3Result = mutableListOf<Event3>()
        val errorResult = mutableListOf<String>()

        fun setup() {
            KtBus.setLogger(object : Logger {
                override fun debug(message: String) {
                }

                override fun error(message: String) {
                    errorResult.add(message)
                }

                override fun info(message: String) {
                }

                override fun warning(message: String) {
                }

                override fun verbose(message: String) {
                }
            })
            bus.subscribe(this)
        }

        fun tearDown() {
            KtBus.resetLogger()
            bus.unsubscribe(this)
            event1Result.clear()
            event2Result.clear()
            event3Result.clear()
            errorResult.clear()
        }

        @Subscribe
        fun onEvent1(event: Event1) {
            event1Result.add(event)
            throw Exception("test")
        }

        @Subscribe
        fun onEvent2(event: Event2) {
            event2Result.add(event)
        }

        @Subscribe
        fun onEvent3(event: Event3) {
            event3Result.add(event)
        }
    }
}


