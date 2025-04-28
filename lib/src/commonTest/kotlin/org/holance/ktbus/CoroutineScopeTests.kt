package org.holance.ktbus
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import java.util.concurrent.ConcurrentSkipListSet
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds

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
            val result = withTimeoutOrNull(10.seconds) {
                while (tests.any { it.event1Result.size < iteration }) {
                    delay(10)
                }
                true
            }
            assertNotNull(result)
        }
        tests.forEach { test ->
            assertEquals(iteration, test.event1Result.size)
            assertEquals(iteration, test.event11Result.size)
            for (i in 0 until iteration) {
                assertTrue(test.event1Result.contains(i * 2))
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

        val event1Result = ConcurrentSkipListSet<Int>()
        val event11Result = mutableListOf<Event1>()

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
            delay(Random.nextLong(1, 100))
            event1Result.add(event.value * 2)
        }

        @Subscribe(scope = DispatcherTypes.Default)
        fun onEvent1_1(event: Event1) {
            event11Result.add(event)
        }
    }
}

