/*
 * Copyright (C) 2026 Afshin Irani
 *
 * This file is part of PRSMS and is licensed under the GNU General Public License version 3.
 */
package com.freetux.PRSMS.receiver

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.freetux.PRSMS.repository.BackupRepository
import com.freetux.PRSMS.util.Preferences
import dagger.android.AndroidInjection
import timber.log.Timber
import java.util.concurrent.Executors
import javax.inject.Inject

class AutomaticBackupReceiver : BroadcastReceiver() {

    @Inject lateinit var preferences: Preferences
    @Inject lateinit var backupRepository: BackupRepository

    override fun onReceive(context: Context, intent: Intent?) {
        AndroidInjection.inject(this, context)
        if (!preferences.automaticBackup.get()) return

        val result = goAsync()
        backupExecutor.execute {
            try {
                backupRepository.performBackup()
            } catch (e: Exception) {
                Timber.e(e, "Automatic backup failed")
            } finally {
                result.finish()
            }
        }
    }

    companion object {
        private val backupExecutor = Executors.newSingleThreadExecutor()
    }

}
