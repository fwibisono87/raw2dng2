package com.raw2dng

import android.Manifest
import android.content.ContentResolver
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.provider.OpenableColumns
import android.util.Log
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import com.raw2dng.databinding.ActivityMainBinding
import java.io.File
import java.io.FileOutputStream
import java.text.SimpleDateFormat
import java.util.*

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private val converter = DNGConverter()
    private var conversionQueue: ConversionQueue? = null
    private val selectedFiles = mutableListOf<Uri>()
    private val tag = "MainActivity"

    private val pickFilesLauncher = registerForActivityResult(
        ActivityResultContracts.OpenMultipleDocuments()
    ) { uris: List<Uri> ->
        if (uris.isNotEmpty()) {
            selectedFiles.clear()
            selectedFiles.addAll(uris)
            updateSelectedFilesUI()
        }
    }

    private val requestPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { permissions ->
        val allGranted = permissions.entries.all { it.value }
        if (allGranted) {
            openFilePicker()
        } else {
            Toast.makeText(this, "Storage permission is required", Toast.LENGTH_SHORT).show()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        setupUI()
        checkSDKAvailability()
    }

    private fun setupUI() {
        binding.selectFilesButton.setOnClickListener {
            checkPermissionsAndPickFiles()
        }

        binding.convertButton.setOnClickListener {
            startConversion()
        }

        logMessage("App started. Ready to convert RAW files to DNG.")
    }

    private fun checkSDKAvailability() {
        val sdkVersion = converter.getSDKVersion()
        val isAvailable = converter.isSDKAvailable()

        logMessage("SDK Status: $sdkVersion")
        if (!isAvailable) {
            logMessage("WARNING: Adobe DNG SDK not available!")
            logMessage("Conversion will create placeholder files only.")
            logMessage("See README for SDK installation instructions.")
        }
    }

    private fun checkPermissionsAndPickFiles() {
        val permissions = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            arrayOf(Manifest.permission.READ_MEDIA_IMAGES)
        } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            arrayOf(Manifest.permission.READ_EXTERNAL_STORAGE)
        } else {
            arrayOf(
                Manifest.permission.READ_EXTERNAL_STORAGE,
                Manifest.permission.WRITE_EXTERNAL_STORAGE
            )
        }

        val allGranted = permissions.all {
            ContextCompat.checkSelfPermission(this, it) == PackageManager.PERMISSION_GRANTED
        }

        if (allGranted) {
            openFilePicker()
        } else {
            requestPermissionLauncher.launch(permissions)
        }
    }

    private fun openFilePicker() {
        try {
            // Accept common RAW file formats
            pickFilesLauncher.launch(arrayOf(
                "*/*",  // Allow all files for now
                "image/*"
            ))
        } catch (e: Exception) {
            Log.e(tag, "Error opening file picker", e)
            Toast.makeText(this, "Error opening file picker: ${e.message}", Toast.LENGTH_SHORT).show()
        }
    }

    private fun updateSelectedFilesUI() {
        val count = selectedFiles.size
        binding.selectedFilesText.text = if (count > 0) {
            getString(R.string.files_selected, count)
        } else {
            getString(R.string.no_files_selected)
        }
        binding.convertButton.isEnabled = count > 0
        logMessage("Selected $count file(s)")
    }

    private fun startConversion() {
        if (selectedFiles.isEmpty()) {
            Toast.makeText(this, "No files selected", Toast.LENGTH_SHORT).show()
            return
        }

        binding.convertButton.isEnabled = false
        binding.selectFilesButton.isEnabled = false
        binding.progressBar.visibility = android.view.View.VISIBLE
        binding.progressBar.max = selectedFiles.size
        binding.progressBar.progress = 0

        logMessage("Starting conversion of ${selectedFiles.size} file(s)...")

        // Create output directory
        val outputDir = getOutputDirectory()
        logMessage("Output directory: ${outputDir.absolutePath}")

        // Create conversion tasks
        val tasks = selectedFiles.mapNotNull { uri ->
            try {
                val fileName = getFileName(uri) ?: "unknown_${System.currentTimeMillis()}"
                val inputPath = copyUriToCache(uri, fileName)
                val outputFileName = fileName.replaceAfterLast('.', "dng")
                val outputPath = File(outputDir, outputFileName).absolutePath

                ConversionTask(uri, inputPath, outputPath, fileName)
            } catch (e: Exception) {
                Log.e(tag, "Error preparing task for $uri", e)
                logMessage("Error: ${e.message}")
                null
            }
        }

        // Create and start conversion queue
        conversionQueue = ConversionQueue(
            converter = converter,
            onProgress = { current, total ->
                runOnUiThread {
                    binding.progressBar.progress = current
                    binding.statusText.text = getString(R.string.converting, current, total)
                }
            },
            onTaskComplete = { result ->
                runOnUiThread {
                    if (result.success) {
                        logMessage("✓ ${result.task.fileName} -> ${File(result.task.outputPath).name}")
                    } else {
                        logMessage("✗ ${result.task.fileName}: ${result.errorMessage}")
                    }
                }
            },
            onAllComplete = { successful, failed ->
                runOnUiThread {
                    binding.convertButton.isEnabled = true
                    binding.selectFilesButton.isEnabled = true
                    binding.progressBar.visibility = android.view.View.GONE

                    val message = "Completed: $successful successful, $failed failed"
                    binding.statusText.text = message
                    logMessage(message)
                    logMessage("Output files saved to: ${outputDir.absolutePath}")

                    Toast.makeText(this, message, Toast.LENGTH_LONG).show()
                }
            }
        )

        conversionQueue?.addTasks(tasks)
        conversionQueue?.start()
    }

    private fun getOutputDirectory(): File {
        // Use app-specific directory
        val dir = File(
            getExternalFilesDir(Environment.DIRECTORY_DOCUMENTS),
            "Raw2DNG"
        )
        if (!dir.exists()) {
            dir.mkdirs()
        }
        return dir
    }

    private fun getFileName(uri: Uri): String? {
        var result: String? = null
        if (uri.scheme == "content") {
            contentResolver.query(uri, null, null, null, null)?.use { cursor ->
                if (cursor.moveToFirst()) {
                    val index = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                    if (index >= 0) {
                        result = cursor.getString(index)
                    }
                }
            }
        }
        if (result == null) {
            result = uri.path
            val cut = result?.lastIndexOf('/')
            if (cut != -1 && cut != null) {
                result = result?.substring(cut + 1)
            }
        }
        return result
    }

    private fun copyUriToCache(uri: Uri, fileName: String): String {
        val cacheFile = File(cacheDir, fileName)
        contentResolver.openInputStream(uri)?.use { input ->
            FileOutputStream(cacheFile).use { output ->
                input.copyTo(output)
            }
        }
        return cacheFile.absolutePath
    }

    private fun logMessage(message: String) {
        Log.d(tag, message)
        runOnUiThread {
            val timestamp = SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(Date())
            val currentLog = binding.logText.text.toString()
            binding.logText.text = if (currentLog.isEmpty()) {
                "[$timestamp] $message"
            } else {
                "$currentLog\n[$timestamp] $message"
            }

            // Auto-scroll to bottom
            binding.logScrollView.post {
                binding.logScrollView.fullScroll(android.view.View.FOCUS_DOWN)
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        conversionQueue?.cancel()
    }
}
