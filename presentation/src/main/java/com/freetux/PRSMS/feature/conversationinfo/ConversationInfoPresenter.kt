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
package com.freetux.PRSMS.feature.conversationinfo

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import java.io.File
import java.io.FileOutputStream
import androidx.lifecycle.Lifecycle
import com.freetux.PRSMS.R
import com.freetux.PRSMS.common.Navigator
import com.freetux.PRSMS.common.base.QkPresenter
import com.freetux.PRSMS.common.util.ClipboardUtils
import com.freetux.PRSMS.common.util.extensions.makeToast
import com.freetux.PRSMS.extensions.asObservable
import com.freetux.PRSMS.extensions.mapNotNull
import com.freetux.PRSMS.feature.conversationinfo.ConversationInfoItem.ConversationInfoMedia
import com.freetux.PRSMS.feature.conversationinfo.ConversationInfoItem.ConversationInfoRecipient
import com.freetux.PRSMS.interactor.DeleteConversations
import com.freetux.PRSMS.interactor.MarkArchived
import com.freetux.PRSMS.interactor.MarkUnarchived
import com.freetux.PRSMS.manager.PermissionManager
import com.freetux.PRSMS.model.Conversation
import com.freetux.PRSMS.repository.ConversationRepository
import com.freetux.PRSMS.repository.MessageRepository
import com.uber.autodispose.android.lifecycle.scope
import com.uber.autodispose.autoDisposable
import io.reactivex.android.schedulers.AndroidSchedulers
import io.reactivex.schedulers.Schedulers
import io.reactivex.rxkotlin.Observables
import io.reactivex.rxkotlin.plusAssign
import io.reactivex.rxkotlin.withLatestFrom
import io.reactivex.subjects.BehaviorSubject
import io.reactivex.subjects.Subject
import javax.inject.Inject
import timber.log.Timber
import javax.inject.Named

