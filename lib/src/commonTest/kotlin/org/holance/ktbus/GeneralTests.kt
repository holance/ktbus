package org.holance.ktbus

import kotlin.test.*

class KtBusGeneralTest {
    val bus = KtBus.getDefault()

    @Test
    fun busSubscription() {
        val iteration = 100
        val tests = arrayOf(TestClass(), TestClass())
        tests.forEach { it.setup() }
        for (i in 0 until iteration) {
            bus.post(Event1(i))
            bus.post(Event2(i + 1))
            bus.post(Event3(i + 2))
        }
        tests.forEach { test ->
            assertEquals(iteration, test.event1Result.size)
            assertEquals(iteration, test.event11Result.size)
            assertEquals(iteration, test.event2Result.size)
            assertEquals(iteration, test.event3Result.size)
            for (i in 0 until iteration) {
                assertEquals(i, test.event1Result[i].value)
                assertEquals(i, test.event11Result[i].value)
                assertEquals(i + 1, test.event2Result[i].value)
                assertEquals(i + 2, test.event3Result[i].value)
            }
        }
        tests.forEach { it.tearDown() }
        bus.post(Event1(1))
        bus.post(Event2(2))
        bus.post(Event3(3))
        tests.forEach { test ->
            assertEquals(0, test.event1Result.size)
            assertEquals(0, test.event11Result.size)
            assertEquals(0, test.event2Result.size)
            assertEquals(0, test.event3Result.size)
        }
    }

    @Test
    fun postSticky() {
        bus.postSticky(Event1(0))
        bus.postSticky(Event2(1))
        bus.postSticky(Event3(2))
        val tests = arrayOf(TestClass(), TestClass())

        tests.forEach { test ->
            test.setup()
            assertEquals(1, test.event1Result.size)
            assertEquals(1, test.event11Result.size)
            assertEquals(1, test.event2Result.size)
            assertEquals(1, test.event3Result.size)
            assertEquals(0, test.event1Result[0].value)
            assertEquals(0, test.event11Result[0].value)
            assertEquals(1, test.event2Result[0].value)
            assertEquals(2, test.event3Result[0].value)
            test.tearDown()
        }

        bus.clearStickyEvent(Event1::class)
        bus.clearStickyEvent(Event2::class)
        bus.clearStickyEvent(Event3::class)

        tests.forEach { test ->
            test.setup()
            assertEquals(0, test.event1Result.size)
            assertEquals(0, test.event11Result.size)
            assertEquals(0, test.event2Result.size)
            assertEquals(0, test.event3Result.size)
            test.tearDown()
        }
    }

    @Suppress("unused")
    private class TestClass {
        val bus = KtBus.getDefault()

        val event1Result = mutableListOf<Event1>()
        val event11Result = mutableListOf<Event1>()
        val event2Result = mutableListOf<Event2>()
        val event3Result = mutableListOf<Event3>()

        fun setup() {
            bus.subscribe(this)
        }

        fun tearDown() {
            bus.unsubscribe(this)
            event1Result.clear()
            event11Result.clear()
            event2Result.clear()
            event3Result.clear()
        }

        @Subscribe
        fun onEvent1(event: Event1) {
            event1Result.add(event)
        }

        @Subscribe
        fun onEvent1_1(event: Event1) {
            event11Result.add(event)
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

