/*
 * Copyright (C) 2017 Freetux <thefreetux@gmail.com>
 *
 * This file is part of PRSMS.
 *
 * PRSMS is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * PRSMS is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with PRSMS.  If not, see <http://www.gnu.org/licenses/>.
 */
package com.freetux.PRSMS.common.util

import android.content.Context
import android.text.format.DateFormat
import com.freetux.PRSMS.common.util.extensions.isSameDay
import com.freetux.PRSMS.common.util.extensions.isSameWeek
import com.freetux.PRSMS.common.util.extensions.isSameYear
import com.freetux.PRSMS.common.util.shamsicalendar.PersianDate
import com.freetux.PRSMS.common.util.shamsicalendar.PersianDateFormat
import com.freetux.PRSMS.util.Preferences
import java.text.SimpleDateFormat
import java.util.*
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class DateFormatter @Inject constructor(val context: Context, private val prefs: Preferences) {

    /**
     * Formats the [pattern] correctly for the current locale, and replaces 12 hour format with
     * 24 hour format if necessary
     */
    private fun getFormatter(pattern: String): SimpleDateFormat {
        var formattedPattern = DateFormat.getBestDateTimePattern(Locale.getDefault(), pattern)
        if (DateFormat.is24HourFormat(context)) {
            formattedPattern = formattedPattern
                    .replace("h", "HH")
                    .replace("K", "HH")
                    .replace(" a".toRegex(), "")
        }

        return SimpleDateFormat(formattedPattern, Locale.getDefault())
    }

    private fun getPersianDateFormat(pattern: String): PersianDateFormat {
        return PersianDateFormat(pattern, PersianDateFormat.PersianDateNumberCharacter.FARSI)
    }

    // Keep numeric date fields in a predictable left-to-right sequence inside RTL layouts.
    private fun formatPersianDate(date: PersianDate, pattern: String): String {
        return "\u2066" + getPersianDateFormat(pattern).format(date) + "\u2069"
    }

    // Force a left-to-right run so Persian RTL reading starts with the day on the right.
    // Same RTL date order used by v1.0.8 in conversation and blocked lists.
    private fun formatPersianMonthDay(date: PersianDate): String {
        return "\u2067" + getPersianDateFormat("j F").format(date) + "\u2069"
    }

    // Keep the requested visual sequence "day month" in the open-message timestamp.
    private fun formatPersianMessageMonthDay(date: PersianDate): String {
        val day = getPersianDateFormat("j").format(date)
        val month = getPersianDateFormat("F").format(date)
        return "\u2066$day\u00A0$month\u2069"
    }

    // Leave the Persian date fields in one RTL run so their digits stay with their fields.
    private fun formatPersianMessageFullDate(date: PersianDate): String {
        val day = getPersianDateFormat("j").format(date)
        val month = getPersianDateFormat("F").format(date)
        val year = getPersianDateFormat("Y").format(date)
        return "$day\u00A0$month\u00A0$year"
    }

    private fun formatPersianMessageTimestamp(time: String, date: String? = null): String {
        val datePart = date?.let { " در: $it" } ?: ""
        return "\u2067ساعت: \u2066$time\u2069$datePart\u2069"
    }

    fun getDetailedTimestamp(date: Long): String {
        val pdate = PersianDate(date)
        if (!prefs.shamsiDate.get()) {
            return getFormatter("M/d/y, h:mm:ss a").format(date)
        } else {
            return formatPersianDate(pdate, "Y/n/j") + " در " + getFormatter("h:mm:ss a").format(date)
        }
    }

    fun getTimestamp(date: Long): String {
        return getFormatter("h:mm a").format(date)
    }

    fun isSameShamsiYear(thenyear: Int): Boolean {
        val pdatenow = PersianDate()
        return thenyear == pdatenow.getShYear()
    }

    fun getMessageTimestamp(date: Long): String {
        val now = Calendar.getInstance()
        val then = Calendar.getInstance()
        then.timeInMillis = date
        val pdate = PersianDate(date)
        val pdatenow = PersianDate()
        val thenyear = pdate.getShYear()

        if (!prefs.shamsiDate.get()) {
            return when {
                now.isSameDay(then) -> getFormatter("h:mm a")
                now.isSameWeek(then) -> getFormatter("E h:mm a")
                now.isSameYear(then) -> getFormatter("MMM d, h:mm a")
                else -> getFormatter("MMM d yyyy, h:mm a")
            }.format(date)
        }

        val time = getFormatter("h:mm a").format(date)
        return when {
            now.isSameDay(then) -> formatPersianMessageTimestamp(time)
            now.isSameWeek(then) -> formatPersianMessageTimestamp(
                    time,
                    getPersianDateFormat("l").format(pdate)
            )
            isSameShamsiYear(thenyear) -> formatPersianMessageTimestamp(
                    time,
                    formatPersianMessageMonthDay(pdate)
            )
            else -> formatPersianMessageTimestamp(
                    time,
                    formatPersianMessageFullDate(pdate)
            )
        }
    }

    fun getConversationTimestamp(date: Long): String {
        val now = Calendar.getInstance()
        val then = Calendar.getInstance()
        then.timeInMillis = date
        val pdate = PersianDate(date)
        val pdatenow = PersianDate()
        val nowyear = pdatenow.getShYear()
        val thenyear = pdate.getShYear()
        if (!prefs.shamsiDate.get()) {
            return when {
                now.isSameDay(then) -> getFormatter("h:mm a")
                now.isSameWeek(then) -> getFormatter("E")
                now.isSameYear(then) -> getFormatter("MMM d")
                else -> getFormatter("MM/d/yy")
            }.format(date)
        } else {
            return when {
                now.isSameDay(then) -> getFormatter("h:mm a").format(date)
                now.isSameWeek(then) -> getPersianDateFormat("l").format(pdate)
                isSameShamsiYear(thenyear) -> formatPersianMonthDay(pdate)
                else -> formatPersianDate(pdate, "Y/n/j")
            }


        }

    }

    fun getScheduledTimestamp(date: Long): String {
        val now = Calendar.getInstance()
        val then = Calendar.getInstance()
        then.timeInMillis = date
        val pdate = PersianDate(date)
        val pdatenow = PersianDate()
        val nowyear = pdatenow.getShYear()
        val thenyear = pdate.getShYear()
        if (!prefs.shamsiDate.get()) {
            return when {
                now.isSameDay(then) -> getFormatter("h:mm a")
                now.isSameYear(then) -> getFormatter("MMM d h:mm a")
                else -> getFormatter("MMM d yyyy h:mm a")
            }.format(date)
        } else {
            return when {
                now.isSameDay(then) -> getFormatter("h:mm a").format(date)
                isSameShamsiYear(thenyear) -> formatPersianMonthDay(pdate) + " در " + getFormatter("h:mm a").format(date)
                else -> formatPersianDate(pdate, "Y/n/j") + " در " + getFormatter("h:mm a").format(date)
            }


        }
    }

}
