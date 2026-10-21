package com.raw2dng

import android.Manifest
import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.OpenableColumns
import android.util.Log
import android.util.LruCache
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.*
import org.json.JSONObject
import java.util.concurrent.Executors

class MainActivity : Activity() {
    private val queue = mutableListOf<QueueItem>()
    private val background = Executors.newSingleThreadExecutor()
    private val prefs by lazy { getSharedPreferences("settings", MODE_PRIVATE) }
    private lateinit var adapter: QueueAdapter
    private lateinit var list: ListView
    private lateinit var convertButton: Button
    private lateinit var cancelButton: Button
    private lateinit var outputLabel: TextView
    private lateinit var selectButton: Button
    private lateinit var clearButton: Button
    private lateinit var formatSpinner: Spinner
    private lateinit var dngPanel: LinearLayout
    private lateinit var jpegPanel: LinearLayout
    private lateinit var settingsRoot: LinearLayout
    private lateinit var locationRow: LinearLayout
    private lateinit var embedRaw: CheckBox
    private lateinit var sourceSpinner: Spinner
    private lateinit var sizeSpinner: Spinner
    private lateinit var customSize: EditText
    private lateinit var maxSpinner: Spinner
    private lateinit var customMax: EditText
    private lateinit var quality: EditText
    private var outputFolder: Uri? = null
    private var running = false

    private val bitmapCache = object : LruCache<String, Bitmap>(24 * 1024 * 1024) {
        override fun sizeOf(key: String, value: Bitmap): Int = value.byteCount
    }

    private val eventListener = ConversionEvents.Listener { event ->
        if (event.uri != null) {
            queue.firstOrNull { it.uri.toString() == event.uri }?.status = event.status
        }
        if (event.finished) running = false
        if (event.status.isNotEmpty() && event.uri == null) {
            Toast.makeText(this, event.status, Toast.LENGTH_SHORT).show()
        }
        updateControls()
        adapter.notifyDataSetChanged()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
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

    private fun buildUi() {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(12), dp(8), dp(12), dp(8))
        }
        val header = LinearLayout(this).apply { gravity = Gravity.CENTER_VERTICAL }
        selectButton = button("Select RAW files") { pickFiles() }
        clearButton = button("Clear") { if (!running) { queue.clear(); adapter.notifyDataSetChanged(); updateControls() } }
        header.addView(selectButton, LinearLayout.LayoutParams(0, dp(48), 1f))
        header.addView(clearButton)
        header.addView(button("About") { showAbout() })
        root.addView(header)

        val settings = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        settingsRoot = settings
        settings.addView(label("Output format"))
        formatSpinner = spinner(listOf("DNG", "JPEG"))
        formatSpinner.setSelection(prefs.getInt(KEY_FORMAT, 0))
        settings.addView(formatSpinner)

        dngPanel = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        embedRaw = CheckBox(this).apply {
            text = getString(R.string.embed_original)
            isChecked = prefs.getBoolean(KEY_EMBED, false)
            setOnCheckedChangeListener { _, checked -> prefs.edit().putBoolean(KEY_EMBED, checked).apply() }
        }
        dngPanel.addView(embedRaw)
        dngPanel.addView(hint("Larger DNG; lets you extract the original later."))
        settings.addView(dngPanel)

