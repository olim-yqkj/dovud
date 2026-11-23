package uz.olim.dovud.ui

import android.Manifest
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.provider.Settings
import android.widget.LinearLayout
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.NotificationManagerCompat
import uz.olim.dovud.control.DovudAccessibilityService
import uz.olim.dovud.safety.SpeakerVerifier
import uz.olim.dovud.service.DovudService
import uz.olim.dovud.settings.SecureStore

/**
 * Birinchi ochilganda Olim akani har bir ruxsatni yoqishga qadamma-qadam olib boradi.
 */
class SetupActivity : AppCompatActivity() {

    private lateinit var list: LinearLayout

    private data class Step(
        val title: String,
        val help: String,
        val done: () -> Boolean,
        val manual: Boolean = false,
        val action: () -> Unit,
    )

    private val isXiaomi = Build.MANUFACTURER.lowercase().let { it.contains("xiaomi") || it.contains("redmi") || it.contains("poco") }
    private val isSamsung = Build.MANUFACTURER.lowercase().contains("samsung")

    private fun manualDone(key: String) = SecureStore.get(SecureStore.MANUAL_STEPS).split(",").contains(key)
    private fun markManual(key: String) {
        val s = SecureStore.get(SecureStore.MANUAL_STEPS).split(",").filter { it.isNotBlank() }.toMutableSet(); s.add(key)
        SecureStore.set(SecureStore.MANUAL_STEPS, s.joinToString(","))
    }

