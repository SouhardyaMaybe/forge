package com.forge.ide.core.backend

import com.forge.ide.core.backendapi.Event
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow

/**
 * Ordered, process-wide event stream with a monotonic sequence number.
 *
 * The sequence is what makes reconnect safe: a UI that reconnects asks for
 * "everything since seq N" instead of guessing what it missed. Events are
 * transient by design (no replay buffer) — durability comes from the
 * journals, not from this bus.
 */
class EventBus {
    private val seq = AtomicLong(0)
    private val _events = MutableSharedFlow<Event>(extraBufferCapacity = 512)

    val events = _events.asSharedFlow()

    suspend fun emit(name: String, params: kotlinx.serialization.json.JsonObject) {
        _events.emit(Event(seq.incrementAndGet(), name, params))
    }

    fun currentSeq(): Long = seq.get()
}
