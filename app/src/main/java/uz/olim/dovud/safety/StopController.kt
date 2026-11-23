package uz.olim.dovud.safety

import java.util.concurrent.atomic.AtomicBoolean

/** QULFLANGAN QISM. "Dovud, to'xta" — istalgan amalni darhol to'xtatadi. */
object StopController {
    private val flag = AtomicBoolean(false)
    private val listeners = mutableListOf<() -> Unit>()

    val stopped: Boolean get() = flag.get()

    fun requestStop() {
        flag.set(true)
        synchronized(listeners) { listeners.toList() }.forEach { runCatching { it() } }
    }

    fun reset() = flag.set(false)

    fun onStop(l: () -> Unit) { synchronized(listeners) { listeners.add(l) } }
}

class StoppedException : Exception("To'xtatildi")
