package com.raw2dng

import android.graphics.Bitmap
import android.net.Uri
import android.os.Handler
import android.os.Looper
import java.util.concurrent.CopyOnWriteArraySet

data class QueueItem(
    val uri: Uri,
    val name: String,
    var make: String = "",
    var model: String = "",
    var error: String? = null,
    var status: String = "Pending",
    var hasEmbedded: Boolean = false,
    var datetime: String? = null,
    var thumbnail: Bitmap? = null,
    var sourceOverride: Int? = null
)

data class ConversionEvent(
    val uri: String? = null,
    val status: String = "",
    val outputName: String? = null,
    val current: Int = 0,
    val total: Int = 0,
    val finished: Boolean = false,
    val successCount: Int = 0,
    val failureCount: Int = 0
)

object ConversionEvents {
    fun interface Listener { fun onConversionEvent(event: ConversionEvent) }

    private val listeners = CopyOnWriteArraySet<Listener>()
    private val main = Handler(Looper.getMainLooper())

    fun add(listener: Listener) { listeners.add(listener) }
    fun remove(listener: Listener) { listeners.remove(listener) }
    fun publish(event: ConversionEvent) {
        main.post { listeners.forEach { it.onConversionEvent(event) } }
    }
}
