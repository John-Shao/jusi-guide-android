package com.jusiai.guidedog

import android.graphics.Bitmap
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update

/** Snapshot of the loop, shown on screen (the spoken guidance comes from audio). */
data class GuideStatus(
    val running: Boolean = false,
    val text: String = "",       // latest guidance sentence
    val mad: Int = -1,           // last frame-diff value
    val lastCycleMs: Long = 0,   // last full cycle wall time
    val vlmMs: Int = -1,         // relay-reported VLM time
    val error: String? = null,
)

/** Shared, observable state: the service writes it, the UI reads it. */
class GuideState {
    val status = MutableStateFlow(GuideStatus())
    val preview = MutableStateFlow<Bitmap?>(null)
    fun update(block: (GuideStatus) -> GuideStatus) = status.update(block)
}
