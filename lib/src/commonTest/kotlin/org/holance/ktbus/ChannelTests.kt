package org.holance.ktbus

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import java.util.concurrent.ConcurrentSkipListSet
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlin.test.fail
import kotlin.time.Duration.Companion.seconds

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
            assertTrue(test1.eventCh1Result.contains(i))
            assertTrue(test2.eventCh1Result.contains(i + 1))
            assertTrue(test1.eventChDefaultResult.contains(i + 2))
            assertTrue(test2.eventChDefaultResult.contains(i + 2))
        }
        tests.forEach { it.tearDown() }
    }

    @Test
    fun channelTestAsync() {
        val iteration = 100
        var test1 = TestClassWithChannel("Channel1")
        var test2 = TestClassWithChannel("Channel2")
        val tests = listOf(test1, test2)
        tests.forEach { it.setup() }
        val scope = CoroutineScope(Dispatchers.IO)

        for (i in 0 until iteration) {
            scope.launch {
                bus.postAsync(Event1(i), "Channel1")
                bus.postAsync(Event1(i + 1), "Channel2")
                bus.postAsync(Event1(i + 2))
            }
        }
        runBlocking {
            val result = withTimeoutOrNull<Boolean>(20.seconds) {
                while (test1.eventCh1Result.size < iteration || test2.eventCh1Result.size < iteration
                    || test1.eventChDefaultResult.size < iteration || test2.eventChDefaultResult.size < iteration
                ) {
                    delay(10)
                }
                return@withTimeoutOrNull true
            }
            assertNotNull(result)
        }
        assertEquals(iteration, test1.eventCh1Result.size)
        assertEquals(iteration, test2.eventCh1Result.size)
        assertEquals(iteration, test1.eventChDefaultResult.size)
        assertEquals(iteration, test2.eventChDefaultResult.size)
        for (i in 0 until iteration) {
            assertTrue(test1.eventCh1Result.contains(i))
            assertTrue(test2.eventCh1Result.contains(i + 1))
            assertTrue(test1.eventChDefaultResult.contains(i + 2))
            assertTrue(test2.eventChDefaultResult.contains(i + 2))
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

        val eventChDefaultResult = ConcurrentSkipListSet<Int>()
        val eventCh1Result = ConcurrentSkipListSet<Int>()

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
            eventChDefaultResult.add(event.value)
        }

        @Subscribe(channelFactory = Factory::class)
        fun onEventCh1(event: Event1) {
            eventCh1Result.add(event.value)
        }
    }
}

