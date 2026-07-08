package com.home.launcher

import android.app.ActivityManager
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Bitmap.Config
import android.graphics.Canvas
import android.graphics.Matrix
import android.hardware.HardwareBuffer
import android.os.Build
import android.util.Log
import java.lang.reflect.InvocationHandler
import java.lang.reflect.Method
import java.lang.reflect.Proxy

object HiddenApi {
    private const val TAG = "HiddenApi"

    private var activityTaskManager: Any? = null
    private var iAtm: Any? = null

    init {
        try {
            val atmClass = Class.forName("android.app.ActivityTaskManager")
            val getService = atmClass.getDeclaredMethod("getService")
            getService.isAccessible = true
            iAtm = getService.invoke(null)
            Log.i(TAG, "IActivityTaskManager obtained: $iAtm")
        } catch (e: Exception) {
            Log.w(TAG, "Failed to get IActivityTaskManager via getService()", e)
            try {
                val serviceManager = Class.forName("android.os.ServiceManager")
                val getService = serviceManager.getDeclaredMethod("getService", String::class.java)
                val binder = getService.invoke(null, "activity_task")
                val asInterface = Class.forName("android.app.IActivityTaskManager\$Stub")
                    .getDeclaredMethod("asInterface", android.os.IBinder::class.java)
                iAtm = asInterface.invoke(null, binder)
                Log.i(TAG, "IActivityTaskManager obtained via ServiceManager: $iAtm")
            } catch (e2: Exception) {
                Log.e(TAG, "Failed to get IActivityTaskManager", e2)
            }
        }
        try {
            val atmClass = Class.forName("android.app.ActivityTaskManager")
            activityTaskManager = atmClass
                .getDeclaredMethod("getInstance")
                .invoke(null)
            Log.i(TAG, "ActivityTaskManager instance: $activityTaskManager")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to get ActivityTaskManager instance", e)
        }
    }

    data class SimpleTaskInfo(
        val taskId: Int,
        val packageName: String,
        val label: String?,
        val userId: Int
    )

    fun getRecentTasks(maxNum: Int = 20): List<SimpleTaskInfo> {
        val result = mutableListOf<SimpleTaskInfo>()
        try {
            if (iAtm != null) {
                val raw = try {
                    iAtm!!.javaClass
                        .getDeclaredMethod("getRecentTasks", Int::class.java, Int::class.java, Int::class.java)
                        .invoke(iAtm, maxNum, 1, 0)
                } catch (e: Exception) {
                    Log.e(TAG, "getRecentTasks 3-param failed", e)
                    null
                }

                val taskList = when (raw) {
                    is List<*> -> raw
                    else -> {
                        try {
                            raw?.javaClass?.getDeclaredMethod("getList")?.invoke(raw) as? List<*>
                        } catch (e: Exception) {
                            Log.e(TAG, "Failed to unwrap list", e)
                            null
                        }
                    }
                } ?: return result

                val taskInfoClass = Class.forName("android.app.TaskInfo")
                val fTaskId = taskInfoClass.getField("taskId")
                val fBaseIntent = taskInfoClass.getField("baseIntent")
                val fUserId = taskInfoClass.getField("userId")
                val fTaskDescription = try {
                    taskInfoClass.getField("taskDescription")
                } catch (e: NoSuchFieldException) {
                    null
                }

                Log.i(TAG, "getRecentTasks: ${taskList.size} raw tasks, parsing...")
                var parsed = 0
                for (task in taskList) {
                    try {
                        val taskId = fTaskId.getInt(task)
                        val baseIntent = fBaseIntent.get(task) as? android.content.Intent
                        val userId = fUserId.getInt(task)
                        val taskDescription = fTaskDescription?.get(task)

                        val packageName = baseIntent?.component?.packageName ?: "unknown"
                        val label: String? = if (taskDescription != null) {
                            try {
                                taskDescription.javaClass.getDeclaredMethod("getLabel").invoke(taskDescription) as? String
                            } catch (e: Exception) {
                                baseIntent?.component?.shortClassName
                            }
                        } else {
                            baseIntent?.component?.shortClassName
                        }

                        result.add(SimpleTaskInfo(taskId, packageName, label, userId))
                        parsed++
                    } catch (e: Exception) {
                        Log.w(TAG, "Error parsing task info for task at index ${taskList.indexOf(task)}", e)
                    }
                }
                Log.i(TAG, "getRecentTasks: parsed $parsed/${taskList.size} tasks")
            }
        } catch (e: Exception) {
            Log.e(TAG, "getRecentTasks failed", e)
        }
        return result
    }

    fun registerTaskStackListener(listener: Any): Boolean {
        try {
            if (iAtm != null) {
                val registerMethod = iAtm!!.javaClass
                    .getDeclaredMethod("registerTaskStackListener", Class.forName("android.app.ITaskStackListener"))
                registerMethod.invoke(iAtm, listener)
                Log.i(TAG, "TaskStackListener registered via IActivityTaskManager")
                return true
            }
        } catch (e: Exception) {
            Log.e(TAG, "registerTaskStackListener failed", e)
        }
        return false
    }

