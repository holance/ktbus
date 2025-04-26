package org.holance.ktbus

import java.util.UUID

open class BaseEvent(id: String = UUID.randomUUID().toString())
data class Event1(val value: Int) : BaseEvent()
data class Event2(val value: Int) : BaseEvent()
data class Event3(val value: Int) : BaseEvent()
