package uz.olim.dovud

import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import android.os.Build
import uz.olim.dovud.settings.SecureStore

class DovudApp : Application() {
    override fun onCreate() {
        super.onCreate()
        instance = this
        SecureStore.init(this)
        if (Build.VERSION.SDK_INT >= 26) {
            val nm = getSystemService(NotificationManager::class.java)
            nm.createNotificationChannel(
                NotificationChannel(CH_SERVICE, "Dovud ishlayapti", NotificationManager.IMPORTANCE_LOW)
            )
            nm.createNotificationChannel(
                NotificationChannel(CH_ALERT, "Dovud xabarlari", NotificationManager.IMPORTANCE_HIGH)
            )
        }
    }

    companion object {
        const val CH_SERVICE = "dovud_service"
        const val CH_ALERT = "dovud_alert"
        lateinit var instance: DovudApp
            private set
    }
}