    fun unregisterTaskStackListener(listener: Any): Boolean {
        try {
            if (iAtm != null) {
                val unregisterMethod = iAtm!!.javaClass
                    .getDeclaredMethod("unregisterTaskStackListener", Class.forName("android.app.ITaskStackListener"))
                unregisterMethod.invoke(iAtm, listener)
                Log.i(TAG, "TaskStackListener unregistered via IActivityTaskManager")
                return true
            }
        } catch (e: Exception) {
            Log.e(TAG, "unregisterTaskStackListener failed", e)
        }
        return false
    }

    fun removeTask(taskId: Int): Boolean {
        try {
            if (iAtm != null) {
                iAtm!!.javaClass
                    .getDeclaredMethod("removeTask", Int::class.java)
                    .invoke(iAtm, taskId)
                Log.i(TAG, "Task $taskId removed")
                return true
            }
        } catch (e: Exception) {
            Log.e(TAG, "removeTask failed", e)
        }
        return false
    }

    fun removeAllRecentTasks(): Boolean {
        try {
            if (iAtm != null) {
                iAtm!!.javaClass
                    .getDeclaredMethod("removeAllVisibleRecentTasks")
                    .invoke(iAtm)
                Log.i(TAG, "All visible recent tasks removed")
                return true
            }
        } catch (e: Exception) {
            Log.e(TAG, "removeAllVisibleRecentTasks failed", e)
        }
        return false
    }

    fun startActivityFromRecents(taskId: Int): Boolean {
        try {
            if (iAtm != null) {
                iAtm!!.javaClass
                    .getDeclaredMethod("startActivityFromRecents", Int::class.java, String::class.java)
                    .invoke(iAtm, taskId, null)
                Log.i(TAG, "Started activity from recents: $taskId")
                return true
            }
        } catch (e: Exception) {
            Log.e(TAG, "startActivityFromRecents failed", e)
        }
        return false
    }

    fun getTaskSnapshot(taskId: Int, isLowResolution: Boolean = false): Bitmap? {
        try {
            if (iAtm != null) {
                val rawSnapshot = iAtm!!.javaClass
                    .getDeclaredMethod("getTaskSnapshot", Int::class.java, Boolean::class.java)
                    .invoke(iAtm, taskId, isLowResolution)
                if (rawSnapshot == null) {
                    Log.i(TAG, "getTaskSnapshot($taskId): raw snapshot is null")
                    return null
                }
                val snapshotClass = rawSnapshot.javaClass

                val getHardwareBuffer = try {
                    snapshotClass.getDeclaredMethod("getHardwareBuffer")
                } catch (e: NoSuchMethodException) {
                    snapshotClass.getDeclaredMethod("getSnapshot")
                }
                val getOrientation = try {
                    snapshotClass.getDeclaredMethod("getOrientation")
                } catch (e: NoSuchMethodException) { null }

                val hb = getHardwareBuffer.invoke(rawSnapshot) as? HardwareBuffer
                if (hb == null) return null

                val orientation = getOrientation?.invoke(rawSnapshot) as? Int ?: 0
                val bitmap = Bitmap.wrapHardwareBuffer(hb, null)
                hb.close()
                if (bitmap == null) return null

                if (orientation != 0) {
                    val matrix = Matrix()
                    matrix.postRotate(orientation.toFloat())
                    return Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, matrix, true)
                }
                return bitmap
            }
        } catch (e: Exception) {
            Log.w(TAG, "getTaskSnapshot failed for task $taskId", e)
        }
        return null
    }

    fun forceStopPackage(context: Context, packageName: String): Boolean {
        try {
            val am = context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
            val method = Class.forName("android.app.ActivityManager").getDeclaredMethod("forceStopPackage", String::class.java)
            method.invoke(am, packageName)
            Log.i(TAG, "Force stopped: $packageName")
            return true
        } catch (e: Exception) {
            Log.e(TAG, "forceStopPackage failed for $packageName", e)
        }
        return false
    }

    fun buildTaskStackListener(onTaskStackChanged: () -> Unit): Any? {
        return try {
            val iTaskStackListener = Class.forName("android.app.ITaskStackListener")
            val proxy = Proxy.newProxyInstance(
                iTaskStackListener.classLoader,
                arrayOf(iTaskStackListener)
            ) { _, method, _ ->
                when (method.name) {
                    "onTaskStackChanged" -> onTaskStackChanged()
                    "onTaskAdded", "onTaskRemoved", "onTaskMovedToFront" -> onTaskStackChanged()
                    else -> null
                }
            }
            proxy
        } catch (e: Exception) {
            Log.e(TAG, "Failed to build TaskStackListener", e)
            null
        }
    }

    fun getAppIconForTask(context: Context, packageName: String): android.graphics.drawable.Drawable? {
        return try {
            val pm = context.packageManager
            val info = pm.getApplicationInfo(packageName, PackageManager.GET_META_DATA)
            info.loadIcon(pm)
        } catch (e: Exception) {
            null
        }
    }

    fun getAppLabelForTask(context: Context, packageName: String): String? {
        return try {
            val pm = context.packageManager
            val info = pm.getApplicationInfo(packageName, PackageManager.GET_META_DATA)
            info.loadLabel(pm).toString()
        } catch (e: Exception) {
            packageName
        }
    }
}
