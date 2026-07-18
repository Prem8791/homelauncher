package com.home.launcher.task

import android.app.ActivityManager
import android.content.ComponentName
import android.content.Context
import android.graphics.Bitmap
import android.hardware.HardwareBuffer
import android.os.IBinder
import android.os.Bundle
import android.util.Log

class ReflectionRecentTasksBackend(private val context: Context) : RecentTasksBackend {
    private val snapshotCache = mutableMapOf<Int, Bitmap>()

    private val iAtmProxy: Any by lazy {
        val sm = Class.forName("android.os.ServiceManager")
        val getService = sm.getMethod("getService", String::class.java)
        val binder = getService.invoke(null, "activity_task") as IBinder
        val stubClass = Class.forName("android.app.IActivityTaskManager\$Stub")
        val asInterface = stubClass.getMethod("asInterface", IBinder::class.java)
        asInterface.invoke(null, binder)!!
    }

    private val iAtmMethods: Map<String, java.lang.reflect.Method> by lazy {
        val cls = Class.forName("android.app.IActivityTaskManager")
        mapOf(
            "startActivityFromRecents" to cls.getMethod("startActivityFromRecents", Int::class.java, Bundle::class.java),
            "getTaskSnapshot" to cls.getMethod("getTaskSnapshot", Int::class.java, Boolean::class.java),
            "removeAllVisibleRecentTasks" to cls.getMethod("removeAllVisibleRecentTasks")
        )
    }

    private val atmMethods: Map<String, java.lang.reflect.Method> by lazy {
        val cls = Class.forName("android.app.ActivityTaskManager")
        val instance = cls.getMethod("getInstance").invoke(null)
        atmInstance = instance
        mapOf(
            "getRecentTasks" to cls.getMethod("getRecentTasks", Int::class.java, Int::class.java, Int::class.java),
            "removeTask" to cls.getMethod("removeTask", Int::class.java)
        )
    }

    private var atmInstance: Any? = null
    private var recentTaskInfoClass: Class<*>? = null

    private fun getRecentTaskInfoClass(): Class<*> {
        if (recentTaskInfoClass == null) {
            recentTaskInfoClass = Class.forName("android.app.ActivityManager\$RecentTaskInfo")
        }
        return recentTaskInfoClass!!
    }

    private fun getIntField(obj: Any, name: String): Int {
        return getRecentTaskInfoClass().getField(name).getInt(obj)
    }

    private fun getObjField(obj: Any, name: String): Any? {
        return getRecentTaskInfoClass().getField(name).get(obj)
    }

    override fun getRecentTasks(maxNum: Int): List<RecentTask> {
        return runCatching {
            val result = atmMethods["getRecentTasks"]!!.invoke(atmInstance, maxNum, 1, -2)

            val rawList = if (result.javaClass.name == "android.content.pm.ParceledListSlice") {
                val sliceClass = Class.forName("android.content.pm.ParceledListSlice")
                val getList = sliceClass.getMethod("getList")
                getList.invoke(result) as List<*>
            } else {
                result as List<*>
            }

            Log.i(TAG, "getRecentTasks returned ${rawList.size} tasks")

            rawList.mapNotNull { raw ->
                if (raw == null) return@mapNotNull null
                val taskId = getIntField(raw, "taskId")
                val baseIntent = getObjField(raw, "baseIntent") as? android.content.Intent
                val realActivity = getObjField(raw, "realActivity") as? ComponentName
                val userId = getIntField(raw, "userId")

                val packageName = realActivity?.packageName
                    ?: baseIntent?.`package`
                    ?: baseIntent?.component?.packageName
                    ?: return@mapNotNull null

                if (packageName == context.packageName) return@mapNotNull null

                val label = resolveLabel(raw, packageName, realActivity)
                RecentTask(taskId, packageName, label, userId)
            }
        }.onFailure {
            Log.e(TAG, "getRecentTasks failed", it)
        }.getOrDefault(emptyList())
    }

    private fun resolveLabel(
        raw: Any,
        packageName: String,
        realActivity: ComponentName?
    ): String? {
        val td = getObjField(raw, "taskDescription")
        if (td != null) {
            val tdLabel = runCatching {
                val cls = Class.forName("android.app.ActivityManager\$TaskDescription")
                val getLabel = cls.getMethod("getLabel")
                getLabel.invoke(td)?.toString()
            }.getOrNull()
            if (!tdLabel.isNullOrBlank()) return tdLabel
        }
        return runCatching {
            val appInfo = context.packageManager.getApplicationInfo(packageName, 0)
            context.packageManager.getApplicationLabel(appInfo).toString()
        }.getOrElse {
            realActivity?.shortClassName ?: packageName
        }
    }

    override fun removeTask(taskId: Int): Boolean {
        return runCatching {
            val result = atmMethods["removeTask"]!!.invoke(atmInstance, taskId) as Boolean
            Log.i(TAG, "removeTask($taskId) success=$result")
            result
        }.onFailure {
            Log.e(TAG, "removeTask($taskId) failed", it)
        }.getOrDefault(false)
    }

