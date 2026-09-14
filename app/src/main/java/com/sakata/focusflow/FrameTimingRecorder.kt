package com.sakata.focusflow

import android.os.Handler
import android.os.HandlerThread
import android.os.SystemClock
import android.util.Log
import android.view.FrameMetrics
import android.view.Window
import java.util.Locale

/**
 * Release-safe, local-only frame timing windows for 8.3 startup verification.
 * Results are written to Logcat and are never persisted or uploaded.
 */
@Suppress("DEPRECATION")
internal object FrameTimingRecorder {
    private const val TAG = "FocusFlowFrames"
    private const val STARTUP_WINDOW_MS = 1_200L
    private const val INTERACTION_WINDOW_MS = 700L
    private const val NO_FRAME_TIMEOUT_MS = 8_000L

    private data class Sample(
        val id: Long,
        val label: String,
        val windowMs: Long,
        val durationsNanos: MutableList<Long> = mutableListOf(),
        var droppedCallbacks: Int = 0,
        var finishScheduled: Boolean = false
    )

    private val lock = Any()
    private val samples = linkedMapOf<Long, Sample>()
    private var installedWindow: Window? = null
    private var timingThread: HandlerThread? = null
    private var timingHandler: Handler? = null
    private var frameBudgetNanos: Long = 1_000_000_000L / 60L
    private var nextSampleId = 1L
    private var snapshotStartedAt = 0L
    private var tabSwitchCount = 0
    private var expansionCount = 0

    private val listener = Window.OnFrameMetricsAvailableListener { _, metrics, droppedSinceLastInvocation ->
        val duration = metrics.getMetric(FrameMetrics.TOTAL_DURATION)
        if (duration <= 0L) return@OnFrameMetricsAvailableListener
        val newlyStarted = mutableListOf<Pair<Long, Long>>()
        synchronized(lock) {
            samples.values.forEach { sample ->
                sample.durationsNanos += duration
                sample.droppedCallbacks += droppedSinceLastInvocation
                if (!sample.finishScheduled) {
                    sample.finishScheduled = true
                    newlyStarted += sample.id to sample.windowMs
                }
            }
        }
        newlyStarted.forEach { (id, delayMs) ->
            timingHandler?.postDelayed({ finish(id, timedOut = false) }, delayMs)
        }
    }

    fun install(window: Window) {
        if (installedWindow === window) return
        if (installedWindow != null) uninstall(installedWindow!!)
        val thread = HandlerThread("focusflow-frame-timing").apply { start() }
        timingThread = thread
        timingHandler = Handler(thread.looper)
        installedWindow = window
        val refreshRate = window.decorView.display?.refreshRate?.takeIf { it > 1f } ?: 60f
        frameBudgetNanos = (1_000_000_000.0 / refreshRate).toLong()
        window.addOnFrameMetricsAvailableListener(listener, timingHandler)
    }

    fun beginStartupSnapshot() {
        snapshotStartedAt = SystemClock.elapsedRealtimeNanos()
    }

    fun endStartupSnapshot() {
        val start = snapshotStartedAt
        if (start == 0L) return
        snapshotStartedAt = 0L
        val elapsedMs = (SystemClock.elapsedRealtimeNanos() - start) / 1_000_000.0
        Log.i(TAG, "startup_snapshot elapsedMs=${format(elapsedMs)}")
    }

    fun recordStartupFrames() {
        start("startup_first_frames", STARTUP_WINDOW_MS)
    }

    fun recordTabSwitch(destinationTab: Int) {
        val order = synchronized(lock) { ++tabSwitchCount }
        val prefix = if (order == 1) "first_tab_switch" else "repeat_tab_switch"
        start("${prefix}_to_$destinationTab", INTERACTION_WINDOW_MS)
    }

    fun recordExpansion(source: String) {
        val order = synchronized(lock) { ++expansionCount }
        val prefix = if (order == 1) "first_expand" else "repeat_expand"
        start("${prefix}_$source", INTERACTION_WINDOW_MS)
    }

    fun uninstall(window: Window) {
        if (installedWindow !== window) return
        runCatching { window.removeOnFrameMetricsAvailableListener(listener) }
        val unfinished = synchronized(lock) {
            val copy = samples.values.toList()
            samples.clear()
            copy
        }
        unfinished.forEach { log(it, suffix = " activity_destroyed") }
        timingHandler?.removeCallbacksAndMessages(null)
        timingThread?.quitSafely()
        timingHandler = null
        timingThread = null
        installedWindow = null
        snapshotStartedAt = 0L
    }

    private fun start(label: String, windowMs: Long) {
        val handler = timingHandler ?: return
        val id = synchronized(lock) {
            val value = nextSampleId++
            samples[value] = Sample(value, label, windowMs)
            value
        }
        handler.postDelayed({ finish(id, timedOut = true) }, NO_FRAME_TIMEOUT_MS)
    }

    private fun finish(id: Long, timedOut: Boolean) {
        val sample = synchronized(lock) { samples.remove(id) } ?: return
        log(sample, suffix = if (timedOut && sample.durationsNanos.isEmpty()) " no_frame_timeout" else "")
    }

    private fun log(sample: Sample, suffix: String) {
        val stats = FrameTimingMath.summarize(sample.durationsNanos, frameBudgetNanos)
        if (stats == null) {
            Log.i(TAG, "${sample.label} frames=0${suffix}")
            return
        }
        Log.i(
            TAG,
            "${sample.label} frames=${stats.frameCount}" +
                " avgMs=${format(stats.averageMs)} p90Ms=${format(stats.p90Ms)}" +
                " maxMs=${format(stats.maxMs)} overBudget=${stats.overBudgetFrames}" +
                " droppedCallbacks=${sample.droppedCallbacks}${suffix}"
        )
    }

    private fun format(value: Double): String = String.format(Locale.US, "%.2f", value)
}
