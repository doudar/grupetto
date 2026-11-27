package com.spop.poverlay

/**
 * What the overlay shows, each one switched on or off in settings (Overlay › Show on the overlay).
 * A metric still only shows where it applies (incline on a Tread, heart rate with a monitor paired);
 * switching it off hides it in both the expanded overlay and the minimized bar.
 */
enum class OverlayItem(val key: String, val label: String, val shownByDefault: Boolean = true) {
    Power("overlayShowPower", "Power"),
    Cadence("overlayShowCadence", "Cadence"),
    Resistance("overlayShowResistance", "Resistance"),
    Speed("overlayShowSpeed", "Speed"),
    Incline("overlayShowIncline", "Incline"),
    HeartRate("overlayShowHeartRate", "Heart rate"),
    Calories("overlayShowCalories", "Calories"),
    Chart("overlayShowChart", "Chart"),
    /** The flywheel (brake disc) temperature estimate, as a chip on the overlay bar. Off until asked for. */
    FlywheelTemperature("overlayShowFlywheelTemperature", "Flywheel temperature", shownByDefault = false);

    companion object {
        val defaults: Set<OverlayItem> = values().filter { it.shownByDefault }.toSet()

        /** The items that apply to this machine, in the order the settings list them. */
        fun forDevice(isTread: Boolean, hasFlywheelEstimate: Boolean): List<OverlayItem> =
            if (isTread) listOf(Incline, Speed, HeartRate, Chart)
            else listOfNotNull(Power, Cadence, Resistance, Speed, HeartRate, Calories, Chart,
                FlywheelTemperature.takeIf { hasFlywheelEstimate })
    }
}
