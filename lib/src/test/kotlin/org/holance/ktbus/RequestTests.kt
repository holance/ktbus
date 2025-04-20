package org.holance.ktbus

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.fail
import org.junit.jupiter.api.Test

class KtBusRequestTests {
    val bus = KtBus.getDefault()

    @Test
    fun requestTest() {
        val iteration = 100
        val test = TestClass()
        val scope = CoroutineScope(Dispatchers.IO)
        test.setup()
        val job = scope.launch {
            for (i in 0 until iteration) {
                scope.launch {
                    bus.request<Event1, Event2>(Event1(i)) { result: Response<Event2> ->
                        when (result) {
                            is Response.Success -> assertEquals(result.data.value, i + 1)
                            else -> fail("Unexpected result type")
                        }
                    }
                    bus.request<Event2, Event3>(Event2(i * 100)) { result: Response<Event3> ->
                        when (result) {
                            is Response.Success -> assertEquals(result.data.value, i * 100 + 1)
                            else -> fail("Unexpected result type")
                        }
                    }
                    bus.request<Event1, Event2>(
                        Event1(i * 10),
                        channel = "test2"
                    ) { result: Response<Event2> ->
                        when (result) {
                            is Response.Success -> assertEquals(result.data.value, i * 10 + 2)
                            else -> fail("Unexpected result type")
                        }
                    }
                    bus.request<Event1, Event2>(
                        Event1(i * 10),
                        channel = "test3"
                    ) { result: Response<Event2> ->
                        when (result) {
                            is Response.Error -> assertEquals(result.message, "Error occurred")
                            else -> fail("Unexpected result type")
                        }
                    }
                }
            }
        }
        runBlocking { job.join() }
        test.tearDown()
    }

    @Suppress("unused")
    private class TestClass() {
        val bus = KtBus.getDefault()
        fun setup() {
            bus.subscribe(this)
        }

        fun tearDown() {
            bus.unsubscribe(this)
        }

        @Subscribe(scope = DispatcherTypes.IO)
        fun onEvent1To2(event: Request<Event1, Event2>) {
            event.setResult(Event2(event.data.value + 1))
        }

        @Subscribe
        fun onEvent2To3(event: Request<Event2, Event3>) {
            event.setResult(Event3(event.data.value + 1))
        }

        @Subscribe
        fun onEvent2To3FailSetResult(event: Request<Event1, Event2>) {
            val scope = CoroutineScope(Dispatchers.IO)
            scope.launch {
                delay(500)
                assertFalse(event.trySetResult(Event2(event.data.value + 1)))
            }
        }

        @Subscribe(channel = "test2", scope = DispatcherTypes.IO)
        fun onEventChannel(event: Request<Event1, Event2>) {
            event.setResult(Event2(event.data.value + 2))
        }

        @Subscribe(channel = "test3")
        fun onEventChannelError(event: Request<Event1, Event2>) {
            event.setError("Error occurred")
        }
    }
}