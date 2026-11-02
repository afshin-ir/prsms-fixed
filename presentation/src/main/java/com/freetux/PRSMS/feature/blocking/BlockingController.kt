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
import android.view.View
import android.widget.EditText
import com.bluelinelabs.conductor.RouterTransaction
import com.jakewharton.rxbinding2.view.clicks
import com.freetux.PRSMS.R
import com.freetux.PRSMS.common.QkChangeHandler
import com.freetux.PRSMS.common.base.QkController
import com.freetux.PRSMS.common.util.Colors
import com.freetux.PRSMS.common.util.extensions.animateLayoutChanges
import com.freetux.PRSMS.feature.blocking.manager.BlockingManagerController
import com.freetux.PRSMS.feature.blocking.messages.BlockedMessagesController
import com.freetux.PRSMS.feature.blocking.numbers.BlockedNumbersController
import com.freetux.PRSMS.injection.appComponent
import com.freetux.PRSMS.util.Preferences
import kotlinx.android.synthetic.main.blocking_controller.*
import kotlinx.android.synthetic.main.settings_switch_widget.view.*
import javax.inject.Inject

class BlockingController : QkController<BlockingView, BlockingState, BlockingPresenter>(), BlockingView {

    override val blockingManagerIntent by lazy { blockingManager.clicks() }
    override val blockedNumbersIntent by lazy { blockedNumbers.clicks() }
    override val blockedMessagesIntent by lazy { blockedMessages.clicks() }
    override val blockedKeywordsIntent by lazy { blockedKeywords.clicks() }
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
        blockingManager.summary = state.blockingManager
        drop.checkbox.isChecked = state.dropEnabled
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
        val keywords = prefs.blockedKeywords.get().filter { it.isNotBlank() }.sorted()
        val selected = BooleanArray(keywords.size)
        AlertDialog.Builder(activity!!)
                .setTitle(R.string.blocking_keywords_title)
                .setMessage(R.string.blocking_keywords_match_summary)
                .setMultiChoiceItems(keywords.toTypedArray(), selected) { _, which, isChecked ->
                    selected[which] = isChecked
                }
                .setPositiveButton(R.string.blocking_keywords_add) { _, _ -> showAddKeywordDialog() }
                .setNegativeButton(R.string.blocking_keywords_remove) { _, _ ->
                    val remaining = keywords.filterIndexed { index, _ -> !selected[index] }.toSet()
                    if (remaining.size != keywords.size) prefs.blockedKeywords.set(remaining)
                }
                .setNeutralButton(R.string.button_cancel, null)
                .show()
    }

    private fun showAddKeywordDialog() {
        val input = EditText(activity!!).apply {
            hint = context.getString(R.string.blocking_keywords_hint)
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_SENTENCES
            setSingleLine(true)
        }

        AlertDialog.Builder(activity!!)
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
                }
                .setNegativeButton(R.string.button_cancel, null)
                .show()
    }

    override fun openBlockingManager() {
        router.pushController(RouterTransaction.with(BlockingManagerController())
                .pushChangeHandler(QkChangeHandler())
                .popChangeHandler(QkChangeHandler()))
    }

}
