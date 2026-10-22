@file:Suppress("DEPRECATION")

package com.raw2dng

import android.app.Instrumentation
import android.content.ClipData
import android.content.ComponentName
import android.content.Intent
import android.net.Uri
import android.os.SystemClock
import android.test.InstrumentationTestCase
import android.widget.TextView

class SharingIntentTest : InstrumentationTestCase() {
    fun testRepeatedSendAppendsClipDataUriAndKeepsGrantsAcrossRecreation() {
        val instrumentation = instrumentation
        val targetContext = instrumentation.targetContext
        val senderContext = instrumentation.context
        val firstUri = photoUri("first.CR2")
        val secondUri = photoUri("second.CR2")
        var activity: MainActivity? = null
        var recreated: MainActivity? = null
        var duplicate: MainActivity? = null
        var duplicateActivityMonitor: Instrumentation.ActivityMonitor? = null
        var recreationMonitor: Instrumentation.ActivityMonitor? = null

        try {
            assertCannotRead(targetContext, firstUri)
            assertCannotRead(targetContext, secondUri)
            activity = startShare(instrumentation, shareIntent(
                targetContext,
                Intent.ACTION_SEND,
                streams = listOf(firstUri),
                clipUris = listOf(firstUri)
            ))
            assertQueueCount(activity, 1)
            assertCanRead(activity, firstUri)

            assertCannotRead(targetContext, secondUri)
            val duplicateMonitor = instrumentation.addMonitor(MainActivity::class.java.name, null, false)
            duplicateActivityMonitor = duplicateMonitor
            launchThroughSender(senderContext, shareIntent(
                targetContext,
                Intent.ACTION_SEND,
                clipUris = listOf(secondUri)
            ))
            duplicate = duplicateMonitor.waitForActivityWithTimeout(1_000) as? MainActivity
            assertNull(
                "A repeated share should reach the existing MainActivity instance",
                duplicate
            )
            assertQueueCount(activity, 2)
            assertCanRead(activity, firstUri)
            assertCanRead(activity, secondUri)

            val recreateMonitor = instrumentation.addMonitor(MainActivity::class.java.name, null, false)
            recreationMonitor = recreateMonitor
            instrumentation.runOnMainSync { activity.recreate() }
            recreated = recreateMonitor.waitForActivityWithTimeout(5_000) as? MainActivity
            assertNotNull("Activity recreation should create a replacement MainActivity", recreated)
            assertQueueCount(recreated!!, 2)
            assertCanRead(recreated!!, firstUri)
            assertCanRead(recreated!!, secondUri)
        } finally {
            duplicateActivityMonitor?.let(instrumentation::removeMonitor)
            recreationMonitor?.let(instrumentation::removeMonitor)
            val activitiesToFinish = listOfNotNull(recreated, duplicate, activity).distinct()
            instrumentation.runOnMainSync { activitiesToFinish.forEach(MainActivity::finish) }
        }
    }

    fun testSendMultipleAndViewCollectStreamsDataAndClipData() {
        val instrumentation = instrumentation
        val targetContext = instrumentation.targetContext
        val senderContext = instrumentation.context
        val firstUri = photoUri("multiple-one.CR2")
        val secondUri = photoUri("multiple-two.CR2")
        val thirdUri = photoUri("view-data.CR2")
        val fourthUri = photoUri("view-clip.CR2")
        var activity: MainActivity? = null
        var duplicate: MainActivity? = null
        var duplicateActivityMonitor: Instrumentation.ActivityMonitor? = null

        try {
            listOf(firstUri, secondUri, thirdUri, fourthUri).forEach { assertCannotRead(targetContext, it) }
            activity = startShare(instrumentation, shareIntent(
                targetContext,
                Intent.ACTION_SEND_MULTIPLE,
                streams = listOf(firstUri),
                clipUris = listOf(firstUri, secondUri)
            ))
            assertQueueCount(activity, 2)
            assertCanRead(activity, firstUri)
            assertCanRead(activity, secondUri)

            val duplicateMonitor = instrumentation.addMonitor(MainActivity::class.java.name, null, false)
            duplicateActivityMonitor = duplicateMonitor
            launchThroughSender(senderContext, shareIntent(
                targetContext,
                Intent.ACTION_VIEW,
                dataUri = thirdUri,
                clipUris = listOf(fourthUri)
            ))
            duplicate = duplicateMonitor.waitForActivityWithTimeout(1_000) as? MainActivity
            assertNull("VIEW should also be delivered to the existing MainActivity instance", duplicate)
            assertQueueCount(activity, 4)
            listOf(firstUri, secondUri, thirdUri, fourthUri).forEach { assertCanRead(activity, it) }
        } finally {
            duplicateActivityMonitor?.let(instrumentation::removeMonitor)
            val activitiesToFinish = listOfNotNull(duplicate, activity).distinct()
            instrumentation.runOnMainSync { activitiesToFinish.forEach(MainActivity::finish) }
        }
    }

