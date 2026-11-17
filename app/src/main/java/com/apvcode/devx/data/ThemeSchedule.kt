package com.apvcode.devx.data

/** Which theme fits the time of day: a day one and a night one, with the night running from one clock time to another (past midnight too). */
object ThemeSchedule {
    const val DEFAULT_FROM = 21 * 60
    const val DEFAULT_TO = 7 * 60

    /** True when [minute] (of the day, 0..1439) is in the night that begins at [from] and ends at [to]. Equal times mean there is no night. */
    fun isNight(minute: Int, from: Int, to: Int): Boolean = when {
        from == to -> false
        from < to -> minute in from until to
        else -> minute >= from || minute < to
    }

    fun modeAt(minute: Int, from: Int, to: Int, day: ThemeMode, night: ThemeMode): ThemeMode = if (isNight(minute, from, to)) night else day

    /** "21:05" for a time given in minutes. */
    fun label(minutes: Int): String = "%02d:%02d".format((minutes / 60) % 24, minutes % 60)
}
