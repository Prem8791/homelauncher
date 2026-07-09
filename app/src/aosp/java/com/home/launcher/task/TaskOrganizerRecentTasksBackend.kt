package com.home.launcher.task

import android.app.ActivityManager
import android.content.Context
import android.graphics.Bitmap
import android.util.Log
import android.window.TaskOrganizer
import com.home.launcher.system.hiddenapi.HiddenApiBridge

class TaskOrganizerRecentTasksBackend(private val context: Context) : RecentTasksBackend {
    private val taskOrganizer = TaskOrganizer()

    override fun getRecentTasks(maxNum: Int): List<RecentTask> {
        val tasks = runCatching { taskOrganizer.getRecentTasks() }
            .onFailure { Log.e(TAG, "getRecentTasks failed", it) }
            .getOrNull() ?: return emptyList()

        val sample = tasks.firstOrNull() ?: return emptyList()
        val sampleClass = sample.javaClass
        val fTaskId = runCatching {
            sampleClass.getDeclaredField("taskId").also { it.isAccessible = true }
        }.getOrNull() ?: return emptyList()
        val fBaseIntent = runCatching {
            sampleClass.getDeclaredField("baseIntent").also { it.isAccessible = true }
        }.getOrNull() ?: return emptyList()
        val fUserId = runCatching {
            sampleClass.getDeclaredField("userId").also { it.isAccessible = true }
        }.getOrNull()

        return tasks.mapNotNull { task ->
            runCatching {
                val taskId = fTaskId.getInt(task)
                val baseIntent = fBaseIntent.get(task) as? android.content.Intent
                val userId = fUserId?.getInt(task) ?: 0
                val packageName = baseIntent?.component?.packageName ?: return@runCatching null
                RecentTask(taskId, packageName, baseIntent.component?.shortClassName, userId)
            }.onFailure {
                Log.w(TAG, "failed to parse RunningTaskInfo", it)
            }.getOrNull()
        }.take(maxNum)
    }

    override fun removeTask(taskId: Int): Boolean {
        return runCatching {
            taskOrganizer.removeRecentTasks(listOf(taskId))
            Log.i(TAG, "removeTask($taskId) via TaskOrganizer")
            true
        }.onFailure {
            Log.e(TAG, "removeTask($taskId) failed", it)
        }.getOrDefault(false)
    }

    override fun removeAllVisibleRecentTasks(): Boolean {
        val tasks = runCatching { taskOrganizer.getRecentTasks() }.getOrNull() ?: return false
        val fTaskId = tasks.firstOrNull()?.let {
            runCatching { it.javaClass.getDeclaredField("taskId").also { f -> f.isAccessible = true } }.getOrNull()
        } ?: return false
        val visibleIds = tasks.mapNotNull { task ->
            runCatching { fTaskId.getInt(task) }.getOrNull()
        }
        if (visibleIds.isEmpty()) return false
        return runCatching {
            taskOrganizer.removeRecentTasks(visibleIds)
            Log.i(TAG, "removeAllVisibleRecentTasks removed ${visibleIds.size} tasks")
            true
        }.onFailure {
            Log.e(TAG, "removeAllVisibleRecentTasks failed", it)
        }.getOrDefault(false)
    }

    override fun startTaskFromRecents(taskId: Int): Boolean {
        return runCatching {
            taskOrganizer.startActivityFromRecents(taskId, null)
            Log.i(TAG, "startTaskFromRecents($taskId) invoked")
            true
        }.onFailure {
            Log.e(TAG, "startTaskFromRecents($taskId) failed", it)
        }.getOrDefault(false)
    }

    override fun getTaskSnapshot(taskId: Int, isLowResolution: Boolean): Bitmap? {
        val iAtm = HiddenApiBridge.activityTaskManager ?: return null
        return SnapshotCapture.getTaskSnapshot(iAtm, taskId, isLowResolution)
    }

    override fun forceStopPackage(packageName: String): Boolean {
        return runCatching {
            val am = context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
            val method = Class.forName("android.app.ActivityManager")
                .getDeclaredMethod("forceStopPackage", String::class.java)
            method.isAccessible = true
            method.invoke(am, packageName)
            Log.i(TAG, "forceStopPackage($packageName) invoked")
            true
        }.onFailure {
            Log.e(TAG, "forceStopPackage($packageName) failed", it)
        }.getOrDefault(false)
    }

    override fun registerTaskChangeListener(onChanged: () -> Unit): TaskListenerRegistration? {
        Log.i(TAG, "TaskOrganizer task change listener unavailable on this platform build")
        return null
    }

    private companion object {
        const val TAG = "TaskOrganizerBackend"
    }
}

internal object SnapshotCapture {
    fun getTaskSnapshot(iAtm: Any?, taskId: Int, isLowResolution: Boolean): Bitmap? {
        if (iAtm == null) return null
        val rawSnapshot = HiddenApiBridge.invoke(iAtm, "getTaskSnapshot", taskId, isLowResolution)
        if (rawSnapshot == null) return null

        return runCatching {
            val snapshotClass = rawSnapshot.javaClass
            val hardwareBuffer = runCatching {
                snapshotClass.getDeclaredMethod("getHardwareBuffer").invoke(rawSnapshot) as? android.hardware.HardwareBuffer
            }.getOrNull() ?: return@runCatching null

            val bitmap = Bitmap.wrapHardwareBuffer(hardwareBuffer, null) ?: return@runCatching null
            hardwareBuffer.close()

            val orientation = runCatching {
                snapshotClass.getDeclaredMethod("getOrientation").invoke(rawSnapshot) as? Int
            }.getOrDefault(0) ?: 0

            if (orientation == 0) bitmap
            else {
                val matrix = android.graphics.Matrix()
                matrix.postRotate(orientation.toFloat())
                Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, matrix, true)
            }
        }.onFailure {
            Log.w(TAG, "getTaskSnapshot failed for task $taskId", it)
        }.getOrNull()
    }

    private const val TAG = "SnapshotCapture"
}
