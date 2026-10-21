package com.raw2dng

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.ContentValues
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.Uri
import android.os.Build
import android.os.IBinder
import android.os.ParcelFileDescriptor
import android.provider.DocumentsContract
import android.provider.MediaStore
import android.util.Log
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.Executors

class ConversionService : Service() {
    private val executor = Executors.newSingleThreadExecutor()
    @Volatile private var cancelRequested = false
    @Volatile private var working = false
    private lateinit var notifications: NotificationManager

    override fun onCreate() {
        super.onCreate()
        notifications = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
        if (Build.VERSION.SDK_INT >= 26) {
            notifications.createNotificationChannel(
                NotificationChannel(CHANNEL_ID, "Raw conversions", NotificationManager.IMPORTANCE_LOW)
            )
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_CANCEL) {
            cancelRequested = true
            ConversionEvents.publish(ConversionEvent(status = "Finishing current file…"))
            return START_NOT_STICKY
        }
        if (working) return START_NOT_STICKY

        val uris = intent?.getStringArrayListExtra(EXTRA_URIS) ?: arrayListOf()
        val dates = intent?.getStringArrayListExtra(EXTRA_DATES) ?: arrayListOf()
        if (uris.isEmpty()) {
            stopSelf(startId)
            return START_NOT_STICKY
        }
        cancelRequested = false
        working = true
        isActive = true
        startAsForeground("Starting conversion…", 0, uris.size)
        val options = intent?.getBundleExtra(EXTRA_OPTIONS) ?: android.os.Bundle()
        executor.execute { process(uris, dates, options, startId) }
        return START_NOT_STICKY
    }

    private fun startAsForeground(text: String, current: Int, total: Int) {
        val notification = progressNotification(text, current, total)
        if (Build.VERSION.SDK_INT >= 35) {
            startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROCESSING)
        } else if (Build.VERSION.SDK_INT >= 29) {
            startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    @Suppress("DEPRECATION")
    private fun process(uris: ArrayList<String>, dates: ArrayList<String>, options: android.os.Bundle, startId: Int) {
        val isJpeg = options.getInt(OPTION_FORMAT, 0) == 1
        val embed = options.getBoolean(OPTION_EMBED, false)
        val globalSource = options.getInt(OPTION_SOURCE, 0)
        val longEdge = options.getInt(OPTION_LONG_EDGE, 0)
        val maxBytes = options.getInt(OPTION_MAX_BYTES, 0)
        val quality = options.getInt(OPTION_QUALITY, 92)
        val folder = options.getString(OPTION_FOLDER)?.let(Uri::parse)
        var succeeded = 0
        var failed = 0

        var completedFiles = 0
        for ((index, uriString) in uris.withIndex()) {
            if (cancelRequested) break
            val uri = Uri.parse(uriString)
            val name = displayName(uri)
            val outputName = outputName(name, isJpeg)
            val current = index + 1
            ConversionEvents.publish(ConversionEvent(uriString, "Converting", current = current, total = uris.size))
            startAsForeground("Converting $current of ${uris.size} – $name", current, uris.size)
            Log.i(TAG, "Starting ${if (isJpeg) "JPEG" else "DNG"} conversion for $name")

            var outputUri: Uri? = null
            var mediaStoreOutput = false
            try {
                val created = createOutput(outputName, isJpeg, folder, dates.getOrNull(index))
                outputUri = created.first
                mediaStoreOutput = created.second
                val result = (contentResolver.openFileDescriptor(uri, "r")
                    ?: error("Could not open input file.")).use { input ->
                    (contentResolver.openFileDescriptor(outputUri, "w")
                        ?: error("Could not open output file.")).use { output ->
                        if (isJpeg) {
                            val source = options.getInt(OPTION_ITEM_SOURCE_PREFIX + uriString, globalSource)
                            Native.toJpeg(input.fd, output.fd, source, longEdge, maxBytes, quality)
                        } else {
                            Native.toDng(input.fd, output.fd, embed)
                        }
                    }
                }
                if (isJpeg) {
                    val json = org.json.JSONObject(result)
                    if (!json.optBoolean("ok", false)) error(json.optString("error", "JPEG conversion failed."))
                } else if (result.isNotEmpty()) {
                    error(result)
                }
                if (mediaStoreOutput && Build.VERSION.SDK_INT >= 29) {
                    val values = ContentValues().apply { put(MediaStore.Images.Media.IS_PENDING, 0) }
                    contentResolver.update(outputUri, values, null, null)
                }
                succeeded++
                completedFiles = index + 1
                ConversionEvents.publish(ConversionEvent(uriString, "Done · $outputName", outputName, current, uris.size))
                Log.i(TAG, "Wrote $outputName")
            } catch (exception: Exception) {
                failed++
                completedFiles = index + 1
                outputUri?.let { runCatching { contentResolver.delete(it, null, null) } }
                val message = exception.message ?: "Conversion failed."
                ConversionEvents.publish(ConversionEvent(uriString, "Error · $message", current = current, total = uris.size))
                Log.e(TAG, "Failed to convert $name: $message", exception)
            }
        }

        val cancelled = cancelRequested
        if (cancelled) {
            uris.drop(completedFiles).forEach { uri ->
                ConversionEvents.publish(ConversionEvent(uri, "Pending"))
            }
        }
        val summary = if (cancelled) "Cancelled · $succeeded done, $failed failed" else "Finished · $succeeded done, $failed failed"
        ConversionEvents.publish(ConversionEvent(status = summary, finished = true, successCount = succeeded, failureCount = failed))
        if (Build.VERSION.SDK_INT >= 24) stopForeground(STOP_FOREGROUND_REMOVE) else stopForeground(true)
        notifications.notify(NOTIFICATION_ID, finalNotification(summary))
        working = false
        isActive = false
        stopSelf(startId)
    }

    private fun createOutput(name: String, jpeg: Boolean, folder: Uri?, dateTime: String?): Pair<Uri, Boolean> {
        val mime = if (jpeg) "image/jpeg" else "image/x-adobe-dng"
        if (folder != null) {
            val parent = DocumentsContract.buildDocumentUriUsingTree(folder, DocumentsContract.getTreeDocumentId(folder))
            val document = DocumentsContract.createDocument(contentResolver, parent, mime, name)
                ?: error("Could not create output in the selected folder.")
            return document to false
        }
        if (Build.VERSION.SDK_INT < 29) error("Choose an output folder before converting on Android 9 and earlier.")

        val values = ContentValues().apply {
            put(MediaStore.Images.Media.DISPLAY_NAME, name)
            put(MediaStore.Images.Media.MIME_TYPE, mime)
            put(MediaStore.Images.Media.RELATIVE_PATH, "${android.os.Environment.DIRECTORY_PICTURES}/Raw2DNG")
            put(MediaStore.Images.Media.IS_PENDING, 1)
            dateTime?.let(::parseDateTaken)?.let { put(MediaStore.Images.Media.DATE_TAKEN, it) }
        }
        val output = contentResolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values)
            ?: error("Could not create an output file in Pictures/Raw2DNG.")
        return output to true
    }