    private fun steps(): List<Step> {
        val s = ArrayList<Step>()
        s += Step("1. Mikrofon", "Dovud sizni eshitishi uchun. Chiqqan oynada \"Ilovadan foydalanganda\" ni bosing.", { micGranted(this) }) {
            requestPermissions(arrayOf(Manifest.permission.RECORD_AUDIO), 1)
        }
        if (Build.VERSION.SDK_INT >= 33) s += Step("2. Bildirishnomalar", "Dovud tugmasi bildirishnoma panelida turishi uchun. \"Ruxsat berish\" ni bosing.",
            { checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED }) {
            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 2)
        }
        s += Step("3. Qo'ng'iroq, SMS, kontaktlar", "Qo'ng'iroq qilish, SMS yuborish va kontakt ismini topish uchun. Har bir oynada \"Ruxsat berish\" ni bosing.",
            { listOf(Manifest.permission.CALL_PHONE, Manifest.permission.SEND_SMS, Manifest.permission.READ_CONTACTS).all { checkSelfPermission(it) == PackageManager.PERMISSION_GRANTED } }) {
            val p = arrayListOf(Manifest.permission.CALL_PHONE, Manifest.permission.SEND_SMS, Manifest.permission.READ_CONTACTS)
            if (Build.VERSION.SDK_INT >= 31) p.add(Manifest.permission.BLUETOOTH_CONNECT)
            if (Build.VERSION.SDK_INT >= 33) p.add(Manifest.permission.READ_MEDIA_IMAGES)
            requestPermissions(p.toTypedArray(), 3)
        }
        if (Build.VERSION.SDK_INT >= 33) s += Step("4. Cheklangan sozlamalarga ruxsat (MUHIM)",
            "Android internetdan o'rnatilgan ilovalarga Accessibility'ni yoqishni bloklaydi. Ochilgan oynada: o'ng yuqoridagi ⋮ (uch nuqta) → \"Cheklangan sozlamalarga ruxsat berish\" (Allow restricted settings) → tasdiqlang. Agar ⋮ ko'rinmasa — avval 5-qadamni sinab ko'ring, keyin shu yerga qayting. So'ng orqaga qaytib \"Bajardim\" ni bosing.",
            { manualDone("restricted") || accessibilityOn() }, manual = true) {
            startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:$packageName")))
            confirmManual("restricted")
        }
        s += Step("5. Telefonni boshqarish (Accessibility)",
            "Eng muhim ruxsat. Ochilgan ro'yxatda \"Yuklab olingan ilovalar\" (yoki \"O'rnatilgan xizmatlar\") → \"Dovud\" → kalitni yoqing → \"Ruxsat berish\". Xiaomi'da 10 soniya kutib \"OK\" ni bosish kerak bo'lishi mumkin.",
            { accessibilityOn() }) { startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)) }
        s += Step("6. Bildirishnomalarni o'qish", "Kelgan xabarlarni o'qib berish va javob yozish uchun. Ro'yxatda \"Dovud\" ni yoqing.",
            { NotificationManagerCompat.getEnabledListenerPackages(this).contains(packageName) }) {
            startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS))
        }
        s += Step("7. Boshqa ilovalar ustidan ko'rsatish", "Dovud fonda ilovalarni ocha olishi uchun. \"Dovud\" → kalitni yoqing.", { Settings.canDrawOverlays(this) }) {
            startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName")))
        }
        s += Step("8. Tizim sozlamalarini o'zgartirish", "Ekran yorug'ligini o'zgartirish uchun. Kalitni yoqing.", { Settings.System.canWrite(this) }) {
            startActivity(Intent(Settings.ACTION_MANAGE_WRITE_SETTINGS, Uri.parse("package:$packageName")))
        }
        s += Step("9. Batareya cheklovini o'chirish", "Telefon Dovudni fonda o'chirib qo'ymasligi uchun. \"Ruxsat berish\" ni bosing.",
            { getSystemService(PowerManager::class.java).isIgnoringBatteryOptimizations(packageName) }) {
            startActivity(Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:$packageName")))
        }
        s += Step("10. Yangilanishlarni o'rnatish", "Dovud o'zining yangi versiyasini o'rnata olishi uchun. \"Ushbu manbadan ruxsat berish\" kalitini yoqing.",
            { packageManager.canRequestPackageInstalls() }) {
            startActivity(Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:$packageName")))
        }
        if (isXiaomi) {
            s += Step("11. Xiaomi: Avtozapusk", "Ochilgan ro'yxatda \"Dovud\" ni toping va kalitni yoqing. So'ng orqaga qaytib \"Bajardim\" ni bosing.",
                { manualDone("autostart") }, manual = true) {
                tryStart(listOf(
                    ComponentName("com.miui.securitycenter", "com.miui.permcenter.autostart.AutoStartManagementActivity"),
                    ComponentName("com.miui.securitycenter", "com.miui.powercenter.autostart.AutoStartManagementActivity")))
                confirmManual("autostart")
            }
            s += Step("12. Xiaomi: Boshqa ruxsatlar", "Ochilgan oynada \"Fonda ishlayotganda qalqib chiquvchi oynalarni ko'rsatish\" va \"Qulflangan ekranda ko'rsatish\" ni RUXSAT bering. Bu bo'lmasa Dovud fonda ilova ocha olmaydi.",
                { manualDone("miui_perm") }, manual = true) {
                val i = Intent("miui.intent.action.APP_PERM_EDITOR").putExtra("extra_pkgname", packageName)
                if (!tryIntent(i)) startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:$packageName")))
                confirmManual("miui_perm")
            }
            s += Step("13. Xiaomi: Batareya — \"Cheklovsiz\"", "Ilova ma'lumotlari → Batareya (Quvvat tejash) → \"Cheklovlar yo'q\" ni tanlang.",
                { manualDone("miui_battery") }, manual = true) {
                startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:$packageName")))
                confirmManual("miui_battery")
            }
        }
        if (isSamsung) s += Step("11. Samsung: Batareya — \"Cheklanmagan\"", "Ilova ma'lumotlari → Batareya → \"Cheklanmagan\" (Unrestricted) ni tanlang.",
            { manualDone("samsung_battery") }, manual = true) {
            startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:$packageName")))
            confirmManual("samsung_battery")
        }
        s += Step("API kalitlari", "OpenAI kalitini (majburiy) va Gemini kalitini (zaxira) kiriting.", { SecureStore.has(SecureStore.OPENAI_KEY) }) {
            startActivity(Intent(this, SettingsActivity::class.java))
        }
        s += Step("Ovoz izi", "Dovud faqat sizning ovozingizga javob berishi uchun 5 ta gap aytasiz.", { SpeakerVerifier.isEnrolled() }) {
            startActivity(Intent(this, VoiceEnrollActivity::class.java))
        }
        return s
    }

    private fun confirmManual(key: String) {
        // Foydalanuvchi qaytganda so'raymiz
        pendingManual = key
    }
    private var pendingManual: String? = null

    private fun tryIntent(i: Intent): Boolean = try { startActivity(i); true } catch (e: Exception) { false }
    private fun tryStart(cs: List<ComponentName>) {
        for (c in cs) if (tryIntent(Intent().setComponent(c))) return
        startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:$packageName")))
    }

    private fun accessibilityOn(): Boolean {
        if (DovudAccessibilityService.instance != null) return true
        val enabled = Settings.Secure.getString(contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES) ?: return false
        return enabled.contains("$packageName/") && enabled.contains("DovudAccessibilityService")
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        title = "Ruxsatlar"
        list = Ui.column(this)
        setContentView(Ui.scroll(this, list))
    }

    override fun onResume() {
        super.onResume()
        pendingManual?.let { key ->
            pendingManual = null
            AlertDialog.Builder(this).setMessage("Bu qadamni bajardingizmi?")
                .setPositiveButton("Bajardim") { _, _ -> markManual(key); render() }
                .setNegativeButton("Yo'q", null).show()
        }
        render()
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == 1 && micGranted(this)) DovudService.start(this)
        render()
    }

    private fun render() {
        list.removeAllViews()
        list.addView(Ui.title(this, "Olim aka, ruxsatlarni bittalab yoqamiz"))
        list.addView(Ui.text(this, "Har bir qatordagi \"Yoqish\" tugmasini bosing, ko'rsatmani bajaring va orqaga qayting. ✅ — tayyor, ❌ — hali yoqilmagan."))
        val all = steps()
        var firstPending = true
        for (st in all) {
            val ok = runCatching { st.done() }.getOrDefault(false)
            list.addView(Ui.text(this, (if (ok) "✅ " else "❌ ") + st.title, 17f))
            if (!ok) {
                list.addView(Ui.text(this, st.help, 14f))
                list.addView(Ui.button(this, if (firstPending) "👉 Yoqish" else "Yoqish") { runCatching { st.action() } })
                firstPending = false
            }
        }
        val allOk = all.all { runCatching { it.done() }.getOrDefault(false) }
        list.addView(Ui.button(this, if (allOk) "✅ Hammasi tayyor — boshlash" else "Keyinroq davom etaman") {
            SecureStore.set(SecureStore.SETUP_DONE, "1")
            if (micGranted(this)) DovudService.start(this)
            finish()
        })
    }

    companion object {
        fun micGranted(ctx: Context) = ctx.checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED
    }
}
