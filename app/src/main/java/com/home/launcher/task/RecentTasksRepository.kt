package com.home.launcher.task

import android.content.Context
import android.graphics.Bitmap
import android.util.Log
import com.home.launcher.system.hiddenapi.ReflectionRecentTasksBackend

class RecentTasksRepository(
    context: Context,
    private val backend: RecentTasksBackend = createBestBackend(context)
) {
    fun getRecentTasks(maxNum: Int): List<RecentTask> = backend.getRecentTasks(maxNum)

    fun removeTask(taskId: Int): Boolean = backend.removeTask(taskId)

    fun removeAllVisibleRecentTasks(): Boolean = backend.removeAllVisibleRecentTasks()

    fun startTaskFromRecents(taskId: Int): Boolean = backend.startTaskFromRecents(taskId)

    fun getTaskSnapshot(taskId: Int, isLowResolution: Boolean = false): Bitmap? =
        backend.getTaskSnapshot(taskId, isLowResolution)

    fun forceStopPackage(packageName: String): Boolean = backend.forceStopPackage(packageName)

    fun registerTaskChangeListener(onChanged: () -> Unit): TaskListenerRegistration? =
        backend.registerTaskChangeListener(onChanged)

    private companion object {
        private const val TAG = "RecentTasksRepo"
        private const val PLATFORM_BACKEND =
            "com.home.launcher.system.platform.PlatformRecentTasksBackend"

        fun createBestBackend(context: Context): RecentTasksBackend {
            val platformBackend = runCatching {
                Class.forName(PLATFORM_BACKEND)
                    .getConstructor(Context::class.java)
                    .newInstance(context.applicationContext) as RecentTasksBackend
            }.onFailure {
                Log.i(TAG, "Platform recents backend unavailable; using reflection fallback", it)
            }.getOrNull()

            if (platformBackend != null) {
                Log.i(TAG, "Using platform ActivityTaskManager recents backend")
                return platformBackend
            }

            return ReflectionRecentTasksBackend(context.applicationContext)
        }
    }
}