class ConversationInfoPresenter @Inject constructor(
    @Named("threadId") threadId: Long,
    messageRepo: MessageRepository,
    private val context: Context,
    private val conversationRepo: ConversationRepository,
    private val deleteConversations: DeleteConversations,
    private val markArchived: MarkArchived,
    private val markUnarchived: MarkUnarchived,
    private val navigator: Navigator,
    private val permissionManager: PermissionManager
) : QkPresenter<ConversationInfoView, ConversationInfoState>(
        ConversationInfoState(threadId = threadId)
) {

    private val conversation: Subject<Conversation> = BehaviorSubject.create()

    init {
        disposables += conversationRepo.getConversationAsync(threadId)
                .asObservable()
                .filter { conversation -> conversation.isLoaded }
                .doOnNext { conversation ->
                    if (!conversation.isValid) {
                        newState { copy(hasError = true) }
                    }
                }
                .filter { conversation -> conversation.isValid }
                .filter { conversation -> conversation.id != 0L }
                .subscribe(conversation::onNext)

        disposables += markArchived
        disposables += markUnarchived
        disposables += deleteConversations

        disposables += Observables
                .combineLatest(
                        conversation,
                        messageRepo.getPartsForConversation(threadId).asObservable()
                ) { conversation, parts ->
                    val data = mutableListOf<ConversationInfoItem>()

                    // If some data was deleted, this isn't the place to handle it
                    if (!conversation.isLoaded || !conversation.isValid || !parts.isLoaded || !parts.isValid) {
                        return@combineLatest
                    }

                    data += conversation.recipients.map { recipient -> ConversationInfoRecipient(recipient, conversation.photo) }
                    data += ConversationInfoItem.ConversationInfoSettings(
                            name = conversation.name,
                            recipients = conversation.recipients,
                            archived = conversation.archived,
                            blocked = conversation.blocked)
                    data += parts.map(::ConversationInfoMedia)

                    newState { copy(data = data) }
                }
                .subscribe()
    }

    private fun storeSenderPhoto(conversationId: Long, uri: String): String {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        context.contentResolver.openInputStream(android.net.Uri.parse(uri))?.use { BitmapFactory.decodeStream(it, null, bounds) }
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) throw IllegalArgumentException("Invalid image")

        var sampleSize = 1
        while (bounds.outWidth / sampleSize > 1024 || bounds.outHeight / sampleSize > 1024) sampleSize *= 2
        val options = BitmapFactory.Options().apply { inSampleSize = sampleSize }
        val bitmap = context.contentResolver.openInputStream(android.net.Uri.parse(uri))?.use {
            BitmapFactory.decodeStream(it, null, options)
        } ?: throw IllegalArgumentException("Unable to open image")

        val maxSize = 512
        val scale = minOf(1f, maxSize.toFloat() / maxOf(bitmap.width, bitmap.height))
        val resized = if (scale < 1f) Bitmap.createScaledBitmap(
                bitmap,
                (bitmap.width * scale).toInt().coerceAtLeast(1),
                (bitmap.height * scale).toInt().coerceAtLeast(1),
                true
        ) else bitmap
        val relativePath = "sender_photos/$conversationId.jpg"
        val file = File(context.filesDir, relativePath)
        file.parentFile?.mkdirs()
        FileOutputStream(file).use { output ->
            if (!resized.compress(Bitmap.CompressFormat.JPEG, 82, output)) throw IllegalStateException("Unable to encode image")
        }
        if (resized !== bitmap) resized.recycle()
        bitmap.recycle()
        return relativePath
    }

    override fun bindIntents(view: ConversationInfoView) {
        super.bindIntents(view)

        // Add or display the contact
        view.recipientClicks()
                .mapNotNull(conversationRepo::getRecipient)
                .doOnNext { recipient ->
                    recipient.contact?.lookupKey?.let(navigator::showContact)
                            ?: navigator.addContact(recipient.address)
                }
                .autoDisposable(view.scope(Lifecycle.Event.ON_DESTROY)) // ... this should be the default
                .subscribe()

        // Copy phone number
        view.recipientLongClicks()
                .mapNotNull(conversationRepo::getRecipient)
                .map { recipient -> recipient.address }
                .observeOn(AndroidSchedulers.mainThread())
                .autoDisposable(view.scope())
                .subscribe { address ->
                    ClipboardUtils.copy(context, address)
                    context.makeToast(R.string.info_copied_address)
                }

        // Show the theme settings for the conversation
        view.themeClicks()
                .autoDisposable(view.scope())
                .subscribe(view::showThemePicker)

        // Show the conversation title dialog
        view.nameClicks()
                .withLatestFrom(conversation) { _, conversation -> conversation }
                .map { conversation -> conversation.name }
                .autoDisposable(view.scope())
                .subscribe(view::showNameDialog)

        // Save the conversation title on a background thread. Realm instances are thread-confined.
        view.nameChanges()
                .withLatestFrom(conversation) { name, conversation -> conversation.id to name }
                .observeOn(Schedulers.io())
                .doOnNext { (conversationId, name) ->
                    conversationRepo.setConversationName(conversationId, name)
                }
                .autoDisposable(view.scope())
                .subscribe(
                        { },
                        { error -> Timber.e(error, "Failed to save conversation title") }
                )

        // Choose a custom sender photo, stored inside the app and independent of phone contacts.
        view.photoClicks()
                .autoDisposable(view.scope())
                .subscribe { view.requestSenderPhotoPicker() }

        view.photoChanges()
                .withLatestFrom(conversation) { uri, conversation -> conversation.id to uri }
                .observeOn(Schedulers.io())
                .map { (conversationId, uri) -> conversationId to storeSenderPhoto(conversationId, uri) }
                .doOnNext { (conversationId, path) -> conversationRepo.setConversationPhoto(conversationId, path) }
                .autoDisposable(view.scope())
                .subscribe(
                        { },
                        { error -> Timber.e(error, "Failed to save sender photo") }
                )

        // Show the notifications settings for the conversation
        view.notificationClicks()
                .withLatestFrom(conversation) { _, conversation -> conversation }
                .autoDisposable(view.scope())
                .subscribe { conversation -> navigator.showNotificationSettings(conversation.id) }

        // Toggle the archived state of the conversation
        view.archiveClicks()
                .withLatestFrom(conversation) { _, conversation -> conversation }
                .autoDisposable(view.scope())
                .subscribe { conversation ->
                    when (conversation.archived) {
                        true -> markUnarchived.execute(listOf(conversation.id))
                        false -> markArchived.execute(listOf(conversation.id))
                    }
                }

        // Toggle the blocked state of the conversation
        view.blockClicks()
                .withLatestFrom(conversation) { _, conversation -> conversation }
                .autoDisposable(view.scope())
                .subscribe { conversation -> view.showBlockingDialog(listOf(conversation.id), !conversation.blocked) }

        // Show the delete confirmation dialog
        view.deleteClicks()
                .filter { permissionManager.isDefaultSms().also { if (!it) view.requestDefaultSms() } }
                .autoDisposable(view.scope())
                .subscribe { view.showDeleteDialog() }

        // Delete the conversation
        view.confirmDelete()
                .withLatestFrom(conversation) { _, conversation -> conversation }
                .autoDisposable(view.scope())
                .subscribe { conversation -> deleteConversations.execute(listOf(conversation.id)) }

        // Media
        view.mediaClicks()
                .autoDisposable(view.scope())
                .subscribe(navigator::showMedia)
    }

}