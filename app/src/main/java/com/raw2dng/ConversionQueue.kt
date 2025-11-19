package com.raw2dng

import android.net.Uri
import android.util.Log
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import java.io.File

data class ConversionTask(
    val inputUri: Uri,
    val inputPath: String,
    val outputPath: String,
    val fileName: String
)

data class ConversionResult(
    val task: ConversionTask,
    val success: Boolean,
    val errorMessage: String = ""
)

class ConversionQueue(
    private val converter: DNGConverter,
    private val onProgress: (current: Int, total: Int) -> Unit,
    private val onTaskComplete: (ConversionResult) -> Unit,
    private val onAllComplete: (successful: Int, failed: Int) -> Unit
) {
    private val tag = "ConversionQueue"
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private val taskChannel = Channel<ConversionTask>(Channel.UNLIMITED)
    private val tasks = mutableListOf<ConversionTask>()
    private var isProcessing = false
    private var completed = 0
    private var successful = 0
    private var failed = 0

    fun addTasks(newTasks: List<ConversionTask>) {
        tasks.addAll(newTasks)
    }

    fun start() {
        if (isProcessing) {
            Log.w(tag, "Queue is already processing")
            return
        }

        isProcessing = true
        completed = 0
        successful = 0
        failed = 0

        scope.launch {
            // Add all tasks to the channel
            for (task in tasks) {
                taskChannel.send(task)
            }

            // Process tasks sequentially
            for (task in tasks) {
                processTask(task)
            }

            // All tasks completed
            isProcessing = false
            withContext(Dispatchers.Main) {
                onAllComplete(successful, failed)
            }
        }
    }

    private suspend fun processTask(task: ConversionTask) {
        withContext(Dispatchers.IO) {
            try {
                Log.d(tag, "Processing: ${task.fileName}")

                completed++
                withContext(Dispatchers.Main) {
                    onProgress(completed, tasks.size)
                }

                // Perform the conversion
                val errorMessage = converter.convertToDNG(task.inputPath, task.outputPath)

                val result = if (errorMessage.isEmpty()) {
                    successful++
                    Log.d(tag, "Success: ${task.fileName}")
                    ConversionResult(task, true)
                } else {
                    failed++
                    Log.e(tag, "Failed: ${task.fileName} - $errorMessage")
                    ConversionResult(task, false, errorMessage)
                }

                withContext(Dispatchers.Main) {
                    onTaskComplete(result)
                }

            } catch (e: Exception) {
                failed++
                Log.e(tag, "Exception processing ${task.fileName}", e)
                withContext(Dispatchers.Main) {
                    onTaskComplete(ConversionResult(task, false, e.message ?: "Unknown error"))
                }
            }
        }
    }

    fun clear() {
        tasks.clear()
        completed = 0
        successful = 0
        failed = 0
    }

    fun cancel() {
        scope.cancel()
        isProcessing = false
    }

    fun getTaskCount() = tasks.size
}
