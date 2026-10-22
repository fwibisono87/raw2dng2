package com.raw2dng

import android.app.Activity
import android.content.Intent
import android.os.Bundle

class SharingIntentSenderActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        @Suppress("DEPRECATION")
        val outgoing = intent.getParcelableExtra<Intent>(EXTRA_OUTGOING_INTENT)
        startActivity(requireNotNull(outgoing) { "Missing outgoing intent" })
        finish()
    }

    companion object {
        const val EXTRA_OUTGOING_INTENT = "outgoingIntent"
    }
}
