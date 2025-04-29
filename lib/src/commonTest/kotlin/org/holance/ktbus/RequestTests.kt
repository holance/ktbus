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
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds
data class Add1Request(val value: Int)
data class Multiply2Request(val value: Int)
data class Multiply3Request(val value: Int)
data class Resp(val value: Int)

class KtBusRequestTests {
    val bus = KtBus.getDefault()

    @Test
    fun requestTest() {
        val iteration = 100
        val test = TestClass()
        test.setup()

        for (i in 0 until iteration) {
            var result = bus.request<Add1Request, Resp>(Add1Request(i))
            assertEquals(i + 1, result.value)
            result = bus.request<Multiply2Request, Resp>(Multiply2Request(i))
            assertEquals(i * 2, result.value)
        }
        test.tearDown()
    }

    @Test
    fun requestTestAsync() {
        val iteration = 100
        val test = TestClass()
        val scope = CoroutineScope(Dispatchers.IO)
        test.setup()
        val result1 = ConcurrentSkipListSet<Int>()
        val result2 = ConcurrentSkipListSet<Int>()

        for (i in 0 until iteration) {
            scope.launch {
                val result = bus.requestAsync<Add1Request, Resp>(Add1Request(i))
                assertEquals(i + 1, result.value)
                result1.add(result.value)
            }
            scope.launch {
                val result = bus.requestAsync<Multiply2Request, Resp>(Multiply2Request(i))
                assertEquals(i * 2, result.value)
                result2.add(result.value)
            }
        }
        runBlocking {
            val result = withTimeoutOrNull<Boolean>(20.seconds) {
                while (result2.size < iteration) {
                    delay(10)
                }
                return@withTimeoutOrNull true
            }
            assertNotNull(result)
        }
        test.tearDown()
        for (i in 0 until iteration) {
            assertTrue(result1.contains(i + 1))
            assertTrue(result2.contains(i * 2))
        }
    }

    @Test
    fun requestTestTimeout() {
        val test = TestClass()
        test.setup()
        assertFailsWith(RequestTimeoutException::class) {
            bus.request<Multiply3Request, Resp>(Multiply3Request(10), timeout = 1.seconds)
        }
        test.tearDown()
    }

    @Test
    fun requestTestNoHandler() {
        assertFailsWith(NoRequestHandlerException::class) {
            bus.request<Multiply3Request, Resp>(Multiply3Request(10))
        }
    }

    @Test
    fun requestTestChannel() {
        val iteration = 100
        val test = TestClass()
        test.setup()

        for (i in 0 until iteration) {
            var result = bus.request<Add1Request, Resp>(Add1Request(i))
            assertEquals(i + 1, result.value)
            result = bus.request<Add1Request, Resp>(Add1Request(i), channel = "test")
            assertEquals(i * 3, result.value)
        }
        test.tearDown()
    }

    @Test
    fun requestTestChannelAsync() {
        val iteration = 100
        val test = TestClass()
        val scope = CoroutineScope(Dispatchers.IO)
        test.setup()
        val result1 = ConcurrentSkipListSet<Int>()
        val result2 = ConcurrentSkipListSet<Int>()
        for (i in 0 until iteration) {
            scope.launch {
                var result = bus.requestAsync<Add1Request, Resp>(Add1Request(i))
                assertEquals(i + 1, result.value)
                result1.add(result.value)
            }
            scope.launch {
                val result = bus.requestAsync<Add1Request, Resp>(Add1Request(i), channel = "test")
                assertEquals(i * 3, result.value)
                result2.add(result.value)
            }
        }
        runBlocking {
            val result = withTimeoutOrNull<Boolean>(20.seconds) {
                while (result1.size < iteration || result2.size < iteration) {
                    delay(10)
                }
                return@withTimeoutOrNull true
            }
            assertNotNull(result)
        }
        test.tearDown()
        for (i in 0 until iteration) {
            assertTrue(result1.contains(i + 1))
            assertTrue(result2.contains(i * 3))
        }
    }

    @Suppress("unused")
    private class TestClass() {
        val bus = KtBus.getDefault()
        fun setup() {
            bus.subscribe(this)
        }

        fun tearDown() {
            bus.unsubscribe(this)
            assertEquals(0, bus.getRequestHandlerCount(Add1Request::class))
            assertEquals(0, bus.getRequestHandlerCount(Multiply2Request::class))
            assertEquals(0, bus.getRequestHandlerCount(Multiply3Request::class))
        }

        @RequestHandler(scope = DispatcherTypes.IO)
        fun onEvent1(event: Add1Request): Resp {
            return Resp(event.value + 1)
        }

        @RequestHandler(scope = DispatcherTypes.IO)
        suspend fun onEvent2(event: Multiply2Request): Resp {
            delay(10.milliseconds)
            return Resp(event.value * 2)
        }

        @RequestHandler(scope = DispatcherTypes.IO)
        suspend fun onEvent3(event: Multiply3Request): Resp {
            delay(2.seconds)
            return Resp(event.value * 3)
        }

        @RequestHandler(scope = DispatcherTypes.IO, channel = "test")
        fun onEvent1Channel(event: Add1Request): Resp {
            return Resp(event.value * 3) // Intentionally wrong
        }
    }
}