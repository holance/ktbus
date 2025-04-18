@file:Suppress("UNCHECKED_CAST")
@file:OptIn(ExperimentalCoroutinesApi::class)

package org.lunci.ktbus

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.yield
import java.lang.reflect.Method
import java.util.concurrent.ConcurrentHashMap
import kotlin.coroutines.resumeWithException
import kotlin.coroutines.suspendCoroutine
import kotlin.reflect.KClass
import kotlin.reflect.KFunction
import kotlin.reflect.KParameter
import kotlin.reflect.full.callSuspend
import kotlin.reflect.full.createInstance
import kotlin.reflect.full.declaredMembers
import kotlin.reflect.full.findAnnotation
import kotlin.reflect.jvm.javaMethod
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

typealias SubscriptionId = Int

enum class DispatcherTypes {
    Main,
    Default,
    IO,
    Unconfined
}

data class KtBusConfig(
    val bufferCapacity: Int = 10,
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

    // region Private classes
    private class IdGen {
        private val queue = ArrayDeque<Int>()
        fun getId(): Int? {
            return queue.removeFirstOrNull()
        }

        fun releaseId(id: Int) {
            queue.addLast(id)
        }

        @Suppress("unused")
        fun clear() {
            queue.clear()
        }

        val size: Int get() = queue.size
    }

    private interface IEventHandler {
        fun hasSubscribers(): Boolean
    }

    private class EventHandler<T : Any>(
        private val typeName: String,
        bufferCapacity: Int,
        onBufferOverflow: BufferOverflow
    ) : IEventHandler {
        private class Subscriptions<T : Any>(
            val events: SharedFlow<T>,
            val scope: CoroutineScope,
            val idGen: IdGen = IdGen(),
            val subJobs: MutableList<Job?> = mutableListOf()
        ) {
            private val mutex: Mutex = Mutex()

            suspend fun add(onEvent: (T) -> Unit, source: String): Int {
                val job = scope.launch {
                    events.collect { event: T ->
                        if (traceFunctionInvocation) {
                            logger?.d("Invoking event handler [$source]: ${event::class.simpleName}")
                        }
                        onEvent(event)
                    }
                }
                mutex.withLock {
                    val id = idGen.getId() ?: subJobs.size
                    if (id == subJobs.size) {
                        subJobs.add(job)
                    } else {
                        subJobs[id] = job
                    }
                    return id
                }
            }

            suspend fun remove(id: SubscriptionId): Boolean {
                mutex.withLock {
                    val job = subJobs[id]
                    subJobs[id] = null
                    idGen.releaseId(id)
                    job?.cancel()
                    if (idGen.size == subJobs.size) {
                        return true
                    }
                }
                return false
            }
        }

        private val subscribers =
            mutableMapOf<CoroutineScope, Subscriptions<T>>()
        private val _events: MutableSharedFlow<T> = MutableSharedFlow(
            replay = 1,
            extraBufferCapacity = bufferCapacity,
            onBufferOverflow = onBufferOverflow
        )
        val events get() = _events.asSharedFlow()
        private val mutex: Mutex = Mutex()

        init {
            logger?.d("Created event handler for class $typeName")
        }

        suspend fun post(event: T, removeStickyEvent: Boolean = true) {
            if (traceFunctionInvocation) {
                logger?.d("Posting event: ${event::class.simpleName}")
            }
            _events.emit(event)
            if (removeStickyEvent) {
                removeStickyEvent()
            }
        }

        suspend fun postSticky(event: T) {
            post(event, false)
        }

        fun removeStickyEvent() {
            _events.resetReplayCache()
        }

        fun subscribe(
            scope: CoroutineScope,
            onEvent: (T) -> Unit,
            source: String
        ): SubscriptionId {
            return runBlocking {
                mutex.withLock {
                    val subscriberSet =
                        subscribers.getOrPut(scope) { Subscriptions(events, scope) }
                    val id: SubscriptionId = subscriberSet.add(onEvent, source)
                    logger?.d("Subscribed event to id $id in class $typeName; scope $scope")
                    id
                }
            }
        }

        fun unsubscribe(scope: CoroutineScope, id: SubscriptionId) {
            logger?.d("Unsubscribing from id $id in class $typeName; scope $scope")
            runBlocking {
                mutex.withLock {
                    val subscriberSet = subscribers[scope] ?: return@withLock
                    if (subscriberSet.remove(id)) {
                        logger?.d("Unsubscribed scope $scope in class $typeName")
                        subscribers.remove(scope)
                    }
                }
            }
        }

        override fun hasSubscribers(): Boolean {
            return subscribers.isNotEmpty()
        }
    }

    private data class FunctionInfo(
        val id: SubscriptionId,
        val channel: String,
        val scope: CoroutineScope,
        val clazz: KClass<*>
    )
    //endregion

    // region Private properties
    private val handlers = ConcurrentHashMap<KClass<*>, ConcurrentHashMap<String, IEventHandler>>()
    private val objectHandlerMapping =
        ConcurrentHashMap<Any, MutableList<FunctionInfo>>()
    //endregion

    // region Private functions
    private fun <T : Any> getOrCreateHandler(clazz: KClass<T>, channel: String): EventHandler<T> {
        val chHandlers = handlers.getOrPut(clazz) {
            ConcurrentHashMap<String, IEventHandler>()
        }
        return chHandlers.getOrPut(channel) {
            EventHandler<T>(
                clazz.simpleName.toString(),
                config.bufferCapacity,
                config.onBufferOverflow
            )
        } as EventHandler<T>
    }

    private fun <T : Any> getHandler(clazz: KClass<T>, channel: String): EventHandler<T>? {
        val chHandlers = handlers.getOrDefault(clazz, null) ?: return null
        return chHandlers.getOrDefault(channel, null) as EventHandler<T>
    }

    private fun <T : Any> subscribe(
        clazz: KClass<T>, obj: Any, channel: String, onEvent: (T) -> Unit,
        scope: CoroutineScope, source: String
    ) {
        val handler = getOrCreateHandler(clazz, channel)
        val id = handler.subscribe(scope, onEvent, source)
        objectHandlerMapping[obj]?.add(FunctionInfo(id, channel, scope, clazz))
    }

    private fun <T : Any> unsubscribe(
        clazz: KClass<T>,
        channel: String,
        id: SubscriptionId,
        scope: CoroutineScope
    ) {
        val handler = getHandler(clazz, channel)
        handler?.unsubscribe(scope, id)
    }

    // endregion

    // region Public functions
    fun <T : Any> post(
        event: T,
        channel: String = DefaultChannelFactory.DEFAULT_CHANNEL,
        scope: CoroutineScope = unconfinedScope
    ) {
        runBlocking { postAsync(event, channel) }
    }

    suspend fun <T : Any> postAsync(
        event: T,
        channel: String = DefaultChannelFactory.DEFAULT_CHANNEL
    ) {
        val clazz = event::class
        val handler = getHandler(clazz, channel) ?: return
        (handler as EventHandler<T>).post(event)
    }

    fun <T : Any> postSticky(
        event: T,
        channel: String = DefaultChannelFactory.DEFAULT_CHANNEL,
        scope: CoroutineScope = unconfinedScope
    ) {
        runBlocking { postStickyAsync(event, channel) }
    }

    suspend fun <T : Any> postStickyAsync(
        event: T,
        channel: String = DefaultChannelFactory.DEFAULT_CHANNEL
    ) {
        val clazz = event::class
        val handler = getOrCreateHandler(clazz, channel) as EventHandler<T>
        handler.postSticky(event)
    }

    fun <T : Any, E : Any> request(
        event: T,
        onResult: (RequestResult<E>) -> Unit,
        channel: String = DefaultChannelFactory.DEFAULT_CHANNEL,
        timeout: Duration = 5.seconds,
        scope: CoroutineScope = unconfinedScope
    ) {
        runBlocking {
            requestAsync(event, onResult, channel, timeout)
        }
    }

    suspend fun <T : Any, E : Any> requestAsync(
        event: T,
        onResult: (RequestResult<E>) -> Unit,
        channel: String = DefaultChannelFactory.DEFAULT_CHANNEL,
        timeout: Duration = 5.seconds
    ) {
        val requestEvent = RequestEvent<T, E>(data = event, bus = this, channel = channel)
        val responseClass: KClass<ResponseEvent<E>> =
            ResponseEvent::class as KClass<ResponseEvent<E>>
        val handler =
            getOrCreateHandler<ResponseEvent<E>>(responseClass, channel)
        val responseListenerJob: Deferred<ResponseEvent<E>> =
            unconfinedScope.async {
                handler.events.filter {
                    it.correlationId == requestEvent.requestId
                }.first()
            }

        yield()
        post(requestEvent, channel)

        val resultEvent = withTimeoutOrNull(timeout) {
            responseListenerJob.await()
        }
        if (resultEvent?.data != null) {
            onResult(RequestResult.Success(resultEvent.data))
        } else if (resultEvent?.error != null) {
            onResult(RequestResult.Error(resultEvent.error))
        } else {
            onResult(RequestResult.Timeout)
        }
    }

    fun <T : Any> removeStickyEvent(
        clazz: KClass<T>,
        channel: String = DefaultChannelFactory.DEFAULT_CHANNEL
    ) {
        val handler = getHandler(clazz, channel)
        handler?.removeStickyEvent()
    }

    /**
     * Subscribes an object to receive events.
     *
     * This function registers an object (typically a class representing a subscriber)
     * to receive events. It scans the object's methods for those annotated with `@Subscribe`
     * and registers them to handle events of the specified types on the defined channels.
     *
     * **Requirements:**
     *  - The provided `obj` must be a `KClass<*>` (Kotlin class object).
     *  - Methods annotated with `@Subscribe` must have a valid signature (one parameter).
     *
     * **Behavior:**
     *  - It first checks if the given class object is already registered. If it is, a warning
     *    is logged, and the function returns without doing anything.
     *  - If the class is not already registered, it iterates through all the declared methods
     *    of the class.
     *  - For each method, it checks if it's annotated with `@Subscribe`.
     *  - If a method is annotated, it extracts information from the annotation:
     *      - `channelFactory`: Specifies the factory class used to create the channel for the event.
     *      - `scope`: Specifies the coroutine dispatcher on which the method should be executed.
     *  - It creates a channel instance using the provided factory class.
     *  - It validates the method signature to ensure it accepts exactly one argument.
     *  - It determines the appropriate coroutine scope based on the `scope` specified in the annotation.
     *  - It then registers the method to receive events of the specified type on the created channel.
     *
     * **Logging:**
     *  - A warning is logged if an object is already registered.
     *
     * **Error Handling:**
     *  - `IllegalArgumentException` is thrown if the provided object is not a `KClass<*>`.
     *  - Any exception thrown by the subscribed method will be propagated by reflection.
     *
     * @param obj The class object (`KClass<*>`) to subscribe.
     * @throws IllegalArgumentException if the provided object is not a `KClass<*>` or if the method contains more than one parameter.
     */
    fun subscribe(obj: Any) {
        require(obj::class != KClass::class) { "Only class instances are allowed as arguments." }
        if (objectHandlerMapping.containsKey(obj)) {
            logger?.w("Object [${obj::class.simpleName}] is already registered in EventBus.")
            return
        }
        objectHandlerMapping[obj] = mutableListOf()
        val clazz = obj::class
        clazz.declaredMembers.forEach { method ->
            val annotation = method.findAnnotation<Subscribe>() ?: return@forEach
            var channel = annotation.channel
            val factoryClass = annotation.channelFactory
            if (factoryClass != DefaultChannelFactory::class) {
                val factory = factoryClass.createInstance()
                channel = factory.createChannel(obj)
            }
            val parameters = method.parameters.filter { it.kind == KParameter.Kind.VALUE }
            if (parameters.size != 1) {
                throw IllegalArgumentException("Method ${method.name} must have exactly one parameter.")
            }
            val classifier = parameters[0].type.classifier
            if (classifier !is KClass<*>) {
                throw IllegalArgumentException("Method ${method.name} parameter must be a class.")
            }
            val argType: KClass<*> = classifier
            val scope = when (annotation.scope) {
                DispatcherTypes.Main -> mainScope
                DispatcherTypes.Default -> defaultScope
                DispatcherTypes.IO -> ioScope
                DispatcherTypes.Unconfined -> unconfinedScope
            }
            val source = "${clazz.simpleName}.${method.name}"
            subscribe(
                argType,
                obj,
                channel,
                { event ->
                    if (method.isSuspend) {
                        scope.launch {
                            try {
                                if (method is KFunction<*> && method.javaMethod != null) {
                                    invokeSuspendFunction(method.javaMethod!!, obj, event)
                                } else {
                                    method.callSuspend(obj, event)
                                }
                            } catch (e: Throwable) {
                                logger?.e("Exception in event handler [$source]: $e")
                            }
                        }
                    } else {
                        try {
                            if (method is KFunction<*> && method.javaMethod != null) {
                                method.javaMethod?.invoke(obj, event)
                            } else {
                                method.call(obj, event)
                            }
                        } catch (e: Throwable) {
                            logger?.e("Exception in event handler [$source]: $e")
                        }
                    }
                },
                scope,
                source
            )
        }
    }

    suspend fun invokeSuspendFunction(
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

    /**
     * Unsubscribes all handlers associated with a given class object.
     *
     * This function removes all registered handlers (functions) that were subscribed
     * using a specific class as the target.
     *
     * @param obj The KClass object representing the class whose associated handlers should be unsubscribed.
     * @throws IllegalArgumentException if the provided argument is not a KClass object.
     */
    fun unsubscribe(obj: Any) {
        require(obj::class != KClass::class) { "Only class instances are allowed as arguments." }
        val functions = objectHandlerMapping.remove(obj) ?: return
        functions.forEach { function ->
            unsubscribe(function.clazz, function.channel, function.id, function.scope)
        }
    }
    // endregion
}