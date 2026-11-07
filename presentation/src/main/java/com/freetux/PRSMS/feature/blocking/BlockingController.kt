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
package com.freetux.PRSMS.feature.blocking

import android.app.AlertDialog
import android.content.Context
import android.text.InputType
import android.view.Gravity
import android.view.View
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import com.bluelinelabs.conductor.RouterTransaction
import com.jakewharton.rxbinding2.view.clicks
import com.freetux.PRSMS.R
import com.freetux.PRSMS.common.QkChangeHandler
import com.freetux.PRSMS.common.base.QkController
import com.freetux.PRSMS.common.util.Colors
import com.freetux.PRSMS.common.util.extensions.animateLayoutChanges
import com.freetux.PRSMS.feature.blocking.messages.BlockedMessagesController
import com.freetux.PRSMS.feature.blocking.numbers.BlockedNumbersController
import com.freetux.PRSMS.injection.appComponent
import com.freetux.PRSMS.util.Preferences
import kotlinx.android.synthetic.main.blocking_controller.*
import kotlinx.android.synthetic.main.settings_switch_widget.view.*
import javax.inject.Inject

class BlockingController : QkController<BlockingView, BlockingState, BlockingPresenter>(), BlockingView {

    override val blockedNumbersIntent by lazy { blockedNumbers.clicks() }
    override val blockedMessagesIntent by lazy { blockedMessages.clicks() }
    override val blockedKeywordsIntent by lazy { blockedKeywords.clicks() }
    override val keywordContactsIntent by lazy { keywordContacts.clicks() }
    override val dropClickedIntent by lazy { drop.clicks() }

    @Inject lateinit var colors: Colors
    @Inject lateinit var context: Context
    @Inject lateinit var prefs: Preferences
    @Inject override lateinit var presenter: BlockingPresenter

    init {
        appComponent.inject(this)
        retainViewMode = RetainViewMode.RETAIN_DETACH
        layoutRes = R.layout.blocking_controller
    }

    override fun onViewCreated() {
        super.onViewCreated()
        parent.postDelayed({ parent?.animateLayoutChanges = true }, 100)
    }

    override fun onAttach(view: View) {
        super.onAttach(view)
        presenter.bindIntents(this)
        setTitle(R.string.blocking_title)
        showBackButton(true)
    }

    override fun render(state: BlockingState) {
        drop.checkbox.isChecked = state.dropEnabled
        keywordContacts.checkbox.isChecked = state.excludeContactsFromKeywordBlocking
        blockedMessages.isEnabled = !state.dropEnabled
    }

    override fun openBlockedNumbers() {
        router.pushController(RouterTransaction.with(BlockedNumbersController())
                .pushChangeHandler(QkChangeHandler())
                .popChangeHandler(QkChangeHandler()))
    }

    override fun openBlockedMessages() {
        router.pushController(RouterTransaction.with(BlockedMessagesController())
                .pushChangeHandler(QkChangeHandler())
                .popChangeHandler(QkChangeHandler()))
    }

    override fun openBlockedKeywords() {
        val currentActivity = activity ?: return
        val padding = (16 * currentActivity.resources.displayMetrics.density).toInt()
        val rowHeight = (48 * currentActivity.resources.displayMetrics.density).toInt()
        val content = LinearLayout(currentActivity).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(padding, padding / 2, padding, 0)
        }
        val description = TextView(currentActivity).apply {
            text = context.getString(R.string.blocking_keywords_match_summary)
            setPadding(0, 0, 0, padding)
        }
        content.addView(description)

        val list = LinearLayout(currentActivity).apply {
            orientation = LinearLayout.VERTICAL
        }
        val scroll = ScrollView(currentActivity).apply {
            addView(list, FrameLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
            ))
        }
        content.addView(scroll, LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                (260 * currentActivity.resources.displayMetrics.density).toInt()
        ))

        fun refreshList() {
            list.removeAllViews()
            val keywords = prefs.blockedKeywords.get()
                    .filter { it.isNotBlank() }
                    .sorted()

            if (keywords.isEmpty()) {
                list.addView(TextView(currentActivity).apply {
                    text = context.getString(R.string.blocking_keywords_empty)
                    gravity = Gravity.CENTER_VERTICAL
                    minHeight = rowHeight
                })
                return
            }

            keywords.forEach { keyword ->
                val row = LinearLayout(currentActivity).apply {
                    orientation = LinearLayout.HORIZONTAL
                    layoutDirection = View.LAYOUT_DIRECTION_LOCALE
                    minimumHeight = rowHeight
                }
                val label = TextView(currentActivity).apply {
                    text = keyword
                    gravity = Gravity.CENTER_VERTICAL
                    maxLines = 1
                    setPadding(0, 0, padding / 2, 0)
                }
                row.addView(label, LinearLayout.LayoutParams(0, rowHeight, 1f))

                val remove = TextView(currentActivity).apply {
                    text = "×"
                    textSize = 24f
                    gravity = Gravity.CENTER
                    contentDescription = context.getString(R.string.blocking_keywords_remove)
                    isClickable = true
                    isFocusable = true
                    setOnClickListener {
                        val remaining = prefs.blockedKeywords.get()
                                .filterNot { it.equals(keyword, ignoreCase = true) }
                                .toSet()
                        prefs.blockedKeywords.set(remaining)
                        refreshList()
                    }
                }
                row.addView(remove, LinearLayout.LayoutParams(rowHeight, rowHeight))
                list.addView(row, LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.MATCH_PARENT,
                        rowHeight
                ))
            }
        }
        refreshList()

        val dialog = AlertDialog.Builder(currentActivity)
                .setTitle(R.string.blocking_keywords_title)
                .setView(content)
                .setPositiveButton(R.string.blocking_keywords_add, null)
                .setNegativeButton(R.string.button_cancel, null)
                .create()
        dialog.setOnShowListener {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                dialog.dismiss()
                showAddKeywordDialog { openBlockedKeywords() }
            }
        }
        dialog.show()
    }

    private fun showAddKeywordDialog(onSaved: (() -> Unit)? = null) {
        val input = EditText(activity!!).apply {
            hint = context.getString(R.string.blocking_keywords_hint)
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_SENTENCES
            setSingleLine(true)
        }

        var saved = false
        val dialog = AlertDialog.Builder(activity!!)
                .setTitle(R.string.blocking_keywords_add)
                .setView(input)
                .setPositiveButton(R.string.blocking_keywords_save) { _, _ ->
                    val keyword = input.text.toString().trim()
                    if (keyword.isNotEmpty()) {
                        val current = prefs.blockedKeywords.get()
                        if (current.none { it.equals(keyword, ignoreCase = true) }) {
                            prefs.blockedKeywords.set(current + keyword)
                        }
                    }
                    saved = true
                }
                .setNegativeButton(R.string.button_cancel, null)
                .create()
        dialog.setOnDismissListener {
            if (saved) onSaved?.invoke()
        }
        dialog.show()
    }

}
