package com.siangloh.ledger.driving

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import com.google.android.gms.location.ActivityTransition
import com.google.android.gms.location.ActivityTransitionResult
import com.google.android.gms.location.DetectedActivity

class DrivingTransitionReceiver : BroadcastReceiver() {

    companion object {
        private const val TAG = "DrivingTransitionRecv"
    }

    override fun onReceive(context: Context, intent: Intent) {
        if (!ActivityTransitionResult.hasResult(intent)) return

        val result = ActivityTransitionResult.extractResult(intent) ?: return
        for (event in result.transitionEvents) {
            if (event.activityType == DetectedActivity.IN_VEHICLE) {
                when (event.transitionType) {
                    ActivityTransition.ACTIVITY_TRANSITION_ENTER -> {
                        Log.i(TAG, "Google Activity Recognition 感应到: 进入载具/开始驾车 (IN_VEHICLE ENTER)")
                        DrivingDetectionManager.setDrivingState(context, isDriving = true, source = "google_activity")
                    }

                    ActivityTransition.ACTIVITY_TRANSITION_EXIT -> {
                        Log.i(TAG, "Google Activity Recognition 感应到: 离开载具/停止驾车 (IN_VEHICLE EXIT)")
                        DrivingDetectionManager.setDrivingState(context, isDriving = false, source = "google_activity")
                    }
                }
            }
        }
    }
}
