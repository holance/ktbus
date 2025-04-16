package org.lunci.ktbus

import kotlinx.coroutines.*
import org.junit.jupiter.api.Assertions.*
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
                bus.request<Event1, Event2>(Event1(i), { result: RequestResult<Event2> ->
                    when (result) {
                        is RequestResult.Success -> assertEquals(result.data.value, i + 1)
                        else -> fail("Unexpected result type")
                    }
                })
                bus.request<Event1, Event2>(Event1(i * 10), { result: RequestResult<Event2> ->
                    when (result) {
                        is RequestResult.Success -> assertEquals(result.data.value, i * 10 + 2)
                        else -> fail("Unexpected result type")
                    }
                }, "test2")
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

        @Subscribe
        fun onEvent(event: RequestEvent<Event1, Event2>) {
            event.setResult(Event2(event.data.value + 1))
        }

        @Subscribe(channel = "test2")
        fun onEventChannel(event: RequestEvent<Event1, Event2>) {
            event.setResult(Event2(event.data.value + 2))
        }
    }
}