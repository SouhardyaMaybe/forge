package com.forge.ide.core.backend

import com.forge.ide.core.backendapi.Events
import com.forge.ide.core.backendapi.GovernorStateParams
import com.forge.ide.core.jni.PtySession
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/** The operations that must never run concurrently on a 3 GB device. */
enum class HeavyOp { BUILD, AGENT, LSP_INDEX }

/**
 * Resource Governor: the single choke point that keeps a low-RAM phone alive.
 *
 * Two jobs:
 *  1. Serialise heavy work behind [withLease] — only one of BUILD / AGENT /
 *     LSP_INDEX may run at a time; everyone else queues.
 *  2. Sample memory and publish [state] so the UI can show honest reasons
 *     ("build paused: 180 MB available") instead of failing silently.
 *
 * Thermal detection is a documented TODO (poll /sys/class/thermal once the
 * device-lab results show which zones matter per vendor).
 */
class ResourceGovernor(private val scope: CoroutineScope) {

    private val _state = MutableStateFlow(
        GovernorStateParams(
            activeOp = null,
            memAvailableKb = -1,
            memTotalKb = -1,
            thermalThrottled = false,
            queued = emptyList(),
        )
    )
    val state: StateFlow<GovernorStateParams> = _state.asStateFlow()

    private val lease = Mutex()
    private val waiters = ArrayDeque<HeavyOp>()

    @Volatile
    private var active: HeavyOp? = null

    suspend fun <T> withLease(op: HeavyOp, block: suspend () -> T): T {
        waiters.addLast(op)
        publish()
        try {
            return lease.withLock {
                active = op
                publish()
                try {
                    block()
                } finally {
                    active = null
                    publish()
                }
            }
        } finally {
            waiters.remove(op)
            publish()
        }
    }

    fun activeOp(): HeavyOp? = active

    fun startSampling(eventBus: EventBus? = null, intervalMs: Long = 2_000) {
        scope.launch {
            while (isActive) {
                sample(eventBus)
                delay(intervalMs)
            }
        }
    }

    private suspend fun sample(eventBus: EventBus?) {
        val (totalKb, availableKb) = PtySession.memInfoKb()
        _state.update {
            it.copy(
                memAvailableKb = availableKb,
                memTotalKb = totalKb,
                activeOp = active?.name,
                queued = waiters.toList().map { op -> op.name },
            )
        }
        eventBus?.emit(Events.GOVERNOR_STATE, buildStateParams(_state.value))
    }

    private fun publish() {
        _state.update {
            it.copy(activeOp = active?.name, queued = waiters.toList().map { op -> op.name })
        }
    }

    companion object {
        /** Below this many available kB the UI should suggest freeing memory. */
        const val RED_LINE_KB = 250L * 1024

        const val AMBER_LINE_KB = 450L * 1024

        private fun buildStateParams(state: GovernorStateParams) =
            kotlinx.serialization.json.buildJsonObject {
                state.activeOp?.let { put("activeOp", it) }
                put("memAvailableKb", state.memAvailableKb)
                put("memTotalKb", state.memTotalKb)
                put("thermalThrottled", state.thermalThrottled)
            }
    }
}
