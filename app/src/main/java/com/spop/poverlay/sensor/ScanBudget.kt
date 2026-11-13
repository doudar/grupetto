package com.spop.poverlay.sensor

/**
 * Limits how long a manager keeps scanning for a saved Bluetooth sensor that is not connected.
 *
 * A scan window is opened at start-up, when the bike starts moving after being idle for a while,
 * and when a connected sensor is lost. It closes after [windowMs]. Brief pauses while riding do not
 * open a new window, so a second saved sensor that is not in the room is not searched for all ride.
 * Until the first window is opened, scanning is allowed.
 */
internal class ScanBudget(
    private val windowMs: Long = 5 * 60_000L,
    private val idleResetMs: Long = 5 * 60_000L,
) {
    private var armed = false
    private var until = 0L
    private var movedBefore = false
    private var idleSince: Long? = null

    fun arm(now: Long) {
        armed = true
        until = now + windowMs
    }

    fun allows(now: Long): Boolean = !armed || now < until

    /**
     * Reports whether the bike is moving. Returns true when this opened a new scan window.
     */
    fun onMovement(moving: Boolean, now: Long): Boolean {
        if (!moving) {
            if (idleSince == null) idleSince = now
            return false
        }
        val idleFor = idleSince?.let { now - it }
        idleSince = null
        val sameSession = movedBefore && idleFor != null && idleFor < idleResetMs
        val alreadyMoving = movedBefore && idleFor == null
        movedBefore = true
        if (sameSession || alreadyMoving) return false
        arm(now)
        return true
    }
}
