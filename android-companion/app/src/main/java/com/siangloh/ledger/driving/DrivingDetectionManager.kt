package com.siangloh.ledger.driving

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.bluetooth.BluetoothClass
import android.bluetooth.BluetoothDevice
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.os.Build
import android.util.Log
import androidx.core.app.NotificationCompat
import com.google.android.gms.location.ActivityRecognition
import com.google.android.gms.location.ActivityTransition
import com.google.android.gms.location.ActivityTransitionRequest
import com.google.android.gms.location.DetectedActivity
import com.siangloh.ledger.R
import com.siangloh.ledger.ui.QuickAddActivity

object DrivingDetectionManager {

    private const val TAG = "DrivingDetection"
    private const val PREFS_NAME = "ledger_driving_prefs"
    private const val CHANNEL_ID = "channel_driving_tracker"

    private const val KEY_ENABLED = "driving_tracking_enabled"
    private const val KEY_CAR_BT_MAC = "selected_car_bt_mac"
    private const val KEY_CAR_BT_NAME = "selected_car_bt_name"
    private const val KEY_IS_DRIVING = "is_currently_driving"
    private const val KEY_DRIVING_START_TIME = "driving_start_timestamp"
    private const val KEY_DRIVING_SOURCE = "driving_source" // "bluetooth" or "google_activity"

    private val CAR_NAME_KEYWORDS = listOf(
        "car", "handsfree", "carkit", "carplay", "android auto", "bt car", "car bt", "car audio",
        "honda", "toyota", "proton", "perodua", "nissan", "bmw", "mercedes", "audi", "mazda",
        "hyundai", "kia", "volvo", "byd", "porsche", "subaru", "mitsubishi", "ford sync", "myvi", "bezza", "saga", "alza"
    )

    private fun getPrefs(context: Context): SharedPreferences {
        return context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    }

    fun isTrackingEnabled(context: Context): Boolean {
        return getPrefs(context).getBoolean(KEY_ENABLED, true)
    }

    fun setTrackingEnabled(context: Context, enabled: Boolean) {
        getPrefs(context).edit().putBoolean(KEY_ENABLED, enabled).apply()
        if (enabled) {
            requestActivityTransitionUpdates(context)
        } else {
            removeActivityTransitionUpdates(context)
            if (isCurrentlyDriving(context)) {
                setDrivingState(context, false, "disabled")
            }
        }
    }

    fun getSavedCarBluetoothMac(context: Context): String {
        return getPrefs(context).getString(KEY_CAR_BT_MAC, "") ?: ""
    }

    fun getSavedCarBluetoothName(context: Context): String {
        return getPrefs(context).getString(KEY_CAR_BT_NAME, "") ?: ""
    }

    fun setSavedCarBluetooth(context: Context, mac: String, name: String) {
        getPrefs(context).edit()
            .putString(KEY_CAR_BT_MAC, mac.trim())
            .putString(KEY_CAR_BT_NAME, name.trim())
            .apply()
    }

    fun isCurrentlyDriving(context: Context): Boolean {
        return getPrefs(context).getBoolean(KEY_IS_DRIVING, false)
    }

    fun getDrivingStartTime(context: Context): Long {
        return getPrefs(context).getLong(KEY_DRIVING_START_TIME, 0L)
    }

    fun getDrivingSource(context: Context): String {
        return getPrefs(context).getString(KEY_DRIVING_SOURCE, "none") ?: "none"
    }

    /**
     * 判断连接/断开的蓝牙设备是否属于汽车设备
     * 1. 优先比对用户手动绑定的车机 MAC 地址
     * 2. 匹配硬件 Device Class (AUDIO_VIDEO_CAR_AUDIO)
     * 3. 匹配特征名称关键词 (Proton, Perodua, CarKit, HandsFree 等)
     */
    fun isCarBluetooth(context: Context, device: BluetoothDevice?): Boolean {
        if (device == null) return false

        val savedMac = getSavedCarBluetoothMac(context)
        if (savedMac.isNotEmpty() && device.address.equals(savedMac, ignoreCase = true)) {
            return true
        }

        // 1. 硬件类型检查
        val btClass = device.bluetoothClass
        if (btClass != null) {
            val devClass = btClass.deviceClass
            if (devClass == BluetoothClass.Device.AUDIO_VIDEO_CAR_AUDIO) {
                return true
            }
        }

        // 2. 名称特征匹配
        val devName = try {
            device.name?.lowercase() ?: ""
        } catch (_: SecurityException) {
            ""
        }
        if (devName.isNotEmpty() && CAR_NAME_KEYWORDS.any { devName.contains(it) }) {
            return true
        }

        return false
    }

