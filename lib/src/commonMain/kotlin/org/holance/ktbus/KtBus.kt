@file:Suppress("UNCHECKED_CAST")
@file:OptIn(ExperimentalCoroutinesApi::class)

package org.holance.ktbus

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import java.lang.reflect.Method
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import kotlin.coroutines.resumeWithException
import kotlin.coroutines.suspendCoroutine
import kotlin.reflect.KClass
import kotlin.reflect.KFunction
import kotlin.reflect.KParameter
import kotlin.reflect.full.callSuspend
import kotlin.reflect.full.createInstance
import kotlin.reflect.full.findAnnotation
import kotlin.reflect.full.memberFunctions
import kotlin.reflect.jvm.javaMethod
import kotlin.reflect.jvm.jvmErasure
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

const val DefaultChannel = ""

enum class DispatcherTypes {
    Main,
    Default,
    IO,
    Unconfined
}

data class KtBusConfig(
    val bufferCapacity: Int = 100,
    val onBufferOverflow: BufferOverflow = BufferOverflow.SUSPEND
)

@Suppress("unused")
class KtBus(val config: KtBusConfig = KtBusConfig()) {
    /**
     * Companion object for managing global configurations and utilities related to the event bus.
     *
     * This object provides access to different coroutine scopes, a logger, and the central event bus instance.
     */
    companion object {
        private val mainScope = CoroutineScope(Dispatchers.Main)
        private val defaultScope = CoroutineScope(Dispatchers.Default)
        private val ioScope = CoroutineScope(Dispatchers.IO)
        private val unconfinedScope = CoroutineScope(Dispatchers.Unconfined)

        private var logger: Logger? = null
        private val instance = KtBus()
        var traceFunctionInvocation = false
        fun getDefault(): KtBus {
            return instance
        }

        fun setLogger(logger: Logger) {
            this.logger = logger
        }

        fun resetLogger() {
            logger = null
        }
    }

    data class DataWrapper<T : Any>(val data: T, val channel: String)

    /** Internal wrapper for requests to include a unique ID */
    data class RequestWrapper<R : Any>(
        val id: String,
        val channel: String,
        val payload: Request<R> // The actual user request object
    )

    /** Internal wrapper for responses */
    data class ResponseWrapper<R : Any>(
        val requestId: String,
        val channel: String,
        val data: R?,
        val error: Throwable? = null
    )

    // Stores a MutableSharedFlow for each event/request type KClass.
    private val flows = ConcurrentHashMap<KClass<*>, MutableSharedFlow<DataWrapper<*>>>()

    // Stores active subscription/handler jobs managed via annotations. Maps subscriber instance to its Job list.
    private val subscriptions = ConcurrentHashMap<Any, MutableList<Job>>()

    // Stores a MutableSharedFlow for each request type.
    private val requestFlows =
        ConcurrentHashMap<KClass<*>, MutableSharedFlow<RequestWrapper<*>>>()

    // Stores active request handlers.
    val requestHandlers =
        ConcurrentHashMap<KClass<*>, ConcurrentHashMap<Any, MutableSet<String>>>()
    private val requestTargetTypeMappings = ConcurrentHashMap<Any, MutableList<KClass<*>>>()

    // Stores pending requests waiting for a response. Maps Request ID to its CompletableDeferred.
    val pendingRequests =
        ConcurrentHashMap<String, CompletableDeferred<ResponseWrapper<*>>>()

    fun <T : Any> getFlow(dataType: KClass<out T>): MutableSharedFlow<DataWrapper<T>> {
        return flows.computeIfAbsent(dataType) {
            MutableSharedFlow<DataWrapper<*>>(
                replay = 1,
                extraBufferCapacity = config.bufferCapacity,
                onBufferOverflow = config.onBufferOverflow
            )
        } as MutableSharedFlow<DataWrapper<T>>
    }

    fun <R : Any> getRequestFlow(dataType: KClass<out R>): MutableSharedFlow<RequestWrapper<*>> {
        return requestFlows.computeIfAbsent(dataType) {
            MutableSharedFlow<RequestWrapper<*>>(
                replay = 1,
                extraBufferCapacity = config.bufferCapacity,
                onBufferOverflow = config.onBufferOverflow
            )
        }
    }

