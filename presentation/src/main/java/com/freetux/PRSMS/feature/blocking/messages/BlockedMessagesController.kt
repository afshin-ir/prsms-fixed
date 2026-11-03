/*
 * Copyright (C) 2019 Freetux <thefreetux@gmail.com>
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
package com.freetux.PRSMS.feature.blocking.messages

import android.app.AlertDialog
import android.content.Context
import android.view.Menu
import android.view.MenuInflater
import android.view.MenuItem
import android.view.View
import android.provider.Telephony.Mms
import android.provider.Telephony.Sms
import com.freetux.PRSMS.R
import com.freetux.PRSMS.common.base.QkController
import com.freetux.PRSMS.common.util.Colors
import com.freetux.PRSMS.feature.blocking.BlockingDialog
import com.freetux.PRSMS.injection.appComponent
import com.freetux.PRSMS.model.Conversation
import com.freetux.PRSMS.model.Message
import com.freetux.PRSMS.common.util.extensions.toPersianDigits
import io.realm.Realm
import io.realm.RealmResults
import io.reactivex.subjects.PublishSubject
import io.reactivex.subjects.Subject
import kotlinx.android.synthetic.main.blocked_messages_controller.*
import kotlinx.android.synthetic.main.container_activity.*
import javax.inject.Inject

class BlockedMessagesController : QkController<BlockedMessagesView, BlockedMessagesState, BlockedMessagesPresenter>(),
    BlockedMessagesView {

    private var observedConversations: RealmResults<Conversation>? = null
    private val blockedConversationsListener: (RealmResults<Conversation>) -> Unit = { updateCountSummary(it) }

    override val menuReadyIntent: Subject<Unit> = PublishSubject.create()
    override val optionsItemIntent: Subject<Int> = PublishSubject.create()
    override val conversationClicks by lazy { blockedMessagesAdapter.clicks }
    override val selectionChanges by lazy { blockedMessagesAdapter.selectionChanges }
    override val confirmDeleteIntent: Subject<List<Long>> = PublishSubject.create()
    override val backClicked: Subject<Unit> = PublishSubject.create()

    @Inject lateinit var blockedMessagesAdapter: BlockedMessagesAdapter
    @Inject lateinit var blockingDialog: BlockingDialog
    @Inject lateinit var colors: Colors
    @Inject lateinit var context: Context
    @Inject override lateinit var presenter: BlockedMessagesPresenter

    init {
        appComponent.inject(this)
        retainViewMode = RetainViewMode.RETAIN_DETACH
        layoutRes = R.layout.blocked_messages_controller
    }

    override fun onViewCreated() {
        super.onViewCreated()
        blockedMessagesAdapter.emptyView = empty
        conversations.adapter = blockedMessagesAdapter
    }

    override fun onAttach(view: View) {
        super.onAttach(view)
        presenter.bindIntents(this)
        setTitle(R.string.blocked_messages_title)
        showBackButton(true)
        setHasOptionsMenu(true)
    }

    override fun onCreateOptionsMenu(menu: Menu, inflater: MenuInflater) {
        super.onCreateOptionsMenu(menu, inflater)
        inflater.inflate(R.menu.blocked_messages, menu)
        menuReadyIntent.onNext(Unit)
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        optionsItemIntent.onNext(item.itemId)
        return true
    }

    override fun handleBack(): Boolean {
        backClicked.onNext(Unit)
        return true
    }

    override fun render(state: BlockedMessagesState) {
        blockedMessagesAdapter.updateData(state.data)
        if (observedConversations !== state.data) {
            observedConversations?.removeChangeListener(blockedConversationsListener)
            observedConversations = state.data
            observedConversations?.addChangeListener(blockedConversationsListener)
        }
        state.data?.let(::updateCountSummary)

        themedActivity?.toolbar?.menu?.findItem(R.id.block)?.isVisible = state.selected > 0
        themedActivity?.toolbar?.menu?.findItem(R.id.delete)?.isVisible = state.selected > 0

        setTitle(when (state.selected) {
            0 -> context.getString(R.string.blocked_messages_title)
            else -> context.getString(R.string.main_title_selected, state.selected)
        })
    }

    private fun updateCountSummary(conversations: RealmResults<Conversation>) {
        if (!conversations.isLoaded || !conversations.isValid) return

        val senderCount = conversations
                .flatMap { conversation -> conversation.recipients.map { recipient -> recipient.address } }
                .filter { address -> address.isNotBlank() }
                .distinct()
                .size
        val messageCount = Realm.getDefaultInstance().use { realm ->
            conversations.fold(0L) { count, conversation ->
                count + realm.where(Message::class.java)
                        .equalTo("threadId", conversation.id)
                        .beginGroup()
                        .beginGroup()
                        .equalTo("type", "sms")
                        .`in`("boxId", arrayOf(Sms.MESSAGE_TYPE_INBOX, Sms.MESSAGE_TYPE_ALL))
                        .endGroup()
                        .or()
                        .beginGroup()
                        .equalTo("type", "mms")
                        .`in`("boxId", arrayOf(Mms.MESSAGE_BOX_INBOX, Mms.MESSAGE_BOX_ALL))
                        .endGroup()
                        .endGroup()
                        .count()
            }
        }

        summary.text = resources?.getString(
                R.string.blocked_messages_summary,
                messageCount.toPersianDigits(),
                senderCount.toPersianDigits()
        )
    }

    override fun onDestroyView(view: View) {
        observedConversations?.removeChangeListener(blockedConversationsListener)
        observedConversations = null
        super.onDestroyView(view)
    }

    override fun clearSelection() = blockedMessagesAdapter.clearSelection()

    override fun showBlockingDialog(conversations: List<Long>, block: Boolean) {
        blockingDialog.show(activity!!, conversations, block)
    }

    override fun showDeleteDialog(conversations: List<Long>) {
        val count = conversations.size
        AlertDialog.Builder(activity)
                .setTitle(R.string.dialog_delete_title)
                .setMessage(resources?.getQuantityString(R.plurals.dialog_delete_message, count, count))
                .setPositiveButton(R.string.button_delete) { _, _ -> confirmDeleteIntent.onNext(conversations) }
                .setNegativeButton(R.string.button_cancel, null)
                .show()
    }

    override fun goBack() {
        router.popCurrentController()
    }

}
