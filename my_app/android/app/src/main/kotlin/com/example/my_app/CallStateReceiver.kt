package com.example.my_app

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.telephony.TelephonyManager

class CallStateReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != TelephonyManager.ACTION_PHONE_STATE_CHANGED) return

        when (intent.getStringExtra(TelephonyManager.EXTRA_STATE)) {
            TelephonyManager.EXTRA_STATE_RINGING -> ScamDetectionForegroundService.startRinging(context)
            TelephonyManager.EXTRA_STATE_OFFHOOK -> ScamDetectionForegroundService.startCallAudio(context)
            TelephonyManager.EXTRA_STATE_IDLE -> ScamDetectionForegroundService.stop(context)
        }
    }
}