    private suspend fun postImpl(
        event: Any,
        channel: String = DefaultChannel,
        removeSticky: Boolean = true
    ) {
        if (event is RequestWrapper<*> || event is ResponseWrapper<*>) {
            logger?.w("Warning: Attempting to post internal wrapper type directly. Use request() method for requests.")
            return
        }
        val flow = getFlow(event::class)
        flow.emit(DataWrapper(event, channel))
        if (removeSticky) {
            flow.resetReplayCache()
        }
    }

    private suspend fun invokeSuspendFunction(
        method: Method,
        obj: Any,
        event: Any
    ) {
        suspendCoroutine<Unit> {
            try {
                method.invoke(obj, event, it)
            } catch (e: Exception) {
                it.resumeWithException(e)
            }
        }
    }

    /** Processes a method annotated with @Subscribe */
    private fun processSubscriber(
        target: Any,
        function: KFunction<*>,
        annotation: Subscribe,
        jobs: MutableList<Job>
    ) {
        if (function.parameters.size != 2) {
            throw IllegalArgumentException("Invalid number of parameters for @Subscribe method. Expected 1, got ${function.parameters.size}")
        }
        val parameters = function.parameters.filter { it.kind == KParameter.Kind.VALUE }
        if (parameters.isEmpty()) {
            throw IllegalArgumentException("Invalid @Subscribe method. No event parameter found.")
        }

        val eventTypeParam = parameters[0]
        val eventType = eventTypeParam.type.jvmErasure
        val source = "${target::class.simpleName}.${function.name}"
        val scope = when (annotation.scope) {
            DispatcherTypes.Main -> mainScope
            DispatcherTypes.Default -> defaultScope
            DispatcherTypes.IO -> ioScope
            DispatcherTypes.Unconfined -> unconfinedScope
        }
        val channel = if (annotation.channelFactory != DefaultChannelFactory::class) {
            annotation.channelFactory.createInstance().createChannel(target)
        } else {
            annotation.channel
        }
        // Ensure it's a specific, non-internal type
        if (eventType != Any::class && eventType != RequestWrapper::class && eventType != ResponseWrapper::class) {
            val specificFlow = getFlow(eventType as KClass<Any>)
            val job = scope.launch {
                specificFlow.collect { event ->
                    if (event.channel != channel) {
                        return@collect
                    }
                    try {
                        if (function.isSuspend) {
                            if (function.javaMethod != null) {
                                invokeSuspendFunction(function.javaMethod!!, target, event.data)
                            } else {
                                function.callSuspend(target, event.data)
                            }
                        } else {
                            if (function.javaMethod != null) {
                                function.javaMethod?.invoke(target, event.data)
                            } else {
                                function.call(target, event.data)
                            }
                        }
                    } catch (e: Throwable) {
                        logger?.e("Exception in event handler [$source]: $e")
                    }
                }
            }
            jobs.add(job)
            logger?.i("Subscribed method ${function.name} on ${target::class.simpleName} to event type ${eventType.simpleName}")
        } else {
            logger?.w("Warning: @Subscribe method ${function.name} on ${target::class.simpleName} has invalid parameter type (Any or internal wrapper).")
        }
    }

