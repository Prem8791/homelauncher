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

        fun createBestBackend(context: Context): RecentTasksBackend {
            return try {
                val taskOrganizerClass = Class.forName("android.window.TaskOrganizer")
                val ctor = taskOrganizerClass.getDeclaredConstructors().firstOrNull()
                if (ctor != null) {
                    Log.i(TAG, "TaskOrganizer available, using new backend")
                    val backendClass = Class.forName("com.home.launcher.task.TaskOrganizerRecentTasksBackend")
                    backendClass.getConstructor(Context::class.java).newInstance(context) as RecentTasksBackend
                } else {
                    Log.i(TAG, "TaskOrganizer ctor not found, falling back to reflection")
                    ReflectionRecentTasksBackend(context.applicationContext)
                }
            } catch (e: Exception) {
                Log.i(TAG, "TaskOrganizer not available, falling back to reflection backend", e)
                ReflectionRecentTasksBackend(context.applicationContext)
            }
        }
    }
}
