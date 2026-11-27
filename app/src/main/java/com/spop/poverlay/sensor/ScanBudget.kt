package com.spop.poverlay.sensor

/**
 * Decides whether a manager may keep scanning for a saved Bluetooth sensor that is not connected.
 *
 * Scanning keeps going until the sensor connects, as long as the bike is in use: while it is moving
 * and for [idleMs] after it stops, after start-up, or after a connected sensor is lost. Once the bike
 * has been idle that long, scanning pauses until it moves again, so a sensor that is not in the room
 * is not searched for while nobody is riding. Until [arm] or [onMovement] is first called, scanning
 * is allowed.
 */
internal class ScanBudget(private val idleMs: Long = 5 * 60_000L) {
    private var moving = false
    private var lastActive: Long? = null

    /** Start-up, or a connected sensor was lost: scan for at least [idleMs] from [now]. */
    fun arm(now: Long) {
        lastActive = now
    }

    fun allows(now: Long): Boolean {
        val last = lastActive ?: return true
        return moving || now - last < idleMs
    }

    /** Reports whether the bike is moving. Returns true when this resumes paused scanning. */
    fun onMovement(moving: Boolean, now: Long): Boolean {
        val wasAllowed = allows(now)
        this.moving = moving
        lastActive = now
        return moving && !wasAllowed
    }
}