    /** Processes a method annotated with @RequestHandler */
    private fun processRequestHandler(
        target: Any,
        function: KFunction<*>,
        annotation: RequestHandler,
        jobs: MutableList<Job>
    ) {
        if (function.parameters.size != 2) {
            throw IllegalArgumentException("Invalid number of parameters for @Subscribe method. Expected 1, got ${function.parameters.size}")
        }
        val parameters = function.parameters.filter { it.kind == KParameter.Kind.VALUE }
        if (parameters.isEmpty()) {
            throw IllegalArgumentException("Invalid @Subscribe method. No event parameter found.")
        }

        val requestTypeParam = parameters[0]
        val requestType =
            requestTypeParam.type.jvmErasure // KClass of the request (e.g., MyRequest::class)
        val returnType =
            function.returnType.jvmErasure // KClass of the response (e.g., MyResponse::class)

        // Ensure it's a Request<*> type and returns a specific type
        if (Request::class.java.isAssignableFrom(requestType.java)
            && returnType != Unit::class && returnType != Any::class
        ) {
            val requestFlow =
                getRequestFlow(requestType as KClass<Request<*>>) // Flow of RequestWrapper<R>
            val scope = when (annotation.scope) {
                DispatcherTypes.Main -> mainScope
                DispatcherTypes.Default -> defaultScope
                DispatcherTypes.IO -> ioScope
                DispatcherTypes.Unconfined -> unconfinedScope
            }
            val source = "${target::class.simpleName}.${function.name}"
            val channel = if (annotation.channelFactory != DefaultChannelFactory::class) {
                annotation.channelFactory.createInstance().createChannel(target)
            } else {
                annotation.channel
            }

            // --- Register Request Handler ---
            val map =
                requestHandlers.computeIfAbsent(requestType) { ConcurrentHashMap<Any, MutableSet<String>>() }
            map.computeIfAbsent(target) { mutableSetOf<String>() }.add(channel)
            logger?.i(
                "Registered method ${function.name} on ${target::class.simpleName} as " +
                        "handler for request type ${requestType.simpleName} on channel $channel"
            )

            requestTargetTypeMappings.computeIfAbsent(target) { mutableListOf() }.add(requestType)

            // --- Launch Collector for this Request Type ---
            val job = scope.launch { // Use the provided scope for the collector
                requestFlow.collect { requestWrapperUntyped ->
                    // We receive RequestWrapper<*> here, need to ensure it's the correct type
                    if (requestType.isInstance(requestWrapperUntyped.payload)) {
                        if (requestWrapperUntyped.channel != channel) {
                            return@collect
                        }
                        val requestWrapper =
                            requestWrapperUntyped as RequestWrapper<Any> // Safe cast after check
                        val requestId = requestWrapper.id

                        // Find the deferred associated with this request ID
                        val deferred = pendingRequests[requestId]
                        if (deferred == null) {
                            logger?.w("Warning: Received request ${requestType.simpleName} (ID: $requestId) but no pending request found. Maybe timed out?")
                            return@collect // Ignore if no longer pending
                        }

                        var responseData: Any? = null
                        var responseError: Throwable? = null
                        try {
                            // Call the actual handler function (suspend or regular)
                            responseData = if (function.isSuspend) {

                                if (function.javaMethod != null) {
                                    invokeSuspendFunction(
                                        function.javaMethod!!,
                                        target,
                                        requestWrapper.payload
                                    )
                                } else {
                                    function.callSuspend(target, requestWrapper.payload)
                                }
                            } else {
                                if (function.javaMethod != null) {
                                    function.javaMethod?.invoke(target, requestWrapper.payload)
                                } else {
                                    function.call(target, requestWrapper.payload)
                                }
                            }
                            // Basic check: Handler should return non-null for success
                            if (responseData == null) {
                                responseError =
                                    IllegalStateException("Request handler ${function.name} returned null for ${requestType.simpleName} (ID: $requestId)")
                            }

                        } catch (e: Throwable) { // Catch any exception from the handler
                            logger?.e("Error calling [$source]: ${e.message}")
                            responseError = e
                        }

                        // Create the response wrapper
                        val responseWrapper =
                            ResponseWrapper(requestId, channel, responseData, responseError)

                        // Complete the deferred to unblock the original request() call
                        @Suppress("UNCHECKED_CAST")
                        if ((deferred as CompletableDeferred<ResponseWrapper<Any>>).complete(
                                responseWrapper
                            ).not()
                        ) {
                            logger?.d("Request may have been completed by another handler.")
                        }
                    }
                }
            }
            jobs.add(job) // Add collector job to the list for cancellation on unsubscribe

        } else {
            logger?.w("Warning: @RequestHandler method ${function.name} on ${target::class.simpleName} must accept a Request<R> parameter and return a specific type R (not Unit or Any).")
        }
    }

    // region --- Post Methods ---

