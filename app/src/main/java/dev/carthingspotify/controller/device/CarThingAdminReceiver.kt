package dev.carthingspotify.controller.device

import android.app.admin.DeviceAdminReceiver
import android.content.Context
import android.content.Intent
import dev.carthingspotify.controller.MainActivity

class CarThingAdminReceiver : DeviceAdminReceiver() {
    override fun onEnabled(context: Context, intent: Intent) {
        super.onEnabled(context, intent)
        context.startActivity(
            Intent(context, MainActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        )
    }
}
