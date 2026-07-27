package dev.carthingspotify.controller.device

import android.app.Activity
import android.app.admin.DevicePolicyManager
import android.content.ComponentName
import android.content.Context
import android.content.IntentFilter
import android.content.Intent

class DeviceModeManager(private val activity: Activity) {
    private val dpm = activity.getSystemService(DevicePolicyManager::class.java)
    private val admin = ComponentName(activity, CarThingAdminReceiver::class.java)
    private val packageName = activity.packageName

    val isDeviceOwner: Boolean get() = dpm.isDeviceOwnerApp(packageName)
    val isLockTaskPermitted: Boolean get() = dpm.isLockTaskPermitted(packageName)

    fun applyDedicatedMode() {
        if (!isDeviceOwner) return
        dpm.setLockTaskPackages(admin, arrayOf(packageName))
        dpm.setLockTaskFeatures(admin, DevicePolicyManager.LOCK_TASK_FEATURE_NONE)
        try { dpm.setStatusBarDisabled(admin, true) } catch (_: Exception) { }
        try { dpm.setKeyguardDisabled(admin, true) } catch (_: Exception) { }
        val home = IntentFilter(Intent.ACTION_MAIN).apply {
            addCategory(Intent.CATEGORY_HOME)
            addCategory(Intent.CATEGORY_DEFAULT)
        }
        dpm.addPersistentPreferredActivity(admin, home, ComponentName(activity, activity::class.java))
        try { activity.startLockTask() } catch (_: Exception) { }
    }

    fun pauseLockTask() {
        try { activity.stopLockTask() } catch (_: Exception) { }
    }

    @Suppress("DEPRECATION")
    fun leaveDedicatedModePermanently() {
        pauseLockTask()
        if (!isDeviceOwner) return
        try { dpm.setStatusBarDisabled(admin, false) } catch (_: Exception) { }
        try { dpm.setKeyguardDisabled(admin, false) } catch (_: Exception) { }
        try { dpm.clearPackagePersistentPreferredActivities(admin, packageName) } catch (_: Exception) { }
        try { dpm.setLockTaskPackages(admin, emptyArray()) } catch (_: Exception) { }
        dpm.clearDeviceOwnerApp(packageName)
    }
}