        jpegPanel = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        jpegPanel.addView(label("JPEG source"))
        sourceSpinner = spinner(listOf("Camera JPEG when available, otherwise develop", "Always develop from RAW"))
        sourceSpinner.setSelection(prefs.getInt(KEY_SOURCE, 0))
        jpegPanel.addView(sourceSpinner)
        jpegPanel.addView(label("Long edge"))
        sizeSpinner = spinner(listOf("Original", "1080 px", "2048 px", "3840 px", "Custom"))
        sizeSpinner.setSelection(prefs.getInt(KEY_SIZE_PRESET, 0))
        jpegPanel.addView(sizeSpinner)
        customSize = editNumber("Custom long edge in pixels", prefs.getInt(KEY_CUSTOM_SIZE, 3000).toString())
        jpegPanel.addView(customSize)
        jpegPanel.addView(label("Maximum file size"))
        maxSpinner = spinner(listOf("Off", "Custom KB"))
        maxSpinner.setSelection(prefs.getInt(KEY_MAX_PRESET, 0))
        jpegPanel.addView(maxSpinner)
        customMax = editNumber("Maximum size in KB", prefs.getInt(KEY_CUSTOM_MAX, 1000).toString())
        jpegPanel.addView(customMax)
        val qualityRow = LinearLayout(this).apply { gravity = Gravity.CENTER_VERTICAL }
        qualityRow.addView(label("JPEG quality (60–100)"), LinearLayout.LayoutParams(0, dp(48), 1f))
        quality = editNumber("92", prefs.getInt(KEY_QUALITY, 92).toString(), 80)
        qualityRow.addView(quality)
        jpegPanel.addView(qualityRow)
        settings.addView(jpegPanel)

