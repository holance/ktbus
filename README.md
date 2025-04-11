# ktbus
A simple EventBus implementation based on Kotlin SharedFlow and inspired by 
[Greenrobot EventBus](https://github.com/greenrobot/EventBus).

# Usage

Getting started:

```kotlin
data class Event1(val value: Int)
data class Event2(val value: Int)
data class Event3(val value: Int)

class SomeClass {
    val bus = KtBus.getDefault()

    fun setup() {
        bus.subscribe(this)
    }

    fun tearDown() {
        bus.unsubscribe(this)
    }

    @Subscribe
    fun onEvent1(event: Event1) {
        // Do something with the event
    }

    @Subscribe
    fun onEvent2(event: Event2) {
        // Do something with the event
    }

    @Subscribe
    fun onEvent3(event: Event3) {
        // Do something with the event
    }
}

val bus = KtBus.getDefault()

bus.post(Event1(1))
bus.post(Event2(2))
bus.post(Event3(3))
```