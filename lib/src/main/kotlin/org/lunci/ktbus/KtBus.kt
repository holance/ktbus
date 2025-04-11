@file:Suppress("UNCHECKED_CAST")
@file:OptIn(ExperimentalCoroutinesApi::class)

package org.lunci.ktbus

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.lang.reflect.Method
import kotlin.reflect.KClass
import kotlin.reflect.KFunction
import kotlin.reflect.full.createInstance
import kotlin.reflect.full.isSubclassOf
import kotlin.reflect.jvm.kotlinFunction

interface Logger {
    fun debug(message: String)
    fun error(message: String)
    fun info(message: String)
    fun warning(message: String)
    fun verbose(message: String)

    @Suppress("unused")
    fun d(message: String) {
        debug(message)
    }

    @Suppress("unused")
    fun e(message: String) {
        error(message)
    }

    @Suppress("unused")
    fun i(message: String) {
        info(message)
    }

    @Suppress("unused")
    fun w(message: String) {
        warning(message)
    }

    @Suppress("unused")
    fun v(message: String) {
        verbose(message)
    }
}

typealias SubscriptionId = Int

enum class DispatcherTypes {
    Main,
    Default,
    IO,
    Unconfined
}

interface ChannelFactory {
    fun createChannel(obj: Any): String
}

// Default implementation
class DefaultChannelFactory : ChannelFactory {
    companion object {
        const val DEFAULT_CHANNEL = ""
    }

    override fun createChannel(obj: Any): String = DEFAULT_CHANNEL
}

@Retention(AnnotationRetention.RUNTIME)
@Target(AnnotationTarget.FUNCTION)
annotation class Subscribe(
    val scope: DispatcherTypes = DispatcherTypes.Unconfined,
    val channelFactory: KClass<out ChannelFactory> = DefaultChannelFactory::class
)

@Suppress("unused")
class KtBus {
    companion object {
        private val mainScope = CoroutineScope(Dispatchers.Main)
        private val defaultScope = CoroutineScope(Dispatchers.Default)
        private val ioScope = CoroutineScope(Dispatchers.IO)
        private val unconfinedScope = CoroutineScope(Dispatchers.Unconfined)
        private var logger: Logger = object : Logger {
            override fun debug(message: String) {
            }

            override fun error(message: String) {
            }

            override fun info(message: String) {
            }

            override fun warning(message: String) {
            }

            override fun verbose(message: String) {
            }
        }

        @Suppress("unused")
        fun setLogger(logger: Logger) {
            this.logger = logger
        }

        private val eventBus = KtBus()

        @Suppress("unused")
        fun getDefault(): KtBus {
            return eventBus
        }
    }

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

