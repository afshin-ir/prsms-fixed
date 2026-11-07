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

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import com.bluelinelabs.conductor.Conductor
import com.bluelinelabs.conductor.Router
import com.bluelinelabs.conductor.RouterTransaction
import com.freetux.PRSMS.R
import com.freetux.PRSMS.common.base.QkThemedActivity
import dagger.android.AndroidInjection
import android.widget.Toast
import kotlinx.android.synthetic.main.container_activity.*

class ConversationInfoActivity : QkThemedActivity() {

    private lateinit var router: Router
    private var senderPhotoCallback: ((Uri) -> Unit)? = null

    fun chooseSenderPhoto(onSelected: (Uri) -> Unit) {
        senderPhotoCallback = onSelected
        val intent = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = "image/*"
        }
        startActivityForResult(intent, SENDER_PHOTO_REQUEST_CODE)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        AndroidInjection.inject(this)
        super.onCreate(savedInstanceState)
        setContentView(R.layout.container_activity)

        router = Conductor.attachRouter(this, container, savedInstanceState)
        if (!router.hasRootController()) {
            val threadId = intent.extras?.getLong("threadId") ?: 0L
            router.setRoot(RouterTransaction.with(ConversationInfoController(threadId)))
        }
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode != SENDER_PHOTO_REQUEST_CODE) return

        val callback = senderPhotoCallback
        senderPhotoCallback = null
        val uri = data?.data
        if (resultCode == RESULT_OK && uri != null) {
            callback?.invoke(uri)
        } else if (resultCode == RESULT_OK) {
            Toast.makeText(this, R.string.info_photo_error, Toast.LENGTH_SHORT).show()
        }
    }

    override fun onBackPressed() {
        if (!router.handleBack()) {
            super.onBackPressed()
        }
    }

    companion object {
        private const val SENDER_PHOTO_REQUEST_CODE = 8097
    }

}