    override fun removeAllVisibleRecentTasks(): Boolean {
        return runCatching {
            iAtmMethods["removeAllVisibleRecentTasks"]!!.invoke(iAtmProxy)
            Log.i(TAG, "removeAllVisibleRecentTasks invoked")
            true
        }.onFailure {
            Log.e(TAG, "removeAllVisibleRecentTasks failed", it)
        }.getOrDefault(false)
    }

    override fun startTaskFromRecents(taskId: Int): Boolean {
        return runCatching {
            iAtmMethods["startActivityFromRecents"]!!.invoke(iAtmProxy, taskId, Bundle())
            Log.i(TAG, "startActivityFromRecents($taskId) invoked")
            true
        }.onFailure {
            Log.e(TAG, "startActivityFromRecents($taskId) failed", it)
        }.getOrDefault(false)
    }

    override fun getTaskSnapshot(taskId: Int, isLowResolution: Boolean): Bitmap? {
        snapshotCache[taskId]?.let { return it }
        val bitmap = readTaskSnapshot(taskId, isLowResolution)
        if (bitmap != null && !isProbablyBlank(bitmap)) {
            snapshotCache[taskId] = bitmap
            return bitmap
        }
        return null
    }

    private fun readTaskSnapshot(taskId: Int, isLowResolution: Boolean): Bitmap? {
        return runCatching {
            val snapshot = iAtmMethods["getTaskSnapshot"]!!.invoke(iAtmProxy, taskId, isLowResolution)
                ?: return@runCatching null

            val snapshotClass = Class.forName("android.window.TaskSnapshot")
            val hardwareBuffer = runCatching<HardwareBuffer> {
                snapshotClass.getMethod("getHardwareBuffer").invoke(snapshot) as HardwareBuffer
            }.recoverCatching {
                snapshotClass.getMethod("getSnapshot").invoke(snapshot) as HardwareBuffer
            }.recoverCatching {
                val hbField = snapshotClass.getDeclaredField("hardwareBuffer")
                hbField.isAccessible = true
                hbField.get(snapshot) as HardwareBuffer
            }.getOrNull() ?: return@runCatching null

            val bitmap = Bitmap.wrapHardwareBuffer(hardwareBuffer, null)
            hardwareBuffer.close()

            if (bitmap == null) {
                Log.w(TAG, "snapshot bitmap unavailable for task $taskId")
                return@runCatching null
            }
            bitmap
        }.onFailure {
            Log.w(TAG, "getTaskSnapshot failed for task $taskId", it)
        }.getOrNull()
    }

    private fun isProbablyBlank(bitmap: Bitmap): Boolean {
        val softwareBitmap = runCatching {
            if (bitmap.config == Bitmap.Config.HARDWARE) {
                bitmap.copy(Bitmap.Config.ARGB_8888, false)
            } else {
                bitmap
            }
        }.getOrNull() ?: return false

        if (softwareBitmap.width <= 0 || softwareBitmap.height <= 0) return true

        var minLuma = 255
        var maxLuma = 0
        var samples = 0
        val stepX = (softwareBitmap.width / BLANK_SAMPLE_GRID).coerceAtLeast(1)
        val stepY = (softwareBitmap.height / BLANK_SAMPLE_GRID).coerceAtLeast(1)

        var y = stepY / 2
        while (y < softwareBitmap.height) {
            var x = stepX / 2
            while (x < softwareBitmap.width) {
                val color = softwareBitmap.getPixel(x, y)
                val alpha = color ushr 24
                if (alpha > 8) {
                    val red = color shr 16 and 0xff
                    val green = color shr 8 and 0xff
                    val blue = color and 0xff
                    val luma = (red * 30 + green * 59 + blue * 11) / 100
                    minLuma = minOf(minLuma, luma)
                    maxLuma = maxOf(maxLuma, luma)
                    samples++
                }
                x += stepX
            }
            y += stepY
        }

        if (softwareBitmap !== bitmap) {
            softwareBitmap.recycle()
        }

        return samples > 0 && (maxLuma - minLuma) < BLANK_LUMA_RANGE_THRESHOLD
    }

    override fun forceStopPackage(packageName: String): Boolean {
        return runCatching {
            val am = context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
            val forceStop = ActivityManager::class.java.getMethod("forceStopPackage", String::class.java)
            forceStop.invoke(am, packageName)
            Log.i(TAG, "forceStopPackage($packageName) invoked")
            true
        }.onFailure {
            Log.e(TAG, "forceStopPackage($packageName) failed", it)
        }.getOrDefault(false)
    }

    override fun registerTaskChangeListener(
        onChanged: (snapshotTaskId: Int?) -> Unit
    ): TaskListenerRegistration? {
        return null
    }

    private companion object {
        const val TAG = "ReflectionRecentsBackend"
        const val BLANK_SAMPLE_GRID = 10
        const val BLANK_LUMA_RANGE_THRESHOLD = 6
    }
}
