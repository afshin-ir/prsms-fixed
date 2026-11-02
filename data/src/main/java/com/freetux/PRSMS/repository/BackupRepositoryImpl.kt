/*
 * Copyright (C) 2017 Moez Bhatti <freetux.bhatti@gmail.com>
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
package com.freetux.PRSMS.repository

import android.content.Context
import android.net.Uri
import android.provider.DocumentsContract
import android.provider.Telephony
import androidx.core.content.contentValuesOf
import com.freetux.PRSMS.model.BackupFile
import com.freetux.PRSMS.model.BlockedNumber
import com.freetux.PRSMS.model.Conversation
import com.freetux.PRSMS.common.util.shamsicalendar.PersianDate
import com.freetux.PRSMS.model.Message
import com.freetux.PRSMS.util.Preferences
import com.freetux.PRSMS.util.tryOrNull
import com.squareup.moshi.Moshi
import io.reactivex.Observable
import io.reactivex.schedulers.Schedulers
import io.reactivex.subjects.BehaviorSubject
import io.reactivex.subjects.Subject
import io.realm.Realm
import okio.buffer
import okio.source
import timber.log.Timber
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.util.*
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.concurrent.schedule

@Singleton
class BackupRepositoryImpl @Inject constructor(
    private val context: Context,
    private val moshi: Moshi,
    private val prefs: Preferences,
    private val syncRepo: SyncRepository,
    private val blockingRepo: BlockingRepository,
    private val conversationRepo: ConversationRepository
) : BackupRepository {

    companion object {
        private const val BACKUP_DIRECTORY_NAME = "PRSMS/Backups"
    }

    // App-specific external storage works with scoped storage and needs no storage permission.
    private val backupDirectory: File by lazy {
        File(context.getExternalFilesDir(null) ?: context.filesDir, BACKUP_DIRECTORY_NAME)
                .apply { mkdirs() }
    }

    data class Backup(
        val messageCount: Int = 0,
        val messages: List<BackupMessage> = listOf(),
        val blockedNumbers: List<String> = listOf(),
        val blockedConversations: List<BackupBlockedConversation> = listOf(),
        val blockedKeywords: List<String> = listOf(),
        val blockedNumberCount: Int = 0,
        val blockedKeywordCount: Int = 0,
        val blockedConversationCount: Int = 0,
        val namedConversations: List<BackupNamedConversation> = listOf()
    )

    /**
     * Simpler version of [Backup] which allows us to read certain fields from the backup without
     * needing to parse the entire file
     */
    data class BackupMetadata(
        val messageCount: Int = 0,
        val blockedNumberCount: Int = 0,
        val blockedConversationCount: Int = 0,
        val blockedKeywordCount: Int = 0
    )

    data class BackupBlockedConversation(
        val addresses: List<String> = listOf(),
        val blockingClient: Int? = null,
        val blockReason: String? = null
    )

    data class BackupNamedConversation(
        val addresses: List<String> = listOf(),
        val name: String = ""
    )

    data class BackupMessage(
        val type: Int,
        val address: String,
        val date: Long,
        val dateSent: Long,
        val read: Boolean,
        val status: Int,
        val body: String,
        val protocol: Int,
        val serviceCenter: String?,
        val locked: Boolean,
        val subId: Int
    )

    // Subjects to emit our progress events to
    private val backupProgress: Subject<BackupRepository.Progress> =
            BehaviorSubject.createDefault(BackupRepository.Progress.Idle())
    private val restoreProgress: Subject<BackupRepository.Progress> =
            BehaviorSubject.createDefault(BackupRepository.Progress.Idle())

    @Volatile private var stopFlag: Boolean = false

    override fun setBackupDirectory(uri: String?) {
        prefs.backupDirectoryUri.set(uri.orEmpty())
    }

    override fun hasCustomBackupDirectory(): Boolean = prefs.backupDirectoryUri.get().isNotBlank()

    override fun performBackup() {
        // If a backup or restore is already running, don't do anything
        if (isBackupOrRestoreRunning()) return

        var messageCount = 0
        var blockedNumbers: List<String> = listOf()
        var blockedConversations: List<BackupBlockedConversation> = listOf()
        var namedConversations: List<BackupNamedConversation> = listOf()

        // Map all the messages and blocked state while the Realm objects are valid.
        val backupMessages = Realm.getDefaultInstance().use { realm ->
            // Get the messages from realm
            val messages = realm.where(Message::class.java).sort("date").findAll().createSnapshot()
            messageCount = messages.size
            blockedNumbers = realm.where(BlockedNumber::class.java).findAll().map { it.address }
            blockedConversations = realm.where(Conversation::class.java)
                    .equalTo("blocked", true)
                    .findAll()
                    .map { conversation ->
                        BackupBlockedConversation(
                                addresses = conversation.recipients.map { it.address },
                                blockingClient = conversation.blockingClient,
                                blockReason = conversation.blockReason
                        )
                    }
            namedConversations = realm.where(Conversation::class.java)
                    .findAll()
                    .filter { conversation -> conversation.name.isNotBlank() && conversation.recipients.isNotEmpty() }
                    .map { conversation ->
                        BackupNamedConversation(
                                addresses = conversation.recipients.map { it.address },
                                name = conversation.name
                        )
                    }

            // Map the messages to the new format
            messages.mapIndexed { index, message ->
                // Update the progress
                backupProgress.onNext(BackupRepository.Progress.Running(messageCount, index))
                messageToBackupMessage(message)
            }
        }

        // Update the status, and set the progress to be indeterminate since we can no longer calculate progress
        backupProgress.onNext(BackupRepository.Progress.Saving())

        // Convert the data to json
        val adapter = moshi.adapter(Backup::class.java).indent("\t")
        val json = adapter.toJson(Backup(
                messageCount = messageCount,
                messages = backupMessages,
                blockedNumbers = blockedNumbers,
                blockedConversations = blockedConversations,
                blockedKeywords = prefs.blockedKeywords.get().toList(),
                blockedNumberCount = blockedNumbers.size,
                blockedConversationCount = blockedConversations.size,
                blockedKeywordCount = prefs.blockedKeywords.get().size,
                namedConversations = namedConversations
        )).toByteArray()

        val saved = try {
            val backupDate = PersianDate(System.currentTimeMillis())
            val jalaliDate = String.format(
                    Locale.US,
                    "%04d-%02d-%02d",
                    backupDate.getShYear(),
                    backupDate.getShMonth(),
                    backupDate.getShDay()
            )
            val directoryUri = prefs.backupDirectoryUri.get().takeIf { it.isNotBlank() }?.let { Uri.parse(it) }
            val fileName = getUniqueBackupFileName(directoryUri, jalaliDate)
            if (directoryUri == null) {
                val dir = backupDirectory
                if (!dir.isDirectory && !dir.mkdirs()) {
                    throw IOException("Unable to create backup directory: ${dir.path}")
                }
                File(dir, fileName).outputStream().use { it.write(json) }
            } else {
                val parentUri = DocumentsContract.buildDocumentUriUsingTree(
                        directoryUri,
                        DocumentsContract.getTreeDocumentId(directoryUri)
                )
                val backupUri = DocumentsContract.createDocument(
                        context.contentResolver,
                        parentUri,
                        "application/json",
                        fileName
                ) ?: throw IOException("Unable to create backup in selected folder")
                context.contentResolver.openOutputStream(backupUri, "wt")?.use { it.write(json) }
                        ?: throw IOException("Unable to open backup output stream")
            }
            true
        } catch (e: Exception) {
            Timber.w(e)
            false
        }

        // Report success only after the file has actually been written.
        backupProgress.onNext(if (saved) BackupRepository.Progress.Finished() else BackupRepository.Progress.Failed())
        Timer().schedule(1000) { backupProgress.onNext(BackupRepository.Progress.Idle()) }
    }

    private fun getUniqueBackupFileName(directoryUri: Uri?, jalaliDate: String): String {
        val existingNames = if (directoryUri == null) {
            backupDirectory.list()?.toSet().orEmpty()
        } else {
            val treeId = DocumentsContract.getTreeDocumentId(directoryUri)
            val childrenUri = DocumentsContract.buildChildDocumentsUriUsingTree(directoryUri, treeId)
            val names = mutableSetOf<String>()
            context.contentResolver.query(
                    childrenUri,
                    arrayOf(DocumentsContract.Document.COLUMN_DISPLAY_NAME),
                    null,
                    null,
                    null
            )?.use { cursor ->
                val nameColumn = cursor.getColumnIndexOrThrow(DocumentsContract.Document.COLUMN_DISPLAY_NAME)
                while (cursor.moveToNext()) {
                    cursor.getString(nameColumn)?.let(names::add)
                }
            }
            names
        }

        val baseName = "Backup SMS ($jalaliDate).json"
        if (baseName !in existingNames) return baseName

        var index = 2
        var candidate: String
        do {
            candidate = "Backup ($index) SMS ($jalaliDate).json"
            index++
        } while (candidate in existingNames)
        return candidate
    }

    private fun messageToBackupMessage(message: Message): BackupMessage = BackupMessage(
            type = message.boxId,
            address = message.address,
            date = message.date,
            dateSent = message.dateSent,
            read = message.read,
            status = message.deliveryStatus,
            body = message.body,
            protocol = 0,
            serviceCenter = null,
            locked = message.locked,
            subId = message.subId
    )

    override fun getBackupProgress(): Observable<BackupRepository.Progress> = backupProgress

    override fun getBackups(): Observable<List<BackupFile>> =
            Observable.interval(0, 5, TimeUnit.SECONDS, Schedulers.io())
                    .map {
                        val files = backupDirectory.listFiles()
                                ?.filter { file -> file.isFile && file.name.endsWith(".json") }
                                ?.mapNotNull(::readBackupFile)
                                .orEmpty()
                        val selected = prefs.backupDirectoryUri.get()
                                .takeIf { it.isNotBlank() }
                                ?.let { uri -> readBackupTree(Uri.parse(uri)) }
                                .orEmpty()
                        (files + selected).distinctBy { it.path }.sortedByDescending { it.date }
                    }
                    .subscribeOn(Schedulers.io())
                    .observeOn(Schedulers.computation())

    private fun readBackupFile(file: File): BackupFile? =
            readBackupMetadata(file.path, file.lastModified(), file.length()) { file.inputStream() }

    private fun readBackupMetadata(
            path: String,
            date: Long,
            size: Long,
            openStream: () -> InputStream?
    ): BackupFile? {
        val input = tryOrNull(false) { openStream() } ?: return null
        val backup = tryOrNull(false) {
            input.source().buffer().use(moshi.adapter(BackupMetadata::class.java)::fromJson)
        } ?: return null

        return BackupFile(
                path,
                date,
                backup.messageCount,
                size,
                backup.blockedNumberCount,
                backup.blockedConversationCount,
                backup.blockedKeywordCount
        )
    }

    private fun readBackupTree(treeUri: Uri): List<BackupFile> {
        val treeId = tryOrNull(false) { DocumentsContract.getTreeDocumentId(treeUri) } ?: return emptyList()
        val childrenUri = DocumentsContract.buildChildDocumentsUriUsingTree(treeUri, treeId)
        val files = mutableListOf<BackupFile>()
        try {
            context.contentResolver.query(
                    childrenUri,
                    arrayOf(
                            DocumentsContract.Document.COLUMN_DOCUMENT_ID,
                            DocumentsContract.Document.COLUMN_DISPLAY_NAME,
                            DocumentsContract.Document.COLUMN_LAST_MODIFIED,
                            DocumentsContract.Document.COLUMN_SIZE
                    ),
                    null,
                    null,
                    null
            )?.use { cursor ->
                val idColumn = cursor.getColumnIndexOrThrow(DocumentsContract.Document.COLUMN_DOCUMENT_ID)
                val nameColumn = cursor.getColumnIndexOrThrow(DocumentsContract.Document.COLUMN_DISPLAY_NAME)
                val dateColumn = cursor.getColumnIndexOrThrow(DocumentsContract.Document.COLUMN_LAST_MODIFIED)
                val sizeColumn = cursor.getColumnIndexOrThrow(DocumentsContract.Document.COLUMN_SIZE)
                while (cursor.moveToNext()) {
                    val name = cursor.getString(nameColumn) ?: continue
                    if (!name.endsWith(".json", ignoreCase = true)) continue
                    val documentUri = DocumentsContract.buildDocumentUriUsingTree(treeUri, cursor.getString(idColumn))
                    val date = if (cursor.isNull(dateColumn)) 0L else cursor.getLong(dateColumn)
                    val size = if (cursor.isNull(sizeColumn)) 0L else cursor.getLong(sizeColumn)
                    readBackupMetadata(documentUri.toString(), date, size) {
                        context.contentResolver.openInputStream(documentUri)
                    }?.let(files::add)
                }
            }
        } catch (e: Exception) {
            Timber.w(e)
        }
        return files
    }

    override fun performRestore(filePath: String) {
        // If a backupFile or restore is already running, don't do anything
        if (isBackupOrRestoreRunning()) return

        restoreProgress.onNext(BackupRepository.Progress.Parsing())

        val backupInput = if (filePath.startsWith("content://")) {
            context.contentResolver.openInputStream(Uri.parse(filePath))
        } else {
            File(filePath).inputStream()
        } ?: throw IOException("Unable to open backup")
        val backup = backupInput.source().buffer().use { source ->
            moshi.adapter(Backup::class.java).fromJson(source)
        }

        val messageCount = backup?.messages?.size ?: 0
        var errorCount = 0

        backup?.messages?.forEachIndexed { index, message ->
            if (stopFlag) {
                stopFlag = false
                restoreProgress.onNext(BackupRepository.Progress.Idle())
                return
            }

            // Update the progress
            restoreProgress.onNext(BackupRepository.Progress.Running(messageCount, index))

            try {
                val values = contentValuesOf(
                        Telephony.Sms.TYPE to message.type,
                        Telephony.Sms.ADDRESS to message.address,
                        Telephony.Sms.DATE to message.date,
                        Telephony.Sms.DATE_SENT to message.dateSent,
                        Telephony.Sms.READ to message.read,
                        Telephony.Sms.SEEN to 1,
                        Telephony.Sms.STATUS to message.status,
                        Telephony.Sms.BODY to message.body,
                        Telephony.Sms.PROTOCOL to message.protocol,
                        Telephony.Sms.SERVICE_CENTER to message.serviceCenter,
                        Telephony.Sms.LOCKED to message.locked
                )

                if (prefs.canUseSubId.get()) {
                    values.put(Telephony.Sms.SUBSCRIPTION_ID, message.subId)
                }

                context.contentResolver.insert(Telephony.Sms.CONTENT_URI, values)
            } catch (e: Exception) {
                Timber.w(e)
                errorCount++
            }
        }

        if (errorCount > 0) {
            Timber.w(Exception("Failed to backup $errorCount/$messageCount messages"))
        }

        // Sync the messages
        restoreProgress.onNext(BackupRepository.Progress.Syncing())
        syncRepo.syncMessages()

        // Restore custom conversation titles using recipient addresses, since thread IDs may differ on another device.
        backup?.namedConversations?.forEach { namedConversation ->
            if (namedConversation.addresses.isNotEmpty() && namedConversation.name.isNotBlank()) {
                try {
                    conversationRepo.getThreadId(namedConversation.addresses)?.let { threadId ->
                        conversationRepo.setConversationName(threadId, namedConversation.name)
                    }
                } catch (e: Exception) {
                    Timber.w(e, "Unable to restore a conversation title")
                }
            }
        }

        // Restore the PRSMS block list and reapply blocked state to conversations.
        backup?.blockedKeywords?.takeIf { it.isNotEmpty() }?.let { keywords ->
            prefs.blockedKeywords.set(prefs.blockedKeywords.get() + keywords)
        }
        backup?.blockedNumbers?.takeIf { it.isNotEmpty() }?.let { addresses ->
            blockingRepo.blockNumber(*addresses.toTypedArray())
        }
        backup?.blockedConversations?.forEach { blockedConversation ->
            val threadId = conversationRepo.getThreadId(blockedConversation.addresses)
            if (threadId != null) {
                conversationRepo.markBlocked(
                        listOf(threadId),
                        blockedConversation.blockingClient ?: 0,
                        blockedConversation.blockReason
                )
            }
        }

        // Mark the task finished, and set it as Idle a second later
        restoreProgress.onNext(BackupRepository.Progress.Finished())
        Timer().schedule(1000) { restoreProgress.onNext(BackupRepository.Progress.Idle()) }
    }

    override fun stopRestore() {
        stopFlag = true
    }

    override fun getRestoreProgress(): Observable<BackupRepository.Progress> = restoreProgress

    private fun isBackupOrRestoreRunning(): Boolean {
        return backupProgress.blockingFirst().running || restoreProgress.blockingFirst().running
    }

}