    /**
     * Posts an event to the event bus.
     *
     * This function attempts to emit an event to the flow associated with its class type.
     * It handles internal wrapper types and manages sticky events based on the `removeSticky` flag.
     *
     * @param event The event to post. This can be any object.
     * @param channel The channel to post the event to. Defaults to [DefaultChannel]. Currently unused, but reserved for future use.
     *
     * **Behavior:**
     *
     * - **Internal Wrapper Prevention:**  Prevents the direct posting of internal wrapper types (`RequestWrapper` and `ResponseWrapper`).
     *   This ensures that these wrappers are only used internally by the request-response mechanism and not directly by external code.
     *   If an attempt is made to post a wrapper, a warning is logged, and returned.
     *
     * - **Sticky Event Management:** Post will remove sticky event automatically.
     */
    fun post(event: Any, channel: String = DefaultChannel) {
        runBlocking {
            postImpl(event, channel)
        }
    }

    /**
     * Posts an event to a channel as a sticky message.
     *
     * A sticky message is a message that persists across multiple sessions or until explicitly removed.
     * This function is a convenience wrapper around the `post` function, setting the `sticky` flag to `true`.
     *
     * @param event The event to be posted. This can be any object.
     * @param channel The channel to post the event to. Defaults to `DefaultChannel`.
     */
    fun postSticky(event: Any, channel: String = DefaultChannel) {
        runBlocking {
            postImpl(event, channel, false)
        }
    }

    /**
     * Attempts to post an event to a specific channel.
     *
     * This function is used to emit an event to a shared flow associated with its class type.
     * It handles internal wrapper types, sticky events, and ensures that the event is emitted successfully.
     *
     * @param event The event to be posted. It must not be an internal wrapper type (RequestWrapper or ResponseWrapper).
     * @param channel The channel to which the event belongs. Defaults to [DefaultChannel].
     *                While the parameter is present, it's not directly utilized in this function's logic.
     *                Its usage is implied to be managed within `getFlow()` method.
     * @param removeSticky If true, the sticky event (if any) for this event type will be removed from the replay cache after a successful post. Defaults to true.
     * @return True if the event was successfully posted, false otherwise.
     *
     * @throws IllegalStateException if the associated flow is not found for the event.
     * @throws ClassCastException if trying to emit incorrect event type to the flow.
     *
     * @see RequestWrapper
     * @see ResponseWrapper
     * @see MutableSharedFlow.tryEmit
     * @see MutableSharedFlow.resetReplayCache
     */
    fun tryPost(
        event: Any,
        channel: String = DefaultChannel,
        removeSticky: Boolean = true
    ): Boolean {
        // Ensure we don't accidentally post internal wrappers
        if (event is RequestWrapper<*> || event is ResponseWrapper<*>) {
            logger?.w("Warning: Attempting to post internal wrapper type directly. Use request() method for requests.")
            return false
        }
        // Find the flow for the specific event class and try emitting
        val flow = getFlow(event::class)
        if (flow.tryEmit(DataWrapper(event, channel))) {
            if (removeSticky) {
                flow.resetReplayCache()
            }
            return true
        }
        return false
    }

    /**
     * Clears the sticky (replayed) events for a given event type.
     *
     * This function removes all previously emitted events from the replay cache of the
     * `MutableSharedFlow` associated with the specified event type [clazz]. Subsequent
     * subscribers will not receive any previously emitted events.  This effectively
     * "clears" the sticky behavior of events of this type.
     *
     * **Note:** This only affects the replay cache. Any currently active subscribers
     * that have already received the events will still have those events.  This only
     * prevents *new* subscribers from receiving the replayed events.
     *
     * @param T The type of the event. Must be a non-nullable type.
     * @param clazz The KClass representing the type of the event to clear.
     * @throws IllegalArgumentException if a flow for the given class is not registered.
     */
    fun <T : Any> clearStickyEvent(clazz: KClass<T>) {
        val flow = getFlow(clazz)
        flow.resetReplayCache()
    }

