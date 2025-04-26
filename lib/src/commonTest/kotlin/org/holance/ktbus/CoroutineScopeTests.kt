package org.holance.ktbus
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.yield
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class CoroutineScopeTests {
    val bus = KtBus.getDefault()

    @Test
    fun scopedHandlerTests() {
        val iteration = 10
        val tests = arrayOf(TestClass(), TestClass())
        tests.forEach { it.setup() }
        for (i in 0 until iteration) {
            bus.post(Event1(i))
        }
        runBlocking {
            val success = withTimeoutOrNull(10000) {
                while (tests.any { it.event1Result.size < iteration }) {
                    delay(10)
                    yield()
                }
                true
            } == true
            assertTrue(success)
        }
        tests.forEach { test ->
            assertEquals(iteration, test.event1Result.size)
            assertEquals(iteration, test.event11Result.size)
            test.event1Result.sortBy { it.value }
            for (i in 0 until iteration) {
                assertEquals(i, test.event1Result[i].value)
                assertEquals(i, test.event11Result[i].value)
            }
        }
        tests.forEach { it.tearDown() }
        bus.post(Event1(1))
        tests.forEach { test ->
            assertEquals(0, test.event1Result.size)
            assertEquals(0, test.event11Result.size)
        }
    }

    @Suppress("unused")
    private class TestClass {
        val bus = KtBus.getDefault()

        val event1Result = mutableListOf<Event1>()
        val event11Result = mutableListOf<Event1>()
        val mutex = Mutex()

        fun setup() {
            bus.subscribe(this)
        }

        fun tearDown() {
            bus.unsubscribe(this)
            event1Result.clear()
            event11Result.clear()
        }

        @Subscribe(scope = DispatcherTypes.IO)
        suspend fun onEvent1(event: Event1) {
            delay(Random.nextLong(1, 10))
            mutex.withLock {
                event1Result.add(event)
            }
        }

        @Subscribe(scope = DispatcherTypes.Default)
        fun onEvent1_1(event: Event1) {
            event11Result.add(event)
        }
    }
}

