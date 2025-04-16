package org.lunci.ktbus

import kotlin.reflect.KClass


@Retention(AnnotationRetention.RUNTIME)
@Target(AnnotationTarget.FUNCTION)
annotation class Subscribe(
    val scope: DispatcherTypes = DispatcherTypes.Unconfined,
    val channelFactory: KClass<out ChannelFactory> = DefaultChannelFactory::class
)