    /**
     * 状态更新引擎：协调 蓝牙 与 Google Activity Recognition
     */
    @Synchronized
    fun setDrivingState(context: Context, isDriving: Boolean, source: String, deviceName: String? = null) {
        if (!isTrackingEnabled(context) && isDriving) return

        val current = isCurrentlyDriving(context)
        val currentSource = getDrivingSource(context)
        val now = System.currentTimeMillis()

        // 优先级保护：如果当前正由蓝牙连接驱动，禁止 Google 活动识别的微弱跳变提前退出驾车状态
        if (!isDriving && current && currentSource == "bluetooth" && source == "google_activity") {
            Log.d(TAG, "已保持车机蓝牙连接，忽略 Google API 的临时 EXIT 跳变")
            return
        }

        if (current == isDriving) {
            // 状态无跳变，但如果是蓝牙连上则提升为蓝牙权威来源
            if (isDriving && source == "bluetooth" && currentSource != "bluetooth") {
                getPrefs(context).edit().putString(KEY_DRIVING_SOURCE, "bluetooth").apply()
                Log.i(TAG, "驾车来源已升级为车机蓝牙绑定: $deviceName")
            }
            return
        }

        val prefs = getPrefs(context)
        if (isDriving) {
            // 开始驾车
            prefs.edit()
                .putBoolean(KEY_IS_DRIVING, true)
                .putLong(KEY_DRIVING_START_TIME, now)
                .putString(KEY_DRIVING_SOURCE, source)
                .apply()

            val srcDesc = if (source == "bluetooth") "车载蓝牙 [${deviceName ?: "车机"}]" else "Google 活动感知 (IN_VEHICLE)"
            Log.i(TAG, "🚗 侦测到进入驾车状态 ($srcDesc)")
        } else {
            // 结束驾车
            val startTime = prefs.getLong(KEY_DRIVING_START_TIME, 0L)
            val durationMinutes = if (startTime > 0) ((now - startTime) / 60000L).toInt() else 0

            prefs.edit()
                .putBoolean(KEY_IS_DRIVING, false)
                .putLong(KEY_DRIVING_START_TIME, 0L)
                .putString(KEY_DRIVING_SOURCE, "none")
                .apply()

            Log.i(TAG, "🛑 侦测到已结束驾车，本次行程历时约 $durationMinutes 分钟")

            // 行程超过 1 分钟，发出友好记账提醒通知
            if (durationMinutes >= 1) {
                notifyTripEnded(context, durationMinutes)
            }
        }
    }

    /**
     * 注册 Google Play Services 活动跳变识别 (Activity Transition API)
     * 利用手机协处理器低频检测 IN_VEHICLE 状态，100% 免费且微功耗
     */
    fun requestActivityTransitionUpdates(context: Context) {
        if (!isTrackingEnabled(context)) return

        try {
            val transitions = listOf(
                ActivityTransition.Builder()
                    .setActivityType(DetectedActivity.IN_VEHICLE)
                    .setActivityTransition(ActivityTransition.ACTIVITY_TRANSITION_ENTER)
                    .build(),
                ActivityTransition.Builder()
                    .setActivityType(DetectedActivity.IN_VEHICLE)
                    .setActivityTransition(ActivityTransition.ACTIVITY_TRANSITION_EXIT)
                    .build()
            )

            val request = ActivityTransitionRequest(transitions)
            val intent = Intent(context, DrivingTransitionReceiver::class.java)
            val pendingIntent = PendingIntent.getBroadcast(
                context,
                2001,
                intent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE
            )

            ActivityRecognition.getClient(context)
                .requestActivityTransitionUpdates(request, pendingIntent)
                .addOnSuccessListener {
                    Log.i(TAG, "Google Activity Recognition Transition API 注册成功 (IN_VEHICLE 监听中)")
                }
                .addOnFailureListener { e ->
                    Log.w(TAG, "Google Activity Recognition 注册未成功 (若无 GMS 环境将由蓝牙独占): ${e.message}")
                }
        } catch (e: Exception) {
            Log.e(TAG, "requestActivityTransitionUpdates 异常: ${e.message}")
        }
    }

    fun removeActivityTransitionUpdates(context: Context) {
        try {
            val intent = Intent(context, DrivingTransitionReceiver::class.java)
            val pendingIntent = PendingIntent.getBroadcast(
                context,
                2001,
                intent,
                PendingIntent.FLAG_NO_CREATE or PendingIntent.FLAG_MUTABLE
            ) ?: return

            ActivityRecognition.getClient(context).removeActivityTransitionUpdates(pendingIntent)
            Log.i(TAG, "已移除 Google Activity Recognition 监听")
        } catch (e: Exception) {
            Log.e(TAG, "removeActivityTransitionUpdates 异常: ${e.message}")
        }
    }

    private fun notifyTripEnded(context: Context, durationMinutes: Int) {
        try {
            val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager ?: return

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                val channel = NotificationChannel(
                    CHANNEL_ID,
                    "驾车出行与记账提醒",
                    NotificationManager.IMPORTANCE_DEFAULT
                ).apply {
                    description = "驾车结束后提醒记录过路费、加油与停车费"
                }
                nm.createNotificationChannel(channel)
            }

            val quickAddIntent = Intent(context, QuickAddActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
                putExtra("prefill_category", "交通")
                putExtra("prefill_note", "驾车出行支出")
            }

            val pi = PendingIntent.getActivity(
                context,
                2002,
                quickAddIntent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )

            val notif = NotificationCompat.Builder(context, CHANNEL_ID)
                .setSmallIcon(R.mipmap.ic_launcher)
                .setContentTitle("🚗 行程已结束 (约 $durationMinutes 分钟)")
                .setContentText("是否有产生加油、TNG 过路费或停车费？点击快速记账")
                .setAutoCancel(true)
                .setContentIntent(pi)
                .setPriority(NotificationCompat.PRIORITY_DEFAULT)
                .build()

            nm.notify(2026, notif)
        } catch (e: Exception) {
            Log.w(TAG, "发送行程结束通知失败: ${e.message}")
        }
    }
}