    private class EventHandler<T>(
        private val typeName: String,
        bufferCapacity: Int = 1,
        onBufferOverflow: BufferOverflow = BufferOverflow.DROP_OLDEST
    ) : IEventHandler {

        private class Subscriptions<T>(
            val events: SharedFlow<T>,
            val scope: CoroutineScope,
            val idGen: IdGen = IdGen(),
            val subJobs: MutableList<Job?> = mutableListOf()
        ) {
            private val mutex: Mutex = Mutex()

            suspend fun add(onEvent: (T) -> Unit): Int {
                val job = scope.launch {
                    events.collect { event ->
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
        private val _events = MutableSharedFlow<T>(
            replay = 1,
            extraBufferCapacity = bufferCapacity,
            onBufferOverflow = onBufferOverflow
        )
        private val events get() = _events.asSharedFlow()
        private val mutex: Mutex = Mutex()
        private val subscriptionScope = unconfinedScope

        init {
            logger.debug("Created event handler for class $typeName")
        }

        suspend fun post(event: T, removeStickyEvent: Boolean = true) {
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
            onEvent: (T) -> Unit
        ): SubscriptionId {
            val deferred = subscriptionScope.async {
                mutex.withLock {
                    val subscriberSet =
                        subscribers.getOrPut(scope) { Subscriptions(events, scope) }
                    return@withLock subscriberSet.add(onEvent)
                }
            }
            val id: SubscriptionId = deferred.getCompleted()
            logger.debug("Subscribed event to id $id in class $typeName; scope $scope")
            return id
        }

        fun unsubscribe(scope: CoroutineScope, id: SubscriptionId) {
            logger.debug("Unsubscribing from id $id in class $typeName; scope $scope")
            val deferred = subscriptionScope.async {
                mutex.withLock {
                    val subscriberSet = subscribers[scope] ?: return@withLock
                    if (subscriberSet.remove(id)) {
                        logger.debug("Unsubscribed scope $scope in class $typeName")
                        subscribers.remove(scope)
                    }
                }
            }
            deferred.getCompleted()
        }

        override fun hasSubscribers(): Boolean {
            return subscribers.isNotEmpty()
        }
    }

    private data class FunctionInfo(
        val id: SubscriptionId,
        val channel: String,
        val scope: CoroutineScope,
        val clazz: Class<*>
    )

    private val handlers = mutableMapOf<Class<*>, MutableMap<String, IEventHandler>>()
    private val objectHandlerMapping =
        mutableMapOf<Any, MutableList<FunctionInfo>>()
    private val postScope = unconfinedScope

    fun <T : Any> post(event: T, channel: String = DefaultChannelFactory.DEFAULT_CHANNEL) {
        val clazz = event::class.java
        val handler = getHandler(clazz, channel) ?: return
        postScope.launch {
            (handler as EventHandler<T>).post(event)
        }
    }

    fun <T : Any> postSticky(event: T, channel: String = DefaultChannelFactory.DEFAULT_CHANNEL) {
        val clazz = event::class.java
        val handler = createOrGetHandler(clazz, channel) as EventHandler<T>
        postScope.launch {
            handler.postSticky(event)
        }
    }

    fun <T : Any> removeStickyEvent(
        clazz: Class<T>,
        channel: String = DefaultChannelFactory.DEFAULT_CHANNEL
    ) {
        val handler = getHandler(clazz, channel)
        handler?.removeStickyEvent()
    }

    private fun checkMethodSignature(method: Method): Class<*>? {
        // Check if the method is a Kotlin function
        val kFunction = method.kotlinFunction ?: return null

        // Check for the correct number of parameters
        if (method.parameterTypes.size != 1) {
            return null
        }

        // Check if the return type is Unit
        if (kFunction.returnType.classifier != Unit::class) {
            return null
        }

        // Get the type of the Any parameter
        return method.parameterTypes[0]
    }

    fun subscribe(obj: Any) {
        if (obj::class.isSubclassOf(KFunction::class)) {
            throw IllegalArgumentException("Functions are not allowed as arguments.")
        }
        if (objectHandlerMapping.contains(obj)) {
            logger.w("Object [${obj.javaClass.simpleName}] is already registered in EventBus.")
            return
        }
        objectHandlerMapping[obj] = mutableListOf()
        val clazz = obj::class.java
        clazz.declaredMethods.forEach { method ->
            val annotation = method.getAnnotation(Subscribe::class.java) ?: return@forEach
            val factoryClass = annotation.channelFactory
            val factory = factoryClass.createInstance()
            val channel = factory.createChannel(obj)
            val returnType = checkMethodSignature(method) ?: return@forEach
            val scope = when (annotation.scope) {
                DispatcherTypes.Main -> mainScope
                DispatcherTypes.Default -> defaultScope
                DispatcherTypes.IO -> ioScope
                DispatcherTypes.Unconfined -> unconfinedScope
            }
            subscribe(returnType, obj, channel, { event -> method.invoke(obj, event) }, scope)
        }
    }

    private fun <T : Any> subscribe(
        clazz: Class<T>, obj: Any, channel: String, onEvent: (T) -> Unit,
        scope: CoroutineScope,
    ) {
        val handler = createOrGetHandler(clazz, channel)
        val id = handler.subscribe(scope, onEvent)
        objectHandlerMapping[obj]?.add(FunctionInfo(id, channel, scope, clazz))
    }

    private fun <T : Any> createOrGetHandler(clazz: Class<T>, channel: String): EventHandler<T> {
        val chHandlers = handlers.getOrPut(clazz) {
            mutableMapOf<String, IEventHandler>()
        }
        return chHandlers.getOrPut(channel) {
            EventHandler<T>(clazz.simpleName)
        } as EventHandler<T>
    }

    private fun <T : Any> getHandler(clazz: Class<T>, channel: String): EventHandler<T>? {
        val chHandlers = handlers[clazz] ?: return null
        return chHandlers[channel] as EventHandler<T>
    }

    fun unsubscribe(obj: Any) {
        if (obj::class.isSubclassOf(KFunction::class)) {
            throw IllegalArgumentException("Functions are not allowed as arguments.")
        }
        if (!objectHandlerMapping.contains(obj)) {
            return
        }
        val functions = objectHandlerMapping[obj] ?: return
        functions.forEach { function ->
            unsubscribe(function.clazz, function.channel, function.id, function.scope)
        }
        objectHandlerMapping.remove(obj)
    }

    private fun <T : Any> unsubscribe(
        clazz: Class<T>,
        channel: String,
        id: SubscriptionId,
        scope: CoroutineScope
    ) {
        val handler = getHandler(clazz, channel)
        handler?.unsubscribe(scope, id)
    }
}