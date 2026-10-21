package com.raw2dng

import android.Manifest
import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.content.pm.PackageManager
import android.content.res.ColorStateList
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.DocumentsContract
import android.provider.OpenableColumns
import android.text.TextUtils
import android.text.util.Linkify
import android.util.Log
import android.util.LruCache
import android.util.TypedValue
import android.view.LayoutInflater
import android.view.Menu
import android.view.MenuItem
import android.view.View
import android.view.ViewGroup
import android.view.WindowInsets
import android.widget.AdapterView
import android.widget.BaseAdapter
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ListView
import android.widget.ProgressBar
import android.widget.RadioButton
import android.widget.RadioGroup
import android.widget.SeekBar
import android.widget.Spinner
import android.widget.TextView
import android.widget.Toast
import org.json.JSONObject
import java.text.NumberFormat
import java.util.concurrent.Executors
import kotlin.math.pow

class MainActivity : Activity() {
    private val queue = mutableListOf<QueueItem>()
    private val background = Executors.newSingleThreadExecutor()
    private val prefs by lazy { getSharedPreferences("settings", MODE_PRIVATE) }

    private lateinit var root: View
    private lateinit var list: ListView
    private lateinit var header: View
    private lateinit var adapter: QueueAdapter
    private lateinit var emptyState: View
    private lateinit var filesSectionTitle: TextView
    private lateinit var formatGroup: RadioGroup
    private lateinit var formatDng: RadioButton
    private lateinit var formatJpeg: RadioButton
    private lateinit var dngOptions: View
    private lateinit var jpegOptions: View
    private lateinit var embedOriginal: CheckBox
    private lateinit var sourceSpinner: Spinner
    private lateinit var sizeSpinner: Spinner
    private lateinit var customSizeRow: View
    private lateinit var customSize: EditText
    private lateinit var maxSpinner: Spinner
    private lateinit var customMaxRow: View
    private lateinit var customMax: EditText
    private lateinit var qualityLabel: TextView
    private lateinit var qualitySeekBar: SeekBar
    private lateinit var folderLabel: TextView
    private lateinit var changeFolderButton: Button
    private lateinit var useDefaultFolderButton: Button
    private lateinit var finishedSummary: TextView
    private lateinit var folderRequiredHint: TextView
    private lateinit var idleActions: View
    private lateinit var addEmptyButton: Button
    private lateinit var addFilesButton: Button
    private lateinit var convertButton: Button
    private lateinit var progressPanel: View
    private lateinit var progressLabel: TextView
    private lateinit var conversionProgress: ProgressBar
    private lateinit var cancelButton: Button

    private var outputFolder: Uri? = null
    private var running = false
    private var progressCurrent = 0
    private var progressTotal = 0
    private var progressStatus = ""
    private var finishedSummaryText: String? = null

    private val bitmapCache = object : LruCache<String, Bitmap>(24 * 1024 * 1024) {
        override fun sizeOf(key: String, value: Bitmap): Int = value.byteCount
    }

    private val eventListener = ConversionEvents.Listener { event ->
        if (event.uri != null) {
            queue.firstOrNull { it.uri.toString() == event.uri }?.status = event.status
            if (event.status.startsWith("Converting")) {
                running = true
                progressCurrent = event.current
                progressTotal = event.total
                progressStatus = ""
            }
        } else if (event.status.isNotEmpty() && !event.finished) {
            progressStatus = event.status
        }

        if (event.finished) {
            running = false
            progressStatus = ""
            val location = currentOutputLocation()
            val numberFormat = NumberFormat.getIntegerInstance()
            finishedSummaryText = if (event.status.startsWith("Cancelled")) {
                getString(
                    R.string.cancelled_summary,
                    numberFormat.format(event.successCount),
                    numberFormat.format(event.failureCount),
                    location
                )
            } else {
                getString(
                    R.string.conversion_summary,
                    numberFormat.format(event.successCount),
                    numberFormat.format(event.failureCount),
                    location
                )
            }
        }
        refreshQueueUi()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        title = getString(R.string.app_name)
        migrateMaximumSizePreference()
        outputFolder = prefs.getString(KEY_FOLDER, null)?.let(Uri::parse)
        running = ConversionService.isActive
        buildUi()
        ConversionEvents.add(eventListener)
        if (savedInstanceState != null) {
            savedInstanceState.getStringArrayList(STATE_URIS)?.map(Uri::parse)?.let(::addUris)
        } else {
            handleIncomingIntent(intent)
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleIncomingIntent(intent)
    }

    override fun onDestroy() {
        ConversionEvents.remove(eventListener)
        background.shutdown()
        super.onDestroy()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        outState.putStringArrayList(STATE_URIS, ArrayList(queue.map { it.uri.toString() }))
        super.onSaveInstanceState(outState)
    }

    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        menuInflater.inflate(R.menu.main, menu)
        return true
    }