    private fun shareIntent(
        targetContext: android.content.Context,
        action: String,
        streams: List<Uri> = emptyList(),
        clipUris: List<Uri> = emptyList(),
        dataUri: Uri? = null
    ): Intent = Intent(action).apply {
        component = ComponentName(targetContext.packageName, MainActivity::class.java.name)
        if (dataUri == null) setType(MIME_TYPE) else setDataAndType(dataUri, MIME_TYPE)
        when (action) {
            Intent.ACTION_SEND -> streams.firstOrNull()?.let { putExtra(Intent.EXTRA_STREAM, it) }
            Intent.ACTION_SEND_MULTIPLE -> putParcelableArrayListExtra(Intent.EXTRA_STREAM, ArrayList(streams))
        }
        clipUris.firstOrNull()?.let { first ->
            clipData = ClipData.newRawUri("Shared RAW photos", first).apply {
                clipUris.drop(1).forEach { addItem(ClipData.Item(it)) }
            }
        }
        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_GRANT_READ_URI_PERMISSION)
    }

    private fun startShare(instrumentation: Instrumentation, outgoing: Intent): MainActivity {
        val monitor = instrumentation.addMonitor(MainActivity::class.java.name, null, false)
        try {
            launchThroughSender(instrumentation.context, outgoing)
            val activity = monitor.waitForActivityWithTimeout(5_000) as? MainActivity
            assertNotNull("Sender activity should launch MainActivity", activity)
            return activity!!
        } finally {
            instrumentation.removeMonitor(monitor)
        }
    }

    private fun launchThroughSender(senderContext: android.content.Context, outgoing: Intent) {
        val senderIntent = Intent().apply {
            component = ComponentName(senderContext.packageName, SharingIntentSenderActivity::class.java.name)
            putExtra(SharingIntentSenderActivity.EXTRA_OUTGOING_INTENT, outgoing)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        senderContext.startActivity(senderIntent)
    }

    private fun assertQueueCount(activity: MainActivity, expected: Int) {
        val expectedText = activity.getString(R.string.files_count, expected)
        var actualText: String? = null
        val deadline = SystemClock.uptimeMillis() + 5_000
        do {
            instrumentation.runOnMainSync {
                actualText = activity.findViewById<TextView>(R.id.filesSectionTitle).text.toString()
            }
            if (actualText == expectedText) return
            SystemClock.sleep(25)
        } while (SystemClock.uptimeMillis() < deadline)
        assertEquals("Queue count did not reach $expected", expectedText, actualText)
    }

    private fun assertCanRead(activity: MainActivity, uri: Uri) {
        var readable = false
        instrumentation.runOnMainSync {
            activity.contentResolver.openFileDescriptor(uri, "r")?.use { readable = true }
        }
        assertTrue("The activity should retain read access to $uri", readable)
    }

    private fun assertCannotRead(targetContext: android.content.Context, uri: Uri) {
        val denied = try {
            targetContext.contentResolver.openFileDescriptor(uri, "r")?.use { false } ?: false
        } catch (_: SecurityException) {
            true
        }
        assertTrue("The target app should need an intent grant to read $uri", denied)
    }

    private fun photoUri(name: String): Uri = Uri.parse(
        "content://com.franciswibisono.raw2dng2.test.sharingprovider/photos/" +
            name.removeSuffix(".CR2") + "-${SystemClock.uptimeMillis()}.CR2"
    )

    companion object {
        private const val MIME_TYPE = "image/x-canon-cr2"
    }
}