    /**
     * Posts an event asynchronously to the specified channel (or the default channel).
     *
     * This function is used to emit an event to a flow. It handles the emission and resets the replay cache of the flow after the emission.
     * It also includes a check to prevent direct posting of internal wrapper types like `RequestWrapper` and `ResponseWrapper`.
     *
     * @param event The event to post. Can be any object, except instances of `RequestWrapper` or `ResponseWrapper`.
     * @param channel The channel to post the event to. Defaults to [DefaultChannel].
     *
     * @throws IllegalArgumentException If the provided `event` is an instance of `RequestWrapper` or `ResponseWrapper`. In such case, it's recommended to use the `request()` method.
     *
     * @see getFlow
     * @see DefaultChannel
     * @see RequestWrapper
     * @see ResponseWrapper
     */
    suspend fun postAsync(
        event: Any,
        channel: String = DefaultChannel
    ) {
        postImpl(event, channel)
    }

    // endregion

    // region --- Request/Response Methods ---

    /**
     * Executes a synchronous request and returns the response.
     *
     * This function takes a [Request] object and an optional timeout duration. It
     * internally uses [requestAsync] to perform the request asynchronously, but
     * blocks the current thread until the result is available.
     *
     * @param R The type of the expected response.
     * @param request The [Request] object to be executed.
     * @param timeout The maximum duration to wait for the request to complete.
     *                Defaults to 5 seconds.
     * @return The response object of type [R].
     * @throws NoRequestHandlerException If no handler is registered for the given request type.
     * @throws RequestTimeoutException If the request times out before a response is received.
     * @throws RequestException If the request handler throws an error or null data.
     *
     * @sample
     * ```kotlin
     * data class MyRequest(val data: String) : Request<MyResponse>
     * data class MyResponse(val result: String)
     *      * // Example usage:
     * val myRequest = MyRequest("some data")
     * try {
     *     val response = request(myRequest, 10.seconds)
     *     println("Received response: ${response.result}")
     * } catch (e: NoRequestHandlerException) {
     *     println("Error: No handler found for MyRequest")
     * } catch (e: RequestTimeoutException) {
     *     println("Error: Request timed out")
     * } catch (e: RequestException) {
     *    println("Error: Request failed: ${e.cause}")
     * }
     * ```
     */
    inline fun <reified R : Any> request(
        request: Request<R>,
        channel: String = DefaultChannel,
        timeout: Duration = 5.seconds // Default 5 second timeout
    ): R {
        runBlocking {
            requestAsync(request, channel, timeout)
        }.also {
            return it
        }
    }

    /**
     * Sends a request asynchronously and waits for a response, with a timeout.
     *
     * This function handles the process of sending a request, storing the associated
     * deferred object to track the response, and waiting for the response with a specified timeout.
     * It also manages error handling for scenarios like no handler being registered, request timeouts,
     * and errors returned by the request handler.
     *
     * @param R The type of the response data.
     * @param request The request to send.
     * @param timeout The maximum duration to wait for a response. Defaults to 5 seconds.
     * @return The response data of type [R].
     * @throws NoRequestHandlerException If no handler is registered for the given request type.
     * @throws RequestTimeoutException If the request times out before a response is received.
     * @throws RequestException If the request handler throws an error or null data.
     *
     * @sample
     * ```kotlin
     * data class MyRequest(val data: String) : Request<MyResponse>
     * data class MyResponse(val result: String)
     *
     * // Example usage:
     * val myRequest = MyRequest("some data")
     *
     * scope.launch {
     *      try {
     *          val response = requestAsync(myRequest, 10.seconds)
     *          println("Received response: ${response.result}")
     *      } catch (e: NoRequestHandlerException) {
     *          println("Error: No handler found for MyRequest")
     *      } catch (e: RequestTimeoutException) {
     *          println("Error: Request timed out")
     *      } catch (e: RequestException) {
     *         println("Error: Request failed: ${e.cause}")
     *      }
     * }
     * ```
     */
    suspend inline fun <reified R : Any> requestAsync(
        request: Request<R>,
        channel: String = DefaultChannel,
        timeout: Duration = 5.seconds // Default 5 second timeout
    ): R {
        val requestType = request::class
        // Check if a handler exists *before* sending
        if (!requestHandlers.containsKey(requestType)) {
            throw NoRequestHandlerException("No handler registered for request type ${requestType.simpleName}")
        }

        val requestId = UUID.randomUUID().toString()
        val deferred = CompletableDeferred<ResponseWrapper<R>>()

        // Store the deferred before posting the request
        @Suppress("UNCHECKED_CAST") // Cast necessary due to heterogeneous map value type
        pendingRequests[requestId] = deferred as CompletableDeferred<ResponseWrapper<*>>

        val requestWrapper = RequestWrapper(requestId, channel, request)

        try {
            // Post the wrapped request to the flow corresponding to the *original* request payload type
            getRequestFlow(requestType).emit(requestWrapper) // Handler listens on this flow

            // Wait for the response with timeout
            val responseWrapper = withTimeoutOrNull(timeout) {
                deferred.await() // Suspend until the deferred is completed by the handler logic
            }

            if (responseWrapper == null) {
                throw RequestTimeoutException("Request timed out after $timeout for ${requestType.simpleName} (ID: $requestId)")
            }

            // Process the response
            if (responseWrapper.error != null) {
                throw RequestException(
                    "Request handler failed for ${requestType.simpleName} (ID: $requestId)",
                    responseWrapper.error
                )
            }

            // Should always have data if error is null, but check for safety
            return responseWrapper.data
                ?: throw RequestException("Handler for ${requestType.simpleName} returned null data (ID: $requestId)")

        } finally {
            // Always remove the pending request entry
            pendingRequests.remove(requestId)
        }
    }
    // endregion