    override fun onPrepareOptionsMenu(menu: Menu): Boolean {
        menu.findItem(R.id.menuClearQueue)?.isEnabled = queue.isNotEmpty() && !running
        return super.onPrepareOptionsMenu(menu)
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean = when (item.itemId) {
        R.id.menuClearQueue -> {
            clearQueue()
            true
        }
        R.id.menuAbout -> {
            showAbout()
            true
        }
        else -> super.onOptionsItemSelected(item)
    }

    private fun buildUi() {
        setContentView(R.layout.activity_main)
        root = findViewById(R.id.mainRoot)
        list = findViewById(R.id.queueList)

        finishedSummary = findViewById(R.id.finishedSummary)
        folderRequiredHint = findViewById(R.id.folderRequiredHint)
        idleActions = findViewById(R.id.idleActions)
        addEmptyButton = findViewById(R.id.addEmptyButton)
        addFilesButton = findViewById(R.id.addFilesButton)
        convertButton = findViewById(R.id.convertButton)
        progressPanel = findViewById(R.id.progressPanel)
        progressLabel = findViewById(R.id.progressLabel)
        conversionProgress = findViewById(R.id.conversionProgress)
        cancelButton = findViewById(R.id.cancelButton)

        header = LayoutInflater.from(this).inflate(R.layout.view_settings_header, list, false)
        list.addHeaderView(header, null, false)
        adapter = QueueAdapter()
        list.adapter = adapter
        list.setOnItemClickListener { _, _, position, _ ->
            val queuePosition = position - list.headerViewsCount
            if (queuePosition in queue.indices && !running) showItem(queue[queuePosition])
        }
        list.setOnItemLongClickListener { _, _, position, _ ->
            val queuePosition = position - list.headerViewsCount
            if (queuePosition in queue.indices && !running) removeItem(queue[queuePosition])
            true
        }

        addEmptyButton.setOnClickListener { pickFiles() }
        addFilesButton.setOnClickListener { pickFiles() }
        convertButton.setOnClickListener { if (pendingItems().isEmpty() && queue.any(::isDone)) clearQueue() else startConversion() }
        cancelButton.setOnClickListener { cancelConversion() }
        applyPrimaryButtonStyle(addEmptyButton)
        applyPrimaryButtonStyle(convertButton)

        bindSettingsHeader()
        updateFolderLabel()
        updateFormatPanels()
        refreshQueueUi()

        if (Build.VERSION.SDK_INT >= 35) {
            val left = (root as ViewGroup).paddingLeft
            val top = (root as ViewGroup).paddingTop
            val right = (root as ViewGroup).paddingRight
            val bottom = (root as ViewGroup).paddingBottom
            root.setOnApplyWindowInsetsListener { view, insets ->
                val bars = insets.getInsets(WindowInsets.Type.systemBars())
                view.setPadding(left + bars.left, top + bars.top, right + bars.right, bottom + bars.bottom)
                insets
            }
            root.requestApplyInsets()
        }
    }

    private fun bindSettingsHeader() {
        formatGroup = header.findViewById(R.id.formatGroup)
        formatDng = header.findViewById(R.id.formatDng)
        formatJpeg = header.findViewById(R.id.formatJpeg)
        dngOptions = header.findViewById(R.id.dngOptions)
        jpegOptions = header.findViewById(R.id.jpegOptions)
        embedOriginal = header.findViewById(R.id.embedOriginal)
        sourceSpinner = header.findViewById(R.id.sourceSpinner)
        sizeSpinner = header.findViewById(R.id.sizeSpinner)
        customSizeRow = header.findViewById(R.id.customSizeRow)
        customSize = header.findViewById(R.id.customSize)
        maxSpinner = header.findViewById(R.id.maxSpinner)
        customMaxRow = header.findViewById(R.id.customMaxRow)
        customMax = header.findViewById(R.id.customMax)
        qualityLabel = header.findViewById(R.id.qualityLabel)
        qualitySeekBar = header.findViewById(R.id.qualitySeekBar)
        folderLabel = header.findViewById(R.id.folderLabel)
        changeFolderButton = header.findViewById(R.id.changeFolderButton)
        useDefaultFolderButton = header.findViewById(R.id.useDefaultFolderButton)
        emptyState = header.findViewById(R.id.emptyState)
        filesSectionTitle = header.findViewById(R.id.filesSectionTitle)

        val format = prefs.getInt(KEY_FORMAT, FORMAT_DNG)
        formatGroup.check(if (format == FORMAT_JPEG) R.id.formatJpeg else R.id.formatDng)
        embedOriginal.isChecked = prefs.getBoolean(KEY_EMBED, false)
        sourceSpinner.setSelection(prefs.getInt(KEY_SOURCE, 0).coerceIn(0, 1))
        sizeSpinner.setSelection(prefs.getInt(KEY_SIZE_PRESET, 0).coerceIn(0, 4))
        customSize.setText(getString(R.string.numeric_value, prefs.getInt(KEY_CUSTOM_SIZE, 3000)))
        maxSpinner.setSelection(prefs.getInt(KEY_MAX_PRESET, 0).coerceIn(0, 4))
        customMax.setText(getString(R.string.numeric_value, prefs.getInt(KEY_CUSTOM_MAX, 1000)))
        qualitySeekBar.progress = (prefs.getInt(KEY_QUALITY, 92).coerceIn(60, 100) - QUALITY_MIN)
        updateQualityLabel(qualitySeekBar.progress + QUALITY_MIN)

        formatGroup.setOnCheckedChangeListener { _, checkedId ->
            val format = if (checkedId == R.id.formatJpeg) FORMAT_JPEG else FORMAT_DNG
            prefs.edit().putInt(KEY_FORMAT, format).apply()
            queue.filter(::isDone).forEach { it.status = "Pending" }
            updateFormatPanels()
            clearFinishedSummary()
            refreshQueueUi()
        }
        embedOriginal.setOnCheckedChangeListener { _, checked ->
            prefs.edit().putBoolean(KEY_EMBED, checked).apply()
            clearFinishedSummary()
        }
        sourceSpinner.onItemSelectedListener = onSelection { position ->
            prefs.edit().putInt(KEY_SOURCE, position).apply()
            clearFinishedSummary()
        }
        sizeSpinner.onItemSelectedListener = onSelection { position ->
            prefs.edit().putInt(KEY_SIZE_PRESET, position).apply()
            customSizeRow.visibility = if (position == SIZE_CUSTOM) View.VISIBLE else View.GONE
            clearFinishedSummary()
        }
        maxSpinner.onItemSelectedListener = onSelection { position ->
            prefs.edit().putInt(KEY_MAX_PRESET, position).apply()
            customMaxRow.visibility = if (position == MAX_CUSTOM) View.VISIBLE else View.GONE
            clearFinishedSummary()
        }
        customSize.addTextChangedListener(simpleWatcher { text ->
            prefs.edit().putInt(KEY_CUSTOM_SIZE, text.toIntOrNull() ?: 0).apply()
            clearFinishedSummary()
        })
        customMax.addTextChangedListener(simpleWatcher { text ->
            prefs.edit().putInt(KEY_CUSTOM_MAX, text.toIntOrNull() ?: 0).apply()
            clearFinishedSummary()
        })
        qualitySeekBar.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar, progress: Int, fromUser: Boolean) {
                val value = progress + QUALITY_MIN
                updateQualityLabel(value)
                if (fromUser) {
                    prefs.edit().putInt(KEY_QUALITY, value).apply()
                    clearFinishedSummary()
                }
            }
            override fun onStartTrackingTouch(seekBar: SeekBar) = Unit
            override fun onStopTrackingTouch(seekBar: SeekBar) = Unit
        })
        changeFolderButton.setOnClickListener {
            clearFinishedSummary()
            chooseFolder()
        }
        useDefaultFolderButton.setOnClickListener { resetFolder() }
    }

    private fun pickFiles() {
        if (running) return
        clearFinishedSummary()
        val intent = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = "*/*"
            putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true)
            putExtra(Intent.EXTRA_MIME_TYPES, arrayOf(
                "image/x-canon-cr2", "image/x-canon-cr3", "image/x-nikon-nef", "image/x-sony-arw",
                "image/x-fuji-raf", "image/x-olympus-orf", "image/x-panasonic-rw2", "image/x-adobe-dng",
                "image/*", "application/octet-stream"
            ))
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION)
        }
        startActivityForResult(intent, REQUEST_PICK)
    }

    private fun chooseFolder() {
        val intent = Intent(Intent.ACTION_OPEN_DOCUMENT_TREE).apply {
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION or Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION)
        }
        startActivityForResult(intent, REQUEST_FOLDER)
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (resultCode != RESULT_OK || data == null) return
        if (requestCode == REQUEST_FOLDER) {
            val uri = data.data ?: return
            persistPermission(uri, data.flags)
            outputFolder = uri
            prefs.edit().putString(KEY_FOLDER, uri.toString()).apply()
            clearFinishedSummary()
            updateFolderLabel()
            updateBottomBar()
        } else if (requestCode == REQUEST_PICK) {
            persistIntentPermissions(data)
            addUris(urisFromIntent(data))
        }
    }

    @Suppress("DEPRECATION")
    private fun handleIncomingIntent(incoming: Intent?) {
        if (incoming == null) return
        val uris = when (incoming.action) {
            Intent.ACTION_SEND -> listOfNotNull(incoming.getParcelableExtra(Intent.EXTRA_STREAM) as? Uri, incoming.data)
            Intent.ACTION_SEND_MULTIPLE -> {
                val streams = incoming.getParcelableArrayListExtra<Uri>(Intent.EXTRA_STREAM).orEmpty()
                (streams + urisFromClip(incoming) + listOfNotNull(incoming.data)).distinct()
            }
            Intent.ACTION_VIEW -> listOfNotNull(incoming.data)
            else -> emptyList()
        }
        if (uris.isNotEmpty()) {
            persistIntentPermissions(incoming)
            addUris(uris)
        }
    }

    private fun urisFromIntent(data: Intent): List<Uri> = buildList {
        data.data?.let(::add)
        data.clipData?.let { clip ->
            for (index in 0 until clip.itemCount) clip.getItemAt(index).uri?.let(::add)
        }
    }.distinct()

    private fun urisFromClip(data: Intent): List<Uri> = buildList {
        data.clipData?.let { clip ->
            for (index in 0 until clip.itemCount) clip.getItemAt(index).uri?.let(::add)
        }
    }

    private fun persistIntentPermissions(data: Intent) {
        val flags = data.flags and (Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
        (urisFromIntent(data) + listOfNotNull(data.data)).distinct().forEach { persistPermission(it, flags) }
    }

    private fun persistPermission(uri: Uri, flags: Int) {
        try {
            contentResolver.takePersistableUriPermission(
                uri,
                flags and (Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
            )
        } catch (_: SecurityException) {
            // Shares commonly provide a temporary grant only; keep using it while the process owns it.
        } catch (_: IllegalArgumentException) {
            // Some providers do not expose persistable document grants.
        }
    }

    private fun addUris(uris: List<Uri>) {
        if (uris.isNotEmpty()) clearFinishedSummary()
        val skipped = mutableListOf<String>()
        for (uri in uris.distinct()) {
            if (queue.any { it.uri == uri }) continue
            val suppliedName = queryDisplayName(uri)
            val extension = suppliedName?.let(::fileExtension)
            if (extension != null && extension !in RAW_EXTENSIONS) {
                skipped += suppliedName
                continue
            }

            val mimeType = if (suppliedName == null || extension == null) {
                runCatching { contentResolver.getType(uri) }.getOrNull()
            } else null
            val uriSegment = uri.lastPathSegment?.substringAfterLast('/')
            val name = if (suppliedName == null || (extension == null && suppliedName == uriSegment)) {
                fallbackPhotoName(uri)
            } else {
                suppliedName
            }
            val item = QueueItem(uri = uri, name = name, mimeType = mimeType)
            queue.add(item)
            background.execute { loadQueueItem(item) }
        }
        refreshQueueUi()
        if (skipped.isNotEmpty()) {
            Toast.makeText(this, getString(R.string.unsupported_extension, skipped.joinToString()), Toast.LENGTH_LONG).show()
        }
    }

    private fun queryDisplayName(uri: Uri): String? = runCatching {
        contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { cursor ->
            if (cursor.moveToFirst()) cursor.getString(0)?.takeIf(String::isNotBlank) else null
        }
    }.getOrNull()

    private fun fileExtension(name: String): String? {
        val dot = name.lastIndexOf('.')
        if (dot <= 0 || dot == name.lastIndex) return null
        return name.substring(dot + 1).lowercase()
    }

    private fun fallbackPhotoName(uri: Uri): String {
        val segment = uri.lastPathSegment?.substringAfterLast('/')?.takeIf(String::isNotBlank)
        return if (segment == null) getString(R.string.photo_without_id) else getString(R.string.folder_photo, segment)
    }

    private fun loadQueueItem(item: QueueItem) {
        try {
            val probeText = contentResolver.openFileDescriptor(item.uri, "r")?.use { Native.probe(it.fd) }
                ?: error("Could not open file")
            val probe = JSONObject(probeText)
            if (probe.has("error")) error(probe.getString("error"))
            item.make = probe.optString("make")
            item.model = probe.optString("model")
            item.hasEmbedded = probe.optBoolean("hasEmbeddedJpeg")
            item.datetime = probe.optString("datetime").takeUnless { it == "null" || it.isBlank() }
            item.status = if (item.hasEmbedded) "Pending" else "Pending · no camera JPEG"
            if (item.hasEmbedded) {
                val cached = bitmapCache.get(item.uri.toString())
                item.thumbnail = cached ?: contentResolver.openFileDescriptor(item.uri, "r")?.use { descriptor ->
                    Native.preview(descriptor.fd, 256)?.let { BitmapFactory.decodeByteArray(it, 0, it.size) }
                }?.also { bitmapCache.put(item.uri.toString(), it) }
            }
        } catch (exception: Exception) {
            item.error = exception.message ?: "Unsupported RAW file"
            item.status = if (exception is SecurityException || exception is java.io.FileNotFoundException) {
                getString(R.string.cannot_read_file)
            } else {
                "Unsupported · ${item.error}"
            }
            Log.w(TAG, "Could not inspect ${item.name}: ${item.error}", exception)
        }
        runOnUiThread { refreshQueueUi() }
    }

    private fun showItem(item: QueueItem) {
        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(24), dp(12), dp(24), dp(8))
        }
        val preview = ImageView(this).apply {
            scaleType = ImageView.ScaleType.FIT_CENTER
            adjustViewBounds = true
            minimumHeight = dp(260)
            maxHeight = dp(440)
            setBackgroundResource(R.drawable.bg_thumbnail_placeholder)
            item.thumbnail?.let(::setImageBitmap)
        }
        val message = TextView(this).apply {
            setPadding(0, dp(10), 0, dp(6))
            text = when {
                item.error != null -> getString(R.string.could_not_inspect_raw, item.error)
                item.hasEmbedded -> getString(R.string.preview_loading)
                else -> getString(R.string.no_embedded_jpeg)
            }
            setTextColor(resolveThemeColor(android.R.attr.textColorSecondary, Color.GRAY))
        }
        content.addView(preview, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(360)))
        content.addView(message)
        val camera = listOf(item.make, item.model).filter(String::isNotBlank).joinToString(" ")
        if (camera.isNotBlank()) {
            content.addView(TextView(this).apply {
                text = camera
                setTextColor(resolveThemeColor(android.R.attr.textColorSecondary, Color.GRAY))
                setPadding(0, dp(2), 0, dp(4))
            })
        }

        val builder = AlertDialog.Builder(this)
            .setTitle(item.name)
            .setView(content)
            .setNegativeButton(R.string.dialog_close, null)
        val jpegMode = selectedFormat() == FORMAT_JPEG
        if (jpegMode) {
            builder.setPositiveButton(R.string.develop_from_raw) { _, _ ->
                item.sourceOverride = SOURCE_DEVELOP
                item.status = "Pending · develop from RAW"
                refreshQueueUi()
            }
            if (item.hasEmbedded) {
                builder.setNeutralButton(R.string.use_camera_jpeg) { _, _ ->
                    item.sourceOverride = SOURCE_EMBEDDED
                    item.status = "Pending · camera JPEG"
                    refreshQueueUi()
                }
            }
        }
        val dialog = builder.create()
        dialog.setOnShowListener {
            if (item.hasEmbedded && item.error == null) background.execute {
                val bitmap = runCatching {
                    contentResolver.openFileDescriptor(item.uri, "r")?.use { descriptor ->
                        Native.preview(descriptor.fd, 1600)?.let { BitmapFactory.decodeByteArray(it, 0, it.size) }
                    }
                }.getOrNull()
                runOnUiThread {
                    if (dialog.isShowing && bitmap != null) {
                        preview.setImageBitmap(bitmap)
                        message.text = getString(R.string.camera_jpeg_preview)
                    } else if (dialog.isShowing) {
                        message.text = getString(R.string.embedded_preview_unavailable)
                    }
                }
            }
        }
        dialog.show()
    }

    private fun isDone(item: QueueItem): Boolean = item.status.startsWith("Done")

    private fun pendingItems(): List<QueueItem> = queue.filter { it.error == null && !isDone(it) }

    private fun clearQueue() {
        if (running) return
        queue.clear()
        clearFinishedSummary()
        refreshQueueUi()
    }

    private fun startConversion() {
        if (running) return
        val convertibleItems = pendingItems()
        if (convertibleItems.isEmpty()) return
        if (Build.VERSION.SDK_INT < 29 && outputFolder == null) return
        if (Build.VERSION.SDK_INT >= 33 && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), REQUEST_NOTIFICATIONS)
        }
        clearFinishedSummary()
        val options = Bundle().apply {
            putInt(ConversionService.OPTION_FORMAT, selectedFormat())
            putBoolean(ConversionService.OPTION_EMBED, embedOriginal.isChecked)
            putInt(ConversionService.OPTION_SOURCE, if (sourceSpinner.selectedItemPosition == 1) SOURCE_DEVELOP else SOURCE_AUTO)
            putInt(ConversionService.OPTION_LONG_EDGE, selectedLongEdge())
            putInt(ConversionService.OPTION_MAX_BYTES, selectedMaxBytes())
            putInt(ConversionService.OPTION_QUALITY, qualitySeekBar.progress + QUALITY_MIN)
            outputFolder?.let { putString(ConversionService.OPTION_FOLDER, it.toString()) }
            convertibleItems.forEach { item ->
                item.sourceOverride?.let { putInt(ConversionService.OPTION_ITEM_SOURCE_PREFIX + item.uri, it) }
            }
        }
        val intent = Intent(this, ConversionService::class.java).apply {
            action = ConversionService.ACTION_START
            putStringArrayListExtra(ConversionService.EXTRA_URIS, ArrayList(convertibleItems.map { it.uri.toString() }))
            putStringArrayListExtra(ConversionService.EXTRA_DATES, ArrayList(convertibleItems.map { it.datetime.orEmpty() }))
            putExtra(ConversionService.EXTRA_OPTIONS, options)
            val first = convertibleItems.first().uri
            clipData = android.content.ClipData.newUri(contentResolver, "Raw2DNG inputs", first).also { clip ->
                convertibleItems.drop(1).forEach { item -> clip.addItem(android.content.ClipData.Item(item.uri)) }
            }
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        running = true
        progressCurrent = 0
        progressTotal = convertibleItems.size
        progressStatus = ""
        convertibleItems.forEach { it.status = "Queued" }
        refreshQueueUi()
        if (Build.VERSION.SDK_INT >= 26) startForegroundService(intent) else startService(intent)
    }

    private fun cancelConversion() {
        if (!running) return
        val intent = Intent(this, ConversionService::class.java).setAction(ConversionService.ACTION_CANCEL)
        startService(intent)
    }

    private fun selectedFormat(): Int = if (formatGroup.checkedRadioButtonId == R.id.formatJpeg) FORMAT_JPEG else FORMAT_DNG

    private fun selectedLongEdge(): Int = when (sizeSpinner.selectedItemPosition) {
        0 -> 0
        1 -> 1080
        2 -> 2048
        3 -> 3840
        else -> customSize.text.toString().toIntOrNull()?.coerceAtLeast(0) ?: 0
    }

    private fun selectedMaxBytes(): Int {
        val bytes = when (maxSpinner.selectedItemPosition) {
            MAX_NONE -> 0L
            MAX_500_KB -> 500L * 1024L
            MAX_1_MB -> 1024L * 1024L
            MAX_2_MB -> 2L * 1024L * 1024L
            else -> (customMax.text.toString().toLongOrNull() ?: 0L) * 1024L
        }
        return bytes.coerceIn(0L, Int.MAX_VALUE.toLong()).toInt()
    }

    private fun resetFolder() {
        outputFolder = null
        prefs.edit().remove(KEY_FOLDER).apply()
        clearFinishedSummary()
        updateFolderLabel()
        updateBottomBar()
    }

    private fun updateFolderLabel() {
        val custom = outputFolder
        when {
            custom != null -> {
                folderLabel.text = readableTreePath(custom)
                folderLabel.setTextColor(resolveThemeColor(android.R.attr.textColorPrimary, Color.BLACK))
            }
            Build.VERSION.SDK_INT >= 29 -> {
                folderLabel.text = getString(R.string.default_output_folder)
                folderLabel.setTextColor(resolveThemeColor(android.R.attr.textColorPrimary, Color.BLACK))
            }
            else -> {
                folderLabel.text = getString(R.string.not_set_choose_folder)
                folderLabel.setTextColor(resolveThemeColor(android.R.attr.textColorSecondary, Color.GRAY))
            }
        }
        useDefaultFolderButton.visibility = if (Build.VERSION.SDK_INT >= 29 && custom != null) View.VISIBLE else View.GONE
    }

    private fun readableTreePath(tree: Uri): String = runCatching {
        val documentId = DocumentsContract.getTreeDocumentId(tree)
        val volume = documentId.substringBefore(':', "")
        val path = documentId.substringAfter(':', documentId).trim('/')
        when {
            volume.equals("primary", ignoreCase = true) -> path.ifBlank { getString(R.string.internal_storage) }
            volume.isNotBlank() -> if (path.isBlank()) getString(R.string.sd_card) else getString(R.string.sd_card_path, path)
            else -> documentId
        }
    }.getOrElse { tree.lastPathSegment ?: getString(R.string.selected_folder) }

    private fun currentOutputLocation(): String = when {
        outputFolder != null -> readableTreePath(outputFolder!!)
        Build.VERSION.SDK_INT >= 29 -> getString(R.string.default_output_folder)
        else -> getString(R.string.selected_folder)
    }

    private fun updateFormatPanels() {
        val jpeg = selectedFormat() == FORMAT_JPEG
        dngOptions.visibility = if (jpeg) View.GONE else View.VISIBLE
        jpegOptions.visibility = if (jpeg) View.VISIBLE else View.GONE
    }

    private fun refreshQueueUi() {
        if (!::adapter.isInitialized) return
        adapter.notifyDataSetChanged()
        emptyState.visibility = if (queue.isEmpty()) View.VISIBLE else View.GONE
        filesSectionTitle.text = getString(R.string.files_count, queue.size)
        updateBottomBar()
        invalidateOptionsMenu()
    }

    private fun updateBottomBar() {
        val empty = queue.isEmpty()
        idleActions.visibility = if (running) View.GONE else View.VISIBLE
        progressPanel.visibility = if (running) View.VISIBLE else View.GONE
        addEmptyButton.visibility = if (!running && empty) View.VISIBLE else View.GONE
        addFilesButton.visibility = if (!running && !empty) View.VISIBLE else View.GONE
        convertButton.visibility = if (!running && !empty) View.VISIBLE else View.GONE
        finishedSummary.text = finishedSummaryText.orEmpty()
        finishedSummary.visibility = if (!running && !finishedSummaryText.isNullOrBlank()) View.VISIBLE else View.GONE

        val convertibleCount = pendingItems().size
        val allDone = convertibleCount == 0 && queue.any(::isDone)
        convertButton.text = if (allDone) getString(R.string.clear_list)
            else resources.getQuantityString(R.plurals.convert_count, convertibleCount, convertibleCount)
        val folderMissing = Build.VERSION.SDK_INT < 29 && outputFolder == null
        folderRequiredHint.visibility = if (!running && !empty && folderMissing) View.VISIBLE else View.GONE
        convertButton.isEnabled = allDone || (convertibleCount > 0 && !folderMissing && !running)
        progressLabel.text = when {
            progressStatus.isNotBlank() -> progressStatus
            progressTotal > 0 -> {
                val currentName = queue.firstOrNull { it.status == "Converting" }?.name.orEmpty()
                getString(R.string.converting_label, progressCurrent.coerceAtLeast(1), progressTotal, currentName)
            }
            else -> getString(R.string.converting)
        }
        conversionProgress.max = progressTotal.coerceAtLeast(1)
        conversionProgress.progress = progressCurrent.coerceIn(0, conversionProgress.max)

        val enabled = !running
        addEmptyButton.isEnabled = enabled
        addFilesButton.isEnabled = enabled
        setEnabledRecursively(header, enabled)
        list.isEnabled = enabled
        list.isClickable = enabled
        changeFolderButton.isEnabled = enabled
        useDefaultFolderButton.isEnabled = enabled
        cancelButton.isEnabled = running
    }

    private fun setEnabledRecursively(view: View, enabled: Boolean) {
        when (view) {
            is Button, is Spinner, is RadioGroup, is RadioButton, is CheckBox, is EditText, is SeekBar, is ImageButton -> view.isEnabled = enabled
        }
        if (view is ViewGroup) {
            for (index in 0 until view.childCount) setEnabledRecursively(view.getChildAt(index), enabled)
        }
    }

    private fun showAbout() {
        val view = TextView(this).apply {
            text = getString(R.string.about_text)
            setPadding(dp(24), dp(12), dp(24), dp(8))
            autoLinkMask = Linkify.WEB_URLS
        }
        AlertDialog.Builder(this)
            .setTitle(R.string.about_title)
            .setView(view)
            .setPositiveButton(R.string.dialog_close, null)
            .show()
    }

    private fun removeItem(item: QueueItem) {
        if (running) return
        queue.remove(item)
        clearFinishedSummary()
        refreshQueueUi()
    }

    private fun updateQualityLabel(value: Int) {
        qualityLabel.text = getString(R.string.quality_value, value)
    }

    private fun clearFinishedSummary() {
        finishedSummaryText = null
        if (::finishedSummary.isInitialized) {
            finishedSummary.text = ""
            finishedSummary.visibility = View.GONE
        }
    }

    private fun migrateMaximumSizePreference() {
        if (prefs.getBoolean(KEY_MAX_MIGRATED, false)) return
        val oldValue = if (prefs.contains(KEY_MAX_PRESET)) prefs.getInt(KEY_MAX_PRESET, MAX_NONE) else MAX_NONE
        val migrated = when (oldValue) {
            0 -> MAX_NONE
            1 -> MAX_CUSTOM // The previous UI used index 1 for a custom KB limit.
            else -> oldValue.coerceIn(MAX_NONE, MAX_CUSTOM)
        }
        prefs.edit().putInt(KEY_MAX_PRESET, migrated).putBoolean(KEY_MAX_MIGRATED, true).apply()
    }

    private fun applyPrimaryButtonStyle(button: Button) {
        val accent = resolveThemeColor(android.R.attr.colorAccent, 0xff0b7770.toInt())
        val disabled = 0x33808080
        val textColor = if (relativeLuminance(accent) > 0.179f) Color.BLACK else Color.WHITE
        button.backgroundTintList = ColorStateList(
            arrayOf(intArrayOf(android.R.attr.state_enabled), intArrayOf()),
            intArrayOf(accent, disabled)
        )
        button.setTextColor(ColorStateList(
            arrayOf(intArrayOf(android.R.attr.state_enabled), intArrayOf()),
            intArrayOf(textColor, 0x99808080.toInt())
        ))
    }

    private fun relativeLuminance(color: Int): Float {
        fun channel(value: Int): Float {
            val normalized = value / 255f
            return if (normalized <= 0.04045f) normalized / 12.92f else ((normalized + 0.055f) / 1.055f).toDouble().pow(2.4).toFloat()
        }
        return 0.2126f * channel(Color.red(color)) + 0.7152f * channel(Color.green(color)) + 0.0722f * channel(Color.blue(color))
    }

    @Suppress("DEPRECATION")
    private fun resolveThemeColor(attribute: Int, fallback: Int): Int {
        val value = TypedValue()
        if (!theme.resolveAttribute(attribute, value, true)) return fallback
        if (value.type in TypedValue.TYPE_FIRST_COLOR_INT..TypedValue.TYPE_LAST_COLOR_INT) return value.data
        return runCatching { resources.getColor(value.resourceId) }.getOrDefault(fallback)
    }

    private fun onSelection(action: (Int) -> Unit) = object : AdapterView.OnItemSelectedListener {
        override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) = action(position)
        override fun onNothingSelected(parent: AdapterView<*>?) = Unit
    }

    private fun simpleWatcher(action: (String) -> Unit) = object : android.text.TextWatcher {
        override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
        override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) = action(s?.toString().orEmpty())
        override fun afterTextChanged(s: android.text.Editable?) = Unit
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

    private inner class QueueAdapter : BaseAdapter() {
        override fun getCount(): Int = queue.size
        override fun getItem(position: Int): QueueItem = queue[position]
        override fun getItemId(position: Int): Long = position.toLong()

        override fun getView(position: Int, recycled: View?, parent: ViewGroup): View {
            val row = recycled ?: LayoutInflater.from(this@MainActivity).inflate(R.layout.row_queue_item, parent, false)
            val item = getItem(position)
            row.findViewById<ImageView>(R.id.rowThumbnail).setImageBitmap(item.thumbnail)
            row.findViewById<TextView>(R.id.rowFileName).apply {
                text = item.name
                maxLines = 1
                ellipsize = TextUtils.TruncateAt.MIDDLE
            }
            row.findViewById<TextView>(R.id.rowCameraModel).apply {
                val camera = listOf(item.make, item.model).filter(String::isNotBlank).joinToString(" ")
                val label = camera.ifBlank { item.mimeType.orEmpty() }
                text = label
                visibility = if (label.isBlank()) View.GONE else View.VISIBLE
            }
            row.findViewById<TextView>(R.id.rowStatus).apply {
                text = if (item.status == "Converting") getString(R.string.converting) else item.status
                setTextColor(statusColor(item.status))
            }
            row.findViewById<ImageButton>(R.id.removeButton).apply {
                visibility = if (running) View.GONE else View.VISIBLE
                setOnClickListener { removeItem(item) }
            }
            row.isEnabled = !running
            return row
        }
    }

    private fun statusColor(status: String): Int = when {
        status.startsWith("Error") || status.startsWith("Unsupported") -> {
            if (Build.VERSION.SDK_INT >= 26) resolveThemeColor(android.R.attr.colorError, ERROR_RED) else ERROR_RED
        }
        status.startsWith("Converting") || status.startsWith("Done") -> resolveThemeColor(android.R.attr.colorAccent, 0xff0b7770.toInt())
        else -> resolveThemeColor(android.R.attr.textColorSecondary, Color.GRAY)
    }

    companion object {
        private const val TAG = "Raw2DNG"
        private const val REQUEST_PICK = 11
        private const val REQUEST_FOLDER = 12
        private const val REQUEST_NOTIFICATIONS = 13
        private const val STATE_URIS = "queueUris"
        private const val KEY_FORMAT = "format"
        private const val KEY_EMBED = "embed"
        private const val KEY_SOURCE = "source"
        private const val KEY_SIZE_PRESET = "sizePreset"
        private const val KEY_CUSTOM_SIZE = "customSize"
        private const val KEY_MAX_PRESET = "maxPreset"
        private const val KEY_MAX_MIGRATED = "maxPresetMigrated"
        private const val KEY_CUSTOM_MAX = "customMax"
        private const val KEY_QUALITY = "quality"
        private const val KEY_FOLDER = "outputFolder"
        private const val FORMAT_DNG = 0
        private const val FORMAT_JPEG = 1
        private const val SOURCE_AUTO = 0
        private const val SOURCE_EMBEDDED = 1
        private const val SOURCE_DEVELOP = 2
        private const val SIZE_CUSTOM = 4
        private const val MAX_NONE = 0
        private const val MAX_500_KB = 1
        private const val MAX_1_MB = 2
        private const val MAX_2_MB = 3
        private const val MAX_CUSTOM = 4
        private const val QUALITY_MIN = 60
        private const val ERROR_RED = 0xffd32f2f.toInt()
        private val RAW_EXTENSIONS = setOf(
            "cr2", "cr3", "crw", "nef", "nrw", "arw", "srf", "sr2", "raf", "orf", "rw2", "pef", "dng", "srw",
            "3fr", "fff", "iiq", "erf", "mef", "mos", "mrw", "x3f", "kdc", "dcr", "rwl", "gpr", "ari", "bay", "cap", "eip", "r3d"
        )
    }
}
