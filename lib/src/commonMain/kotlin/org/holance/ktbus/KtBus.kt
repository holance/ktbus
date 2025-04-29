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
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import kotlin.reflect.KClass
import kotlin.reflect.KFunction
import kotlin.reflect.KParameter
import kotlin.reflect.full.callSuspend
import kotlin.reflect.full.createInstance
import kotlin.reflect.full.findAnnotation
import kotlin.reflect.full.memberFunctions
import kotlin.reflect.jvm.isAccessible
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
        const val EVENT_TYPE_STR = "Event Type"
        const val REQUEST_TYPE_STR = "Request Type"
        const val RESPONSE_TYPE_STR = "Response Type"
        const val FUNCTION_PARAMETER_0_STR = "Function Parameter 0"


        fun getDefault(): KtBus {
            return instance
        }

        fun setLogger(logger: Logger) {
            this.logger = logger
        }

        fun resetLogger() {
            logger = null
        }

        fun KClass<*>.isGeneric(): Boolean {
            return this.typeParameters.isNotEmpty()
        }

        fun KClass<*>.isAny(): Boolean {
            return this == Any::class
        }

        fun KClass<*>.isUnit(): Boolean {
            return this == Unit::class
        }

        fun KClass<*>.isInternalTypes(): Boolean {
            return this == DataWrapper::class || this == RequestWrapper::class || this == ResponseWrapper::class
        }

        fun KClass<*>.validate(name: String) {
            if (isGeneric() || isAny() || isUnit()
            ) {
                throw IllegalArgumentException("[$name]: Invalid type of [$simpleName]. Type cannot be Unit or Any or a generic type.")
            }
            if (isInternalTypes()) {
                throw IllegalArgumentException("[$name]: Invalid type of [$simpleName]. Type cannot be KtBus internal wrapper types.")
            }
        }
    }

    data class DataWrapper<T : Any>(val data: T, val channel: String)

    /** Internal wrapper for requests to include a unique ID */
    data class RequestWrapper<T : Any, R : Any>(
        val id: String,
        val channel: String,
        val payload: T, // The actual user request object
        val responseType: KClass<R>
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
        ConcurrentHashMap<KClass<*>, ConcurrentHashMap<KClass<*>, MutableSharedFlow<RequestWrapper<*, *>>>>()

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

    fun <T : Any, R : Any> getRequestFlow(
        requestType: KClass<T>,
        responseType: KClass<R>
    ): MutableSharedFlow<RequestWrapper<*, *>> {
        return requestFlows.computeIfAbsent(requestType) {
            ConcurrentHashMap<KClass<*>, MutableSharedFlow<RequestWrapper<*, *>>>()
        }.computeIfAbsent(responseType) {
            MutableSharedFlow<RequestWrapper<*, *>>(
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
        event::class.validate(EVENT_TYPE_STR)
        val flow = getFlow(event::class)
        flow.emit(DataWrapper(event, channel))
        if (removeSticky) {
            flow.resetReplayCache()
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
            throw IllegalArgumentException("Invalid number of parameters for @Subscribe method. Expected 2, got ${function.parameters.size}")
        }
        val parameters = function.parameters.filter { it.kind == KParameter.Kind.VALUE }
        if (parameters.isEmpty()) {
            throw IllegalArgumentException("Invalid @Subscribe method. No event parameter found.")
        }

        val eventTypeParam = parameters[0]
        val eventType = eventTypeParam.type.jvmErasure
        eventType.validate(FUNCTION_PARAMETER_0_STR)
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
                        function.isAccessible = true
                        if (function.isSuspend) {
                            function.callSuspend(target, event.data)
                        } else {
                            function.call(target, event.data)
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
            throw IllegalArgumentException("Invalid number of parameters for @RequestHandler method. Expected 2, got ${function.parameters.size}")
        }
        val parameters = function.parameters.filter { it.kind == KParameter.Kind.VALUE }
        if (parameters.isEmpty()) {
            throw IllegalArgumentException("Invalid @RequestHandler method. No event parameter found.")
        }

        val requestTypeParam = parameters[0]
        val requestType =
            requestTypeParam.type.jvmErasure // KClass of the request (e.g., MyRequest::class)
        val returnType =
            function.returnType.jvmErasure // KClass of the response (e.g., MyResponse::class)
        requestType.validate(REQUEST_TYPE_STR)
        returnType.validate(REQUEST_TYPE_STR)

        val requestFlow =
            getRequestFlow(requestType, returnType) // Flow of RequestWrapper<R>
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
                if (requestType.isInstance(requestWrapperUntyped.payload)
                    && requestWrapperUntyped.responseType == returnType
                ) {
                    if (requestWrapperUntyped.channel != channel) {
                        return@collect
                    }
                    val requestWrapper =
                        requestWrapperUntyped as RequestWrapper<Any, Any> // Safe cast after check
                    val requestId = requestWrapper.id

                    // Find the deferred associated with this request ID
                    val deferred = pendingRequests[requestId]
                    if (deferred == null) {
                        logger?.w("Warning: Received request ${requestType.simpleName} (ID: $requestId) but no pending request found. Maybe timed out?")
                        return@collect // Ignore if no longer pending
                    }

                    var responseData: Any? = null
                    var responseError: Throwable? = null
                    function.isAccessible = true
                    try {
                        // Call the actual handler function (suspend or regular)
                        responseData = if (function.isSuspend) {
                            function.callSuspend(target, requestWrapper.payload)
                        } else {
                            function.call(target, requestWrapper.payload)
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
     * @throws IllegalArgumentException if the event is an internal wrapper type (RequestWrapper or ResponseWrapper).
     * @throws IllegalArgumentException if the event is a generic type.
     *
     * - **Sticky Event Management:** Post will remove sticky event automatically.
     *
     * @sample org.holance.samples.ktbus.PostSamples
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
     *
     * @throws IllegalArgumentException if the event is an internal wrapper type (RequestWrapper or ResponseWrapper).
     * @throws IllegalArgumentException if the event is a generic type.
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
     * @throws IllegalArgumentException if the event is an internal wrapper type (RequestWrapper or ResponseWrapper).
     * @throws IllegalArgumentException if the event is a generic type.
     */
    fun tryPost(
        event: Any,
        channel: String = DefaultChannel,
        removeSticky: Boolean = true
    ): Boolean {
        // Ensure we don't accidentally post internal wrappers
        event::class.validate(EVENT_TYPE_STR)
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
     * @throws IllegalArgumentException if the event is an internal wrapper type (RequestWrapper or ResponseWrapper).
     * @throws IllegalArgumentException if the event is a generic type.
     *
     * @sample org.holance.samples.ktbus.PostSamples
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
     * Sends a request synchronously and waits for a response.
     *
     * This function sends a request object to a designated channel and waits until a response
     * is received or a timeout occurs. It utilizes a `CompletableDeferred` to handle the asynchronous
     * response and a `Flow` for communication between the request sender and receiver (handler).
     *
     * @param T The type of the request payload.
     * @param R The type of the expected response payload.
     * @param request The request object to be sent.
     * @param channel The name of the channel to send the request to. Defaults to [DefaultChannel].
     * @param timeout The maximum duration to wait for a response. Defaults to 5 seconds.
     * @return The response object of type [R] if successful.
     * @throws NoRequestHandlerException If no handler is registered for the given request type [T].
     * @throws RequestTimeoutException If the request times out before a response is received.
     * @throws RequestException If the request handler encounters an error or returns null data.
     * @throws IllegalArgumentException if the event is  a unit or any type.
     * @throws IllegalArgumentException if the event is a generic type.
     * @sample org.holance.samples.ktbus.RequestSample.sendRequest
     */
    inline fun <reified T : Any, reified R : Any> request(
        request: T,
        channel: String = DefaultChannel,
        timeout: Duration = 5.seconds // Default 5 second timeout
    ): R {
        runBlocking {
            requestAsync<T, R>(request, channel, timeout)
        }.also {
            return it
        }
    }

    /**
     * Sends a request asynchronously and waits for a response.
     *
     * This function sends a request object to a designated channel and suspends until a response
     * is received or a timeout occurs. It utilizes a `CompletableDeferred` to handle the asynchronous
     * response and a `Flow` for communication between the request sender and receiver (handler).
     *
     * @param T The type of the request payload.
     * @param R The type of the expected response payload.
     * @param request The request object to be sent.
     * @param channel The name of the channel to send the request to. Defaults to [DefaultChannel].
     * @param timeout The maximum duration to wait for a response. Defaults to 5 seconds.
     * @return The response object of type [R] if successful.
     * @throws NoRequestHandlerException If no handler is registered for the given request type [T].
     * @throws RequestTimeoutException If the request times out before a response is received.
     * @throws RequestException If the request handler encounters an error or returns null data.
     * @throws IllegalArgumentException if the request or response type is a unit or any type.
     * @throws IllegalArgumentException if the request or response type is a generic type.
     *
     * @sample org.holance.samples.ktbus.RequestSample.sendRequestAsync
     *
     */
    suspend inline fun <reified T : Any, reified R : Any> requestAsync(
        request: T,
        channel: String = DefaultChannel,
        timeout: Duration = 5.seconds // Default 5 second timeout
    ): R {
        val requestType = request::class
        val returnType = R::class
        requestType.validate(REQUEST_TYPE_STR)
        returnType.validate(RESPONSE_TYPE_STR)

        // Check if a handler exists *before* sending
        if (!requestHandlers.containsKey(requestType)) {
            throw NoRequestHandlerException("No handler registered for request type ${requestType.simpleName}")
        }

        val requestId = UUID.randomUUID().toString()
        val deferred = CompletableDeferred<ResponseWrapper<R>>()

        // Store the deferred before posting the request
        @Suppress("UNCHECKED_CAST") // Cast necessary due to heterogeneous map value type
        pendingRequests[requestId] = deferred as CompletableDeferred<ResponseWrapper<*>>

        val requestWrapper = RequestWrapper(requestId, channel, request, returnType)

        try {
            // Post the wrapped request to the flow corresponding to the *original* request payload type
            getRequestFlow(
                requestType,
                returnType
            ).emit(requestWrapper) // Handler listens on this flow

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
     * @throws IllegalArgumentException if the subscription handler or request handler contains invalid parameter types or return types.
     * @throws IllegalStateException if `processSubscriber` or `processRequestHandler` fails to properly process the functions
     * @see Subscribe
     * @see RequestHandler
     * @see unsubscribe
     * @sample org.holance.samples.ktbus.SubscribeSample
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
     * @sample org.holance.samples.ktbus.SubscribeSample
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