    // region --- Registration Methods ---

    /**
     * Subscribes the given target object to receive events and handle requests.
     *
     * This function examines the target object for methods annotated with `@Subscribe` or `@RequestHandler`.
     * For each such method, it creates and registers a corresponding subscription or request handler.
     *
     * **Duplicate Subscriptions:**
     * If a target is already subscribed, a warning is logged, and the function returns without creating
     * duplicate subscriptions. It is recommended to call `unsubscribe` before subscribing the same target again.
     *
     * @param target The object to subscribe. This object will have its methods inspected for `@Subscribe` and `@RequestHandler` annotations.
     * @throws IllegalArgumentException if the target is null.
     * @throws IllegalStateException if `processSubscriber` or `processRequestHandler` fails to properly process the functions
     * @see Subscribe
     * @see RequestHandler
     * @see unsubscribe
     */
    fun subscribe(target: Any) {
        if (subscriptions.containsKey(target)) {
            logger?.w("Warning: Target ${target::class.simpleName} is already subscribed. Unsubscribe first to avoid duplicate handlers/subscriptions.")
            return
        }

        val targetClass = target::class
        val jobs = subscriptions.computeIfAbsent(target) { mutableListOf() }

        targetClass.memberFunctions.forEach { function ->
            // Handle @Subscribe annotations (Broadcast Events)
            function.findAnnotation<Subscribe>()?.let {
                processSubscriber(target, function, it, jobs)
            }

            // Handle @RequestHandler annotations (Request/Response)
            function.findAnnotation<RequestHandler>()?.let {
                processRequestHandler(target, function, it, jobs)
            }
        }
        // If no jobs were added for a new target, remove the empty list entry
        if (jobs.isEmpty() && subscriptions[target]?.isEmpty() == true) {
            subscriptions.remove(target)
        }
    }

    /**
     * Unsubscribes an object instance, cancelling all its active collectors
     * for both @Subscribe and @RequestHandler methods.
     * Also removes its handlers from the central registry.
     *
     * @param target The object instance to unsubscribe.
     */
    fun unsubscribe(target: Any) {
        // 1. Cancel collector jobs
        subscriptions.remove(target)?.let { jobs ->
            jobs.forEach { job ->
                if (job.isActive) {
                    job.cancel()
                }
            }
            logger?.i("Unsubscribed target ${target::class.simpleName}. Cancelled ${jobs.size} collector job(s).")
        }
            ?: logger?.w("Target ${target::class.simpleName} was not found in subscriptions map for job cancellation.")

        // 2. Remove registered request handlers associated with this target
        requestTargetTypeMappings.remove(target)?.forEach { requestType ->
            requestHandlers[requestType]?.remove(target)
            requestHandlers.computeIfPresent(requestType) { type, list -> if (list.isEmpty()) null else list }
        }
    }
    // endregion

    fun <T : Any> getRequestHandlerCount(requestType: KClass<T>): Int {
        return requestHandlers[requestType]?.size ?: 0
    }
}