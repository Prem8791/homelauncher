package com.home.launcher.system.platform

import android.app.ActivityManager
import android.app.ActivityTaskManager
import android.app.TaskStackListener
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Matrix
import android.hardware.HardwareBuffer
import android.os.Bundle
import android.os.UserHandle
import android.util.Log
import com.home.launcher.task.RecentTask
import com.home.launcher.task.RecentTasksBackend
import com.home.launcher.task.TaskListenerRegistration

class PlatformRecentTasksBackend(private val context: Context) : RecentTasksBackend {
    private val activityTaskManager: ActivityTaskManager = ActivityTaskManager.getInstance()

    override fun getRecentTasks(maxNum: Int): List<RecentTask> {
        return runCatching {
            val tasks = activityTaskManager.getRecentTasks(
                maxNum,
                ActivityManager.RECENT_WITH_EXCLUDED,
                UserHandle.USER_CURRENT
            )
            tasks.mapNotNull { task: ActivityManager.RecentTaskInfo ->
                val baseIntent = task.baseIntent
                val packageName = baseIntent?.component?.packageName ?: return@mapNotNull null
                val label = task.taskDescription?.label?.toString()
                    ?: baseIntent.component?.shortClassName
                RecentTask(task.taskId, packageName, label, task.userId)
            }
        }.onFailure {
            Log.e(TAG, "getRecentTasks failed", it)
        }.getOrDefault(emptyList())
    }

    override fun removeTask(taskId: Int): Boolean {
        return runCatching {
            val removed = activityTaskManager.removeTask(taskId)
            Log.i(TAG, "removeTask($taskId) success=$removed")
            removed
        }.onFailure {
            Log.e(TAG, "removeTask($taskId) failed", it)
        }.getOrDefault(false)
    }

    override fun removeAllVisibleRecentTasks(): Boolean {
        val taskIds = getRecentTasks(MAX_RECENTS_TO_REMOVE).map { task: RecentTask -> task.taskId }
        if (taskIds.isEmpty()) return false
        val removedCount = taskIds.count { taskId: Int -> removeTask(taskId) }
        Log.i(TAG, "removeAllVisibleRecentTasks removed $removedCount/${taskIds.size} tasks")
        return removedCount > 0
    }

    override fun startTaskFromRecents(taskId: Int): Boolean {
        return runCatching {
            val result = ActivityTaskManager.getService()
                .startActivityFromRecents(taskId, Bundle())
            Log.i(TAG, "startActivityFromRecents($taskId) result=$result")
            true
        }.onFailure {
            Log.e(TAG, "startActivityFromRecents($taskId) failed", it)
        }.getOrDefault(false)
    }

    override fun getTaskSnapshot(taskId: Int, isLowResolution: Boolean): Bitmap? {
        return runCatching {
            val snapshot = ActivityTaskManager.getService()
                .getTaskSnapshot(taskId, isLowResolution) ?: return@runCatching null
            val hardwareBuffer: HardwareBuffer = snapshot.hardwareBuffer ?: return@runCatching null
            val bitmap = Bitmap.wrapHardwareBuffer(hardwareBuffer, null)
            hardwareBuffer.close()

            if (bitmap == null) {
                Log.w(TAG, "snapshot bitmap unavailable for task $taskId")
                return@runCatching null
            }

            val orientation = snapshot.orientation
            if (orientation == 0) {
                bitmap
            } else {
                val matrix = Matrix()
                matrix.postRotate(orientation.toFloat())
                Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, matrix, true)
            }
        }.onFailure {
            Log.w(TAG, "getTaskSnapshot failed for task $taskId", it)
        }.getOrNull()
    }

    override fun forceStopPackage(packageName: String): Boolean {
        return runCatching {
            val am = context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
            am.forceStopPackage(packageName)
            Log.i(TAG, "forceStopPackage($packageName) invoked")
            true
        }.onFailure {
            Log.e(TAG, "forceStopPackage($packageName) failed", it)
        }.getOrDefault(false)
    }

    override fun registerTaskChangeListener(onChanged: () -> Unit): TaskListenerRegistration? {
        val listener = object : TaskStackListener() {
            override fun onTaskStackChanged() {
                onChanged()
            }

            override fun onTaskCreated(taskId: Int, componentName: android.content.ComponentName?) {
                onChanged()
            }

            override fun onTaskRemoved(taskId: Int) {
                onChanged()
            }

            override fun onTaskMovedToFront(taskInfo: ActivityManager.RunningTaskInfo?) {
                onChanged()
            }
        }

        return runCatching {
            ActivityTaskManager.getService().registerTaskStackListener(listener)
            Log.i(TAG, "TaskStackListener registered via ActivityTaskManager")
            TaskListenerRegistration {
                runCatching {
                    ActivityTaskManager.getService().unregisterTaskStackListener(listener)
                    Log.i(TAG, "TaskStackListener unregistered via ActivityTaskManager")
                }.onFailure {
                    Log.e(TAG, "unregisterTaskStackListener failed", it)
                }
            }
        }.onFailure {
            Log.e(TAG, "registerTaskStackListener failed", it)
        }.getOrNull()
    }

    private companion object {
        const val TAG = "PlatformRecentsBackend"
        const val MAX_RECENTS_TO_REMOVE = 100
    }
}
