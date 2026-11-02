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
package com.freetux.PRSMS.feature.backup

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.res.ColorStateList
import android.graphics.Typeface
import android.view.View
import androidx.appcompat.app.AlertDialog
import androidx.core.app.ActivityCompat
import androidx.core.view.children
import androidx.core.view.isVisible
import com.jakewharton.rxbinding2.view.clicks
import com.freetux.PRSMS.R
import com.freetux.PRSMS.common.base.QkController
import com.freetux.PRSMS.common.util.DateFormatter
import com.freetux.PRSMS.common.util.extensions.getLabel
import com.freetux.PRSMS.common.util.extensions.setBackgroundTint
import com.freetux.PRSMS.common.util.extensions.setPositiveButton
import com.freetux.PRSMS.common.util.extensions.setTint
import com.freetux.PRSMS.common.widget.PreferenceView
import com.freetux.PRSMS.injection.appComponent
import com.freetux.PRSMS.model.BackupFile
import com.freetux.PRSMS.repository.BackupRepository
import com.freetux.PRSMS.receiver.AutomaticBackupScheduler
import com.freetux.PRSMS.util.Preferences
import io.reactivex.Observable
import io.reactivex.subjects.PublishSubject
import io.reactivex.subjects.Subject
import kotlinx.android.synthetic.main.backup_controller.*
import kotlinx.android.synthetic.main.backup_list_dialog.view.*
import kotlinx.android.synthetic.main.preference_view.view.*
import javax.inject.Inject

class BackupController : QkController<BackupView, BackupState, BackupPresenter>(), BackupView {

    @Inject lateinit var adapter: BackupAdapter
    @Inject lateinit var backupRepo: BackupRepository
    @Inject lateinit var dateFormatter: DateFormatter
    @Inject lateinit var preferences: Preferences
    @Inject override lateinit var presenter: BackupPresenter

    private val activityVisibleSubject: Subject<Unit> = PublishSubject.create()
    private val confirmRestoreSubject: Subject<Unit> = PublishSubject.create()
    private val stopRestoreSubject: Subject<Unit> = PublishSubject.create()

    private val backupFilesDialog by lazy {
        val view = View.inflate(activity, R.layout.backup_list_dialog, null)
                .apply { files.adapter = adapter.apply { emptyView = empty } }

        AlertDialog.Builder(activity!!)
                .setView(view)
                .setCancelable(true)
                .create()
    }

    private val confirmRestoreDialog by lazy {
        AlertDialog.Builder(activity!!)
                .setTitle(R.string.backup_restore_confirm_title)
                .setMessage(R.string.backup_restore_confirm_message)
                .setPositiveButton(R.string.backup_restore_title, confirmRestoreSubject)
                .setNegativeButton(R.string.button_cancel, null)
                .create()
    }

    private val stopRestoreDialog by lazy {
        AlertDialog.Builder(activity!!)
                .setTitle(R.string.backup_restore_stop_title)
                .setMessage(R.string.backup_restore_stop_message)
                .setPositiveButton(R.string.button_stop, stopRestoreSubject)
                .setNegativeButton(R.string.button_cancel, null)
                .create()
    }

    init {
        appComponent.inject(this)
        layoutRes = R.layout.backup_controller
    }

    override fun onAttach(view: View) {
        super.onAttach(view)
        presenter.bindIntents(this)
        setTitle(R.string.backup_title)
        showBackButton(true)
    }

    override fun onViewCreated() {
        super.onViewCreated()

        themedActivity?.colors?.theme()?.let { theme ->
            progressBar.indeterminateTintList = ColorStateList.valueOf(theme.theme)
            progressBar.progressTintList = ColorStateList.valueOf(theme.theme)
            fab.setBackgroundTint(theme.theme)
            fabIcon.setTint(theme.textPrimary)
            fabLabel.setTextColor(theme.textPrimary)
        }

        automaticBackup.checkbox.isChecked = preferences.automaticBackup.get()
        automaticBackup.setOnClickListener {
            val enabled = !preferences.automaticBackup.get()
            preferences.automaticBackup.set(enabled)
            automaticBackup.checkbox.isChecked = enabled
            activity?.let { AutomaticBackupScheduler.schedule(it, enabled) }
        }

        // Make the list titles bold
        linearLayout.children
                .mapNotNull { it as? PreferenceView }
                .map { it.titleView }
                .forEach { it.setTypeface(it.typeface, Typeface.BOLD) }
    }

