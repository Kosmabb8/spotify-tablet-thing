package dev.carthingspotify.controller.device

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import dev.carthingspotify.controller.MainActivity

class RestartReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        context.startActivity(Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }
}
