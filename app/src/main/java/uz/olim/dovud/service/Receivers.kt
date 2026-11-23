package uz.olim.dovud.service

import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationCompat
import uz.olim.dovud.DovudApp
import uz.olim.dovud.R
import uz.olim.dovud.ui.MainActivity

class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (!DovudService.start(context)) {
            // Android 14+ mikrofonli xizmatni fondan ishga tushirishni cheklaydi — bitta bosish kerak
            val open = PendingIntent.getActivity(context, 0, Intent(context, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE)
            val n = NotificationCompat.Builder(context, DovudApp.CH_ALERT).setSmallIcon(R.drawable.ic_notif)
                .setContentTitle("Dovudni yoqish uchun bosing").setContentIntent(open).setAutoCancel(true).build()
            context.getSystemService(NotificationManager::class.java).notify(8, n)
        }
    }
}

class NotificationActionReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val s = DovudService.instance
        when (intent.action) {
            DovudService.ACTION_TALK -> if (s != null) s.talk() else DovudService.start(context)
            DovudService.ACTION_STOP -> s?.stopAll()
        }
    }
}
