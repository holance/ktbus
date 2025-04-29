package org.holance.samples.ktbus

import org.holance.ktbus.KtBus
import org.holance.ktbus.RequestHandler

class RequestSample {
    data class MyRequest(val message: String)
    data class MyResponse(val result: String)

    class HandleRequest {
        val bus = KtBus.getDefault()
        fun setup() {
            bus.subscribe(this)
        }

        fun tearDown() {
            bus.unsubscribe(this)
        }

        @RequestHandler
        fun handleRequest(request: MyRequest): MyResponse {
            // Process the request and return a response
            return MyResponse("Processed: ${request.message}")
        }
    }

    fun sendRequest() {
        val bus = KtBus.getDefault()
        val request = MyRequest("Hello, KtBus!")
        val response = bus.request<MyRequest, MyResponse>(request)
        println("Response: ${response.result}")
    }

    suspend fun sendRequestAsync() {
        val bus = KtBus.getDefault()
        val request = MyRequest("Hello, KtBus!")
        val response = bus.requestAsync<MyRequest, MyResponse>(request)
        println("Response: ${response.result}")
    }
}