    override fun onActivityResumed(activity: Activity) {
        super.onActivityResumed(activity)
        activityVisibleSubject.onNext(Unit)
    }

    override fun render(state: BackupState) {
        when {
            state.backupProgress.running -> {
                progressIcon.setImageResource(R.drawable.ic_file_upload_black_24dp)
                progressTitle.setText(R.string.backup_backing_up)
                progressSummary.text = state.backupProgress.getLabel(activity!!)
                progressSummary.isVisible = progressSummary.text.isNotEmpty()
                progressCancel.isVisible = false
                val running = (state.backupProgress as? BackupRepository.Progress.Running)
                progressBar.isVisible = state.backupProgress.indeterminate || running?.max ?: 0 > 0
                progressBar.isIndeterminate = state.backupProgress.indeterminate
                progressBar.max = running?.max ?: 0
                progressBar.progress = running?.count ?: 0
                progress.isVisible = true
                fab.isVisible = false
            }

            state.restoreProgress.running -> {
                progressIcon.setImageResource(R.drawable.ic_file_download_black_24dp)
                progressTitle.setText(R.string.backup_restoring)
                progressSummary.text = state.restoreProgress.getLabel(activity!!)
                progressSummary.isVisible = progressSummary.text.isNotEmpty()
                progressCancel.isVisible = true
                val running = (state.restoreProgress as? BackupRepository.Progress.Running)
                progressBar.isVisible = state.restoreProgress.indeterminate || running?.max ?: 0 > 0
                progressBar.isIndeterminate = state.restoreProgress.indeterminate
                progressBar.max = running?.max ?: 0
                progressBar.progress = running?.count ?: 0
                progress.isVisible = true
                fab.isVisible = false
            }

            else -> {
                progress.isVisible = false
                fab.isVisible = true
            }
        }

        backup.summary = state.lastBackup
        backupLocation.summary = activity?.getString(
                if (backupRepo.hasCustomBackupDirectory()) R.string.backup_location_summary_selected
                else R.string.backup_location_summary_default
        )

        adapter.data = state.backups

        fabIcon.setImageResource(when (state.upgraded) {
            true -> R.drawable.ic_file_upload_black_24dp
            false -> R.drawable.ic_star_black_24dp
        })

        fabLabel.setText(when (state.upgraded) {
            true -> R.string.backup_now
            false -> R.string.title_qksms_plus
        })
    }

    override fun activityVisible(): Observable<*> = activityVisibleSubject

    override fun restoreClicks(): Observable<*> = restore.clicks()

    override fun restoreFileSelected(): Observable<BackupFile> = adapter.backupSelected
            .doOnNext { backupFilesDialog.dismiss() }

    override fun restoreConfirmed(): Observable<*> = confirmRestoreSubject

    override fun stopRestoreClicks(): Observable<*> = progressCancel.clicks()

    override fun stopRestoreConfirmed(): Observable<*> = stopRestoreSubject

    override fun fabClicks(): Observable<*> = fab.clicks()

    override fun backupLocationClicks(): Observable<*> = backupLocation.clicks()

    override fun requestStoragePermission() {
        ActivityCompat.requestPermissions(activity!!, arrayOf(Manifest.permission.WRITE_EXTERNAL_STORAGE), 0)
    }

    override fun selectFile() = backupFilesDialog.show()

    override fun selectBackupFolder() {
        val intent = Intent(Intent.ACTION_OPEN_DOCUMENT_TREE)
                .addFlags(
                        Intent.FLAG_GRANT_READ_URI_PERMISSION or
                                Intent.FLAG_GRANT_WRITE_URI_PERMISSION or
                                Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION
                )
        startActivityForResult(intent, REQUEST_BACKUP_FOLDER)
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode != REQUEST_BACKUP_FOLDER || resultCode != Activity.RESULT_OK) return
        val uri = data?.data ?: return
        val permissionFlags = data.flags and
                (Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
        try {
            activity?.contentResolver?.takePersistableUriPermission(uri, permissionFlags)
            backupRepo.setBackupDirectory(uri.toString())
            backupLocation.summary = activity?.getString(R.string.backup_location_summary_selected)
        } catch (e: SecurityException) {
            backupRepo.setBackupDirectory(null)
            backupLocation.summary = activity?.getString(R.string.backup_location_summary_default)
        }
    }

    override fun confirmRestore() = confirmRestoreDialog.show()

    override fun stopRestore() = stopRestoreDialog.show()


    companion object {
        private const val REQUEST_BACKUP_FOLDER = 4107
    }

}