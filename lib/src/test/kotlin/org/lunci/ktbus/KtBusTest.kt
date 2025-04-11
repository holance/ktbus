package org.lunci.ktbus

import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Assertions.*

class KtBusTest {
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
        for (i in 0 until iteration) {
            assertEquals(i, test.event1Result[i].value)
            assertEquals(i + 1, test.event2Result[i].value)
            assertEquals(i + 2, test.event3Result[i].value)
        }
        test.tearDown()
    }

    @Test
    fun postSticky() {
        bus.postSticky(Event1(0))
        bus.postSticky(Event2(1))
        bus.postSticky(Event3(2))
        val test = TestClass()
        test.setup()
        assertEquals(1, test.event1Result.size)
        assertEquals(1, test.event2Result.size)
        assertEquals(1, test.event3Result.size)
        assertEquals(0, test.event1Result[0].value)
        assertEquals(1, test.event2Result[0].value)
        assertEquals(2, test.event3Result[0].value)
        test.tearDown()
    }
}

data class Event1(val value: Int)
data class Event2(val value: Int)
data class Event3(val value: Int)

@Suppress("unused")
class TestClass {
    val bus = KtBus.getDefault()

    val event1Result = mutableListOf<Event1>()
    val event2Result = mutableListOf<Event2>()
    val event3Result = mutableListOf<Event3>()

    fun setup() {
        bus.subscribe(this)
    }

    fun tearDown() {
        bus.unsubscribe(this)
    }

    @Subscribe
    fun onEvent1(event: Event1) {
        event1Result.add(event)
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