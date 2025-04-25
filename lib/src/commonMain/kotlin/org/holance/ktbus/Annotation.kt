package org.holance.ktbus

import kotlin.reflect.KClass


/**
 * Indicates that a function is a subscriber to a message channel.
 *
 * This annotation is used to mark a function as a subscriber that will receive messages
 * published to a specific channel. It allows you to configure the execution scope (dispatcher),
 * the channel to listen to, and the factory used to create that channel.
 *
 * @property scope The dispatcher on which the subscriber function will be executed.
 *                 Defaults to [DispatcherTypes.Unconfined].
 *                 This determines the threading context for message processing.
 *                 For example:
 *                 - [DispatcherTypes.Unconfined]: Executes the subscriber on the thread that published the event.
 *                 - [DispatcherTypes.Main]: Executes the subscriber on the commonMain (UI) thread.
 *                 - [DispatcherTypes.IO]: Executes the subscriber on a thread optimized for IO operations.
 *                 - [DispatcherTypes.Default]: Executes the subscriber on a background thread.
 *                 - [DispatcherTypes.Immediate]: Executes the subscriber in the same call stack.
 * @property channel The name of the channel to subscribe to. Defaults to [DefaultChannelFactory.DEFAULT_CHANNEL].
 *                  Subscribers with the same channel name will receive the same messages.
 *                  It is common to define constants for your channels.
 *                  Example:
 *                   ```
 *                   companion object {
 *                       const val USER_EVENTS = "user_events"
 *                       const val PAYMENT_EVENTS = "payment_events"
 *                   }
 *                   ```
 * @property channelFactory The class of the [ChannelFactory] to use for creating the channel.
 *                         Defaults to [DefaultChannelFactory::class].
 *                         This allows for custom channel implementations for advanced use cases.
 *                         A custom [ChannelFactory] should implement the interface [ChannelFactory].
 *                         Example:
 *                          ```
 *                          class MyChannelFactory : ChannelFactory {
 *                              override fun createChannel(obj: Any) : String {
 *                                  // Use obj to determine dynamic channel at runtime.
 *                              }
 *                          }
 *                          ```
 * @see DispatcherTypes
 * @see DefaultChannelFactory
 * @see ChannelFactory
 */
@Retention(AnnotationRetention.RUNTIME)
@Target(AnnotationTarget.FUNCTION)
annotation class Subscribe(
    val scope: DispatcherTypes = DispatcherTypes.Unconfined,
    val channel: String = DefaultChannelFactory.DEFAULT_CHANNEL,
    val channelFactory: KClass<out ChannelFactory> = DefaultChannelFactory::class
)

@Retention(AnnotationRetention.RUNTIME)
@Target(AnnotationTarget.FUNCTION)
annotation class RequestHandler(
    val scope: DispatcherTypes = DispatcherTypes.Unconfined,
    val channel: String = DefaultChannelFactory.DEFAULT_CHANNEL,
    val channelFactory: KClass<out ChannelFactory> = DefaultChannelFactory::class
)