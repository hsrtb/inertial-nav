package com.nibawr.nav

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * Minimal snapshot of the last known GNSS fix, for UI display only.
 */
data class LastGps(
    val ageMs: Long,
    val lat: Double,
    val lon: Double,
    val speedMps: Float?,
    val hAccM: Float?,
)

/**
 * Snapshot of [RecordingService] state, published at most ~2x/second.
 */
data class Status(
    val isRecording: Boolean = false,
    val startElapsedNs: Long = 0L,
    val eventCounts: Map<String, Long> = emptyMap(),
    val hzByTag: Map<String, Double> = emptyMap(),
    val lastGps: LastGps? = null,
    val fileName: String = "",
    val bytesWritten: Long = 0L,
)

/**
 * Simple singleton bridge between [RecordingService] (producer) and the
 * Compose UI (consumer). No binder needed: the service and the activity
 * live in the same process, so a plain in-memory StateFlow suffices.
 */
object RecorderState {
    private val _status = MutableStateFlow(Status())
    val status: StateFlow<Status> = _status

    fun update(newStatus: Status) {
        _status.value = newStatus
    }

    fun reset() {
        _status.value = Status()
    }
}
