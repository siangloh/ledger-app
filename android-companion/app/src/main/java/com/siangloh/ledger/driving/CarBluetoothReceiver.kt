package com.siangloh.ledger.driving

import android.bluetooth.BluetoothDevice
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import android.util.Log

class CarBluetoothReceiver : BroadcastReceiver() {

    companion object {
        private const val TAG = "CarBluetoothReceiver"
    }

    override fun onReceive(context: Context, intent: Intent) {
        val action = intent.action ?: return

        val device: BluetoothDevice? = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE, BluetoothDevice::class.java)
        } else {
            @Suppress("DEPRECATION")
            intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE)
        }

        if (device == null) return

        val isCar = DrivingDetectionManager.isCarBluetooth(context, device)
        val devName = try {
            device.name ?: device.address
        } catch (_: SecurityException) {
            device.address
        }

        when (action) {
            BluetoothDevice.ACTION_ACL_CONNECTED -> {
                if (isCar) {
                    Log.i(TAG, "已连接车载蓝牙: $devName (${device.address})")
                    DrivingDetectionManager.setDrivingState(context, isDriving = true, source = "bluetooth", deviceName = devName)
                } else {
                    Log.d(TAG, "连接了普通非车载蓝牙设备: $devName (忽略)")
                }
            }

            BluetoothDevice.ACTION_ACL_DISCONNECTED -> {
                if (isCar) {
                    Log.i(TAG, "已断开车载蓝牙: $devName (${device.address})")
                    DrivingDetectionManager.setDrivingState(context, isDriving = false, source = "bluetooth", deviceName = devName)
                }
            }
        }
    }
}
