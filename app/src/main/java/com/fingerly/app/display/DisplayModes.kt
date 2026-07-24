package com.fingerly.app.display

import android.app.Activity

/**
 * SPEC §1: request and hold the highest refresh rate explicitly via
 * preferredDisplayModeId. Never assume the OS picked it — HyperOS may lock
 * third-party apps to 60Hz regardless (surfaced in the first-run checklist).
 */
object DisplayModes {

    /**
     * Requests the display mode with the highest refresh rate at the current
     * resolution. Returns the requested rate (the OS may still refuse; read
     * [currentRefreshRate] to see what actually happened).
     */
    fun requestHighestRefreshRate(activity: Activity): Float {
        val display = activity.display ?: return 0f
        val current = display.mode
        val best = display.supportedModes
            .filter {
                it.physicalWidth == current.physicalWidth &&
                    it.physicalHeight == current.physicalHeight
            }
            .maxByOrNull { it.refreshRate }
            ?: return current.refreshRate
        val attrs = activity.window.attributes
        if (attrs.preferredDisplayModeId != best.modeId) {
            attrs.preferredDisplayModeId = best.modeId
            activity.window.attributes = attrs
        }
        return best.refreshRate
    }

    /** The refresh rate the display is actually running at right now. */
    fun currentRefreshRate(activity: Activity): Float =
        activity.display?.refreshRate ?: 0f
}
