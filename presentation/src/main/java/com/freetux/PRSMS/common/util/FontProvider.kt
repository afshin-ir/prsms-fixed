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
import android.graphics.Typeface
import androidx.core.content.res.ResourcesCompat
import com.freetux.PRSMS.R
import timber.log.Timber
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class FontProvider @Inject constructor(context: Context) {

    private var vazirmatn: Typeface? = null
    private val pendingCallbacks = ArrayList<(Typeface) -> Unit>()

    init {
        ResourcesCompat.getFont(context, R.font.vazirmatn_regular, object : ResourcesCompat.FontCallback() {
            override fun onFontRetrievalFailed(reason: Int) {
                Timber.w("Vazirmatn font retrieval failed: $reason")
            }

            override fun onFontRetrieved(typeface: Typeface) {
                vazirmatn = typeface
                pendingCallbacks.forEach { vazirmatn?.run(it) }
                pendingCallbacks.clear()
            }
        }, null)
    }

    fun getVazirmatn(callback: (Typeface) -> Unit) {
        vazirmatn?.run(callback) ?: pendingCallbacks.add(callback)
    }
}