        formatSpinner.onItemSelectedListener = simpleSelection { selected ->
            prefs.edit().putInt(KEY_FORMAT, selected).apply()
            updateFormatPanels()
            updateControls()
        }
        sourceSpinner.onItemSelectedListener = simpleSelection { prefs.edit().putInt(KEY_SOURCE, it).apply() }
        sizeSpinner.onItemSelectedListener = simpleSelection {
            prefs.edit().putInt(KEY_SIZE_PRESET, it).apply()
            customSize.visibility = if (it == 4) View.VISIBLE else View.GONE
        }
        maxSpinner.onItemSelectedListener = simpleSelection {
            prefs.edit().putInt(KEY_MAX_PRESET, it).apply()
            customMax.visibility = if (it == 1) View.VISIBLE else View.GONE
        }
        customSize.addTextChangedListener(simpleWatcher { text -> prefs.edit().putInt(KEY_CUSTOM_SIZE, text.toIntOrNull() ?: 0).apply() })
        customMax.addTextChangedListener(simpleWatcher { text -> prefs.edit().putInt(KEY_CUSTOM_MAX, text.toIntOrNull() ?: 0).apply() })
        quality.addTextChangedListener(simpleWatcher { text -> prefs.edit().putInt(KEY_QUALITY, text.toIntOrNull()?.coerceIn(60, 100) ?: 92).apply() })
        root.addView(ScrollView(this).apply { addView(settings) }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(310)))

        locationRow = LinearLayout(this).apply { gravity = Gravity.CENTER_VERTICAL }
        outputLabel = label("")
        locationRow.addView(outputLabel, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        locationRow.addView(button("Choose folder…") { chooseFolder() })
        if (Build.VERSION.SDK_INT >= 29) locationRow.addView(button("Reset") { resetFolder() })
        root.addView(locationRow)

        list = ListView(this)
        adapter = QueueAdapter()
        list.adapter = adapter
        list.setOnItemClickListener { _, _, position, _ -> showItem(queue[position]) }
        list.setOnItemLongClickListener { _, _, position, _ -> removeItem(queue[position]); true }
        root.addView(list, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))

        val footer = LinearLayout(this).apply { gravity = Gravity.CENTER_VERTICAL }
        convertButton = button("Convert") { startConversion() }
        cancelButton = button("Cancel") { cancelConversion() }
        footer.addView(convertButton, LinearLayout.LayoutParams(0, dp(52), 1f))
        footer.addView(cancelButton)
        root.addView(footer)
        setContentView(root)
        if (Build.VERSION.SDK_INT >= 35) {
            val left = root.paddingLeft
            val top = root.paddingTop
            val right = root.paddingRight
            val bottom = root.paddingBottom
            root.setOnApplyWindowInsetsListener { view, insets ->
                val systemBars = insets.getInsets(android.view.WindowInsets.Type.systemBars())
                view.setPadding(left + systemBars.left, top + systemBars.top, right + systemBars.right, bottom + systemBars.bottom)
                insets
            }
            root.requestApplyInsets()
        }
        updateFormatPanels()
        updateLocationLabel()
        updateControls()
    }

    private fun pickFiles() {
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
            updateLocationLabel()
            updateControls()
        } else if (requestCode == REQUEST_PICK) {
            persistIntentPermissions(data)
            addUris(urisFromIntent(data))
        }
    }

    private fun handleIncomingIntent(incoming: Intent?) {
        if (incoming == null) return
        val uris = when (incoming.action) {
            Intent.ACTION_SEND -> listOfNotNull(incoming.getParcelableExtra(Intent.EXTRA_STREAM) as? Uri, incoming.data)
            Intent.ACTION_SEND_MULTIPLE -> {
                @Suppress("DEPRECATION")
                (incoming.getParcelableArrayListExtra<Uri>(Intent.EXTRA_STREAM) ?: arrayListOf()) + urisFromClip(incoming)
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
        data.clipData?.let { clip -> for (index in 0 until clip.itemCount) clip.getItemAt(index).uri?.let(::add) }
    }.distinct()

    private fun urisFromClip(data: Intent): List<Uri> = buildList {
        data.clipData?.let { clip -> for (index in 0 until clip.itemCount) clip.getItemAt(index).uri?.let(::add) }
    }

    private fun persistIntentPermissions(data: Intent) {
        val flags = data.flags and (Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
        (urisFromIntent(data) + listOfNotNull(data.data)).distinct().forEach { persistPermission(it, flags) }
    }

    private fun persistPermission(uri: Uri, flags: Int) {
        try {
            contentResolver.takePersistableUriPermission(uri, flags and (Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION))
        } catch (_: SecurityException) {
            // Shares commonly provide a temporary grant only; keep using it while the process owns it.
        } catch (_: IllegalArgumentException) {
            // Some providers do not expose persistable document grants.
        }
    }

    private fun addUris(uris: List<Uri>) {
        val skipped = mutableListOf<String>()
        val supported = uris.distinct().filter { uri ->
            if (isRawName(displayName(uri))) true else { skipped += displayName(uri); false }
        }
        supported.forEach { uri ->
            if (queue.any { it.uri == uri }) return@forEach
            val item = QueueItem(uri, displayName(uri))
            queue.add(item)
            background.execute { loadQueueItem(item) }
        }
        adapter.notifyDataSetChanged()
        updateControls()
        if (skipped.isNotEmpty()) {
            Toast.makeText(this, "Skipped unsupported: ${skipped.joinToString()}", Toast.LENGTH_LONG).show()
        }
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
            } else if (prefs.getInt(KEY_FORMAT, 0) == 1) {
                item.sourceOverride = 2
            }
        } catch (exception: Exception) {
            item.error = exception.message ?: "Unsupported RAW file"
            item.status = "Unsupported · ${item.error}"
            Log.w(TAG, "Could not inspect ${item.name}: ${item.error}", exception)
        }
        runOnUiThread { adapter.notifyDataSetChanged(); updateControls() }
    }

    private fun showItem(item: QueueItem) {
        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(8), dp(8), dp(8), dp(4))
        }
        val preview = ImageView(this).apply {
            scaleType = ImageView.ScaleType.FIT_CENTER
            adjustViewBounds = true
            maxHeight = dp(440)
            item.thumbnail?.let(::setImageBitmap)
        }
        val message = TextView(this).apply {
            text = item.error?.let { "Could not inspect this RAW file: $it" }
                ?: if (item.hasEmbedded) "Loading camera preview…" else "No embedded camera JPEG. JPEG output will be developed from the RAW data."
        }
        content.addView(preview, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(360)))
        content.addView(message)
        if (item.make.isNotBlank() || item.model.isNotBlank()) content.addView(TextView(this).apply {
            text = getString(R.string.camera_model, item.make, item.model).trim()
        })
        val dialog = AlertDialog.Builder(this)
            .setTitle(item.name)
            .setView(content)
            .setNegativeButton("Close", null)
            .create()
        dialog.setOnShowListener {
            if (item.hasEmbedded) background.execute {
                val bitmap = runCatching {
                    contentResolver.openFileDescriptor(item.uri, "r")?.use { descriptor ->
                        Native.preview(descriptor.fd, 1600)?.let { BitmapFactory.decodeByteArray(it, 0, it.size) }
                    }
                }.getOrNull()
                runOnUiThread {
                    if (bitmap != null && dialog.isShowing) {
                        preview.setImageBitmap(bitmap)
                        message.text = getString(R.string.camera_preview)
                    } else if (dialog.isShowing) message.text = getString(R.string.embedded_preview_unavailable)
                }
            }
            if (prefs.getInt(KEY_FORMAT, 0) == 1) {
                dialog.getButton(AlertDialog.BUTTON_NEUTRAL)?.visibility = View.GONE
                dialog.getButton(AlertDialog.BUTTON_POSITIVE)?.visibility = View.GONE
            }
        }
        dialog.setButton(AlertDialog.BUTTON_POSITIVE, "Develop from RAW") { _, _ ->
            item.sourceOverride = 2
            item.status = "Pending · develop from RAW"
            adapter.notifyDataSetChanged()
        }
        if (item.hasEmbedded) {
            dialog.setButton(AlertDialog.BUTTON_NEUTRAL, "Use camera JPEG") { _, _ ->
                item.sourceOverride = 1
                item.status = "Pending · camera JPEG"
                adapter.notifyDataSetChanged()
            }
        }
        dialog.show()
        if (prefs.getInt(KEY_FORMAT, 0) == 1) {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE)?.visibility = View.VISIBLE
            dialog.getButton(AlertDialog.BUTTON_NEUTRAL)?.visibility = if (item.hasEmbedded) View.VISIBLE else View.GONE
        }
    }

    private fun startConversion() {
        if (queue.isEmpty()) return
        if (Build.VERSION.SDK_INT < 29 && outputFolder == null) {
            Toast.makeText(this, "Choose an output folder before converting.", Toast.LENGTH_LONG).show()
            chooseFolder()
            return
        }
        if (Build.VERSION.SDK_INT >= 33 && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), REQUEST_NOTIFICATIONS)
        }
        val convertibleItems = queue.filter { it.error == null }
        val options = Bundle().apply {
            putInt(ConversionService.OPTION_FORMAT, formatSpinner.selectedItemPosition)
            putBoolean(ConversionService.OPTION_EMBED, embedRaw.isChecked)
            putInt(ConversionService.OPTION_SOURCE, if (sourceSpinner.selectedItemPosition == 1) 2 else 0)
            putInt(ConversionService.OPTION_LONG_EDGE, selectedLongEdge())
            putInt(ConversionService.OPTION_MAX_BYTES, selectedMaxBytes())
            putInt(ConversionService.OPTION_QUALITY, quality.text.toString().toIntOrNull()?.coerceIn(60, 100) ?: 92)
            outputFolder?.let { putString(ConversionService.OPTION_FOLDER, it.toString()) }
            convertibleItems.forEach { item -> item.sourceOverride?.let { putInt(ConversionService.OPTION_ITEM_SOURCE_PREFIX + item.uri, it) } }
        }
        val intent = Intent(this, ConversionService::class.java).apply {
            action = ConversionService.ACTION_START
            putStringArrayListExtra(ConversionService.EXTRA_URIS, ArrayList(convertibleItems.map { it.uri.toString() }))
            putStringArrayListExtra(ConversionService.EXTRA_DATES, ArrayList(convertibleItems.map { it.datetime.orEmpty() }))
            putExtra(ConversionService.EXTRA_OPTIONS, options)
        }
        running = true
        queue.forEach { if (it.error == null) it.status = "Queued" }
        updateControls()
        adapter.notifyDataSetChanged()
        if (Build.VERSION.SDK_INT >= 26) startForegroundService(intent) else startService(intent)
    }

    private fun cancelConversion() {
        val intent = Intent(this, ConversionService::class.java).setAction(ConversionService.ACTION_CANCEL)
        startService(intent)
    }

    private fun selectedLongEdge(): Int = when (sizeSpinner.selectedItemPosition) {
        0 -> 0
        1 -> 1080
        2 -> 2048
        3 -> 3840
        else -> customSize.text.toString().toIntOrNull()?.coerceAtLeast(0) ?: 0
    }

    private fun selectedMaxBytes(): Int = if (maxSpinner.selectedItemPosition == 0) 0 else {
        ((customMax.text.toString().toLongOrNull() ?: 0L) * 1024L).coerceIn(0, Int.MAX_VALUE.toLong()).toInt()
    }

    private fun resetFolder() {
        outputFolder = null
        prefs.edit().remove(KEY_FOLDER).apply()
        updateLocationLabel()
        updateControls()
    }

    private fun updateLocationLabel() {
        outputLabel.text = when {
            outputFolder != null -> "Folder: ${outputFolder!!.lastPathSegment ?: "Selected folder"}"
            Build.VERSION.SDK_INT >= 29 -> "Output: Pictures/Raw2DNG"
            else -> "Output: choose a folder"
        }
    }

    private fun updateFormatPanels() {
        val jpeg = formatSpinner.selectedItemPosition == 1
        dngPanel.visibility = if (jpeg) View.GONE else View.VISIBLE
        jpegPanel.visibility = if (jpeg) View.VISIBLE else View.GONE
    }

    private fun updateControls() {
        val canRun = queue.any { it.error == null }
        convertButton.isEnabled = canRun && !running && (Build.VERSION.SDK_INT >= 29 || outputFolder != null)
        convertButton.text = if (running) "Converting…" else "Convert"
        cancelButton.isEnabled = running
        list.isEnabled = !running
        selectButton.isEnabled = !running
        clearButton.isEnabled = !running && queue.isNotEmpty()
        setInteractiveEnabled(settingsRoot, !running)
        setInteractiveEnabled(locationRow, !running)
        outputLabel.alpha = if (Build.VERSION.SDK_INT >= 29 || outputFolder != null) 1f else 0.65f
    }

    private fun setInteractiveEnabled(view: View, enabled: Boolean) {
        if (view is Button || view is Spinner || view is CheckBox || view is EditText) view.isEnabled = enabled
        if (view is ViewGroup) {
            for (index in 0 until view.childCount) setInteractiveEnabled(view.getChildAt(index), enabled)
        }
    }

    private fun showAbout() {
        val text = "Raw2DNG 0.1.0\n\nThe app is MIT licensed.\nUses rawler (dnglab), LGPL-2.1.\n\nrawler source: https://github.com/dnglab/dnglab\nProject source (placeholder): $SOURCE_URL"
        val view = TextView(this).apply {
            this.text = text
            setPadding(dp(24), dp(16), dp(24), dp(16))
            autoLinkMask = android.text.util.Linkify.WEB_URLS
        }
        AlertDialog.Builder(this).setTitle("About Raw2DNG").setView(view).setPositiveButton("Close", null).show()
    }

    private fun removeItem(item: QueueItem) {
        if (running) return
        queue.remove(item)
        adapter.notifyDataSetChanged()
        updateControls()
    }

    private fun displayName(uri: Uri): String = runCatching {
        contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { cursor ->
            if (cursor.moveToFirst()) cursor.getString(0) else null
        }
    }.getOrNull() ?: uri.lastPathSegment ?: "raw-file"

    private fun isRawName(name: String): Boolean = name.substringAfterLast('.', "").lowercase() in RAW_EXTENSIONS

    private inner class QueueAdapter : BaseAdapter() {
        override fun getCount(): Int = queue.size
        override fun getItem(position: Int): QueueItem = queue[position]
        override fun getItemId(position: Int): Long = position.toLong()
        override fun getView(position: Int, recycled: View?, parent: ViewGroup): View {
            val row = recycled as? LinearLayout ?: LinearLayout(this@MainActivity).apply {
                gravity = Gravity.CENTER_VERTICAL
                setPadding(dp(4), dp(6), dp(4), dp(6))
                addView(ImageView(this@MainActivity).apply { id = ID_THUMB; scaleType = ImageView.ScaleType.CENTER_CROP }, LinearLayout.LayoutParams(dp(72), dp(72)))
                val body = LinearLayout(this@MainActivity).apply { id = ID_BODY; orientation = LinearLayout.VERTICAL; setPadding(dp(10), 0, dp(4), 0) }
                addView(body, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
                addView(Button(this@MainActivity).apply { id = ID_REMOVE; text = "×" })
            }
            val item = getItem(position)
            val image = row.findViewById<ImageView>(ID_THUMB)
            image.setImageBitmap(item.thumbnail)
            val body = row.findViewById<LinearLayout>(ID_BODY)
            body.removeAllViews()
            body.addView(TextView(this@MainActivity).apply { text = item.name; textSize = 16f; maxLines = 1; ellipsize = android.text.TextUtils.TruncateAt.MIDDLE })
            val model = listOf(item.make, item.model).filter(String::isNotBlank).joinToString(" ")
            if (model.isNotBlank()) body.addView(TextView(this@MainActivity).apply { text = model; textSize = 13f })
            body.addView(TextView(this@MainActivity).apply { text = item.status; textSize = 13f })
            row.findViewById<Button>(ID_REMOVE).apply {
                isEnabled = !running
                setOnClickListener { removeItem(item) }
            }
            return row
        }
    }

    private fun spinner(items: List<String>): Spinner = Spinner(this).apply {
        adapter = ArrayAdapter(this@MainActivity, android.R.layout.simple_spinner_item, items).also {
            it.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        }
    }

    private fun simpleSelection(onSelected: (Int) -> Unit) = object : AdapterView.OnItemSelectedListener {
        override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) = onSelected(position)
        override fun onNothingSelected(parent: AdapterView<*>?) = Unit
    }

    private fun button(text: String, action: () -> Unit): Button = Button(this).apply {
        this.text = text
        setOnClickListener { action() }
    }

    private fun label(text: String): TextView = TextView(this).apply {
        this.text = text
        textSize = 14f
        setPadding(dp(2), dp(5), dp(2), dp(2))
    }

    private fun hint(text: String): TextView = label(text).apply { textSize = 12f; alpha = 0.72f }

    private fun editNumber(hintText: String, value: String, width: Int = ViewGroup.LayoutParams.MATCH_PARENT): EditText = EditText(this).apply {
        hint = hintText
        setText(value)
        inputType = android.text.InputType.TYPE_CLASS_NUMBER
        if (width != ViewGroup.LayoutParams.MATCH_PARENT) layoutParams = LinearLayout.LayoutParams(dp(width), dp(48))
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

    private fun simpleWatcher(action: (String) -> Unit) = object : android.text.TextWatcher {
        override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
        override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) = action(s?.toString().orEmpty())
        override fun afterTextChanged(s: android.text.Editable?) = Unit
    }

    companion object {
        private const val TAG = "Raw2DNG"
        private const val REQUEST_PICK = 11
        private const val REQUEST_FOLDER = 12
        private const val REQUEST_NOTIFICATIONS = 13
        private const val STATE_URIS = "queueUris"
        private const val SOURCE_URL = "https://github.com/PLACEHOLDER/raw2dng"
        private const val KEY_FORMAT = "format"
        private const val KEY_EMBED = "embed"
        private const val KEY_SOURCE = "source"
        private const val KEY_SIZE_PRESET = "sizePreset"
        private const val KEY_CUSTOM_SIZE = "customSize"
        private const val KEY_MAX_PRESET = "maxPreset"
        private const val KEY_CUSTOM_MAX = "customMax"
        private const val KEY_QUALITY = "quality"
        private const val KEY_FOLDER = "outputFolder"
        private const val ID_THUMB = 1001
        private const val ID_BODY = 1002
        private const val ID_REMOVE = 1003
        private val RAW_EXTENSIONS = setOf(
            "cr2", "cr3", "crw", "nef", "nrw", "arw", "srf", "sr2", "raf", "orf", "rw2", "pef", "dng", "srw",
            "3fr", "fff", "iiq", "erf", "mef", "mos", "mrw", "x3f", "kdc", "dcr", "rwl", "gpr", "ari", "bay", "cap", "eip", "r3d"
        )
    }
}