    private fun parseDateTaken(value: String): Long? = runCatching {
        SimpleDateFormat("yyyy:MM:dd HH:mm:ss", Locale.US).parse(value)?.time
    }.getOrNull()

    private fun displayName(uri: Uri): String = runCatching {
        contentResolver.query(uri, arrayOf(android.provider.OpenableColumns.DISPLAY_NAME), null, null, null)?.use { cursor ->
            if (cursor.moveToFirst()) cursor.getString(0) else null
        }
    }.getOrNull() ?: uri.lastPathSegment ?: "raw-file"

    private fun outputName(input: String, jpeg: Boolean): String {
        val base = input.substringBeforeLast('.', input)
        return "$base.${if (jpeg) "jpg" else "dng"}"
    }

    @Suppress("DEPRECATION")
    private fun progressNotification(text: String, current: Int, total: Int): Notification {
        val builder = if (Build.VERSION.SDK_INT >= 26) Notification.Builder(this, CHANNEL_ID) else Notification.Builder(this)
        return builder
            .setSmallIcon(R.drawable.ic_stat_raw2dng)
            .setContentTitle("Raw2DNG")
            .setContentText(text)
            .setProgress(total, current.coerceAtMost(total), false)
            .setContentIntent(appPendingIntent())
            .setOngoing(true)
            .build()
    }

    @Suppress("DEPRECATION")
    private fun finalNotification(text: String): Notification {
        val builder = if (Build.VERSION.SDK_INT >= 26) Notification.Builder(this, CHANNEL_ID) else Notification.Builder(this)
        return builder
            .setSmallIcon(R.drawable.ic_stat_raw2dng)
            .setContentTitle("Raw2DNG")
            .setContentText(text)
            .setContentIntent(appPendingIntent())
            .setAutoCancel(true)
            .build()
    }

    private fun appPendingIntent(): PendingIntent {
        val intent = Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
        val flags = PendingIntent.FLAG_UPDATE_CURRENT or if (Build.VERSION.SDK_INT >= 23) PendingIntent.FLAG_IMMUTABLE else 0
        return PendingIntent.getActivity(this, 0, intent, flags)
    }

    @android.annotation.TargetApi(35)
    override fun onTimeout(startId: Int, fgsType: Int) {
        Log.w(TAG, "Foreground processing time limit reached; finishing the current file")
        cancelRequested = true
        ConversionEvents.publish(ConversionEvent(status = "System time limit reached; stopping after this file"))
    }

    override fun onDestroy() {
        if (!working) isActive = false
        executor.shutdown()
        super.onDestroy()
    }

    companion object {
        @Volatile var isActive = false
            private set
        const val ACTION_START = "com.raw2dng.action.START"
        const val ACTION_CANCEL = "com.raw2dng.action.CANCEL"
        const val EXTRA_URIS = "raw2dng.uris"
        const val EXTRA_DATES = "raw2dng.dates"
        const val EXTRA_OPTIONS = "raw2dng.options"
        const val OPTION_FORMAT = "format"
        const val OPTION_EMBED = "embed"
        const val OPTION_SOURCE = "source"
        const val OPTION_LONG_EDGE = "longEdge"
        const val OPTION_MAX_BYTES = "maxBytes"
        const val OPTION_QUALITY = "quality"
        const val OPTION_FOLDER = "folder"
        const val OPTION_ITEM_SOURCE_PREFIX = "source."
        private const val TAG = "Raw2DNG"
        private const val CHANNEL_ID = "raw2dng_conversion"
        private const val NOTIFICATION_ID = 20
    }
}
