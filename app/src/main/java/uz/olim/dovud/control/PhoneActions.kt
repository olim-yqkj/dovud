package uz.olim.dovud.control

import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.media.AudioManager
import android.net.Uri
import android.os.Build
import android.provider.AlarmClock
import android.provider.ContactsContract
import android.provider.MediaStore
import android.provider.Settings
import android.telephony.SmsManager

/** Telefonning to'g'ridan-to'g'ri amallari (internetsiz ham ishlaydi). */
class PhoneActions(private val app: Context) {

    /** Faoliyatni ochish uchun eng yaxshi kontekst (Accessibility xizmati fondan ochishga ruxsat beradi). */
    private val ctx: Context get() = DovudAccessibilityService.instance ?: app

    private fun launch(i: Intent): Boolean = try {
        i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK); ctx.startActivity(i); true
    } catch (e: Exception) { false }

    data class AppInfo(val label: String, val pkg: String)

    fun installedApps(): List<AppInfo> {
        val pm = app.packageManager
        val i = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        return pm.queryIntentActivities(i, 0).map { AppInfo(it.loadLabel(pm).toString(), it.activityInfo.packageName) }.distinctBy { it.pkg }
    }

    private val aliases = mapOf(
        "telegram" to listOf("telegram", "телеграм", "telegramm"),
        "camera" to listOf("kamera", "камера", "camera", "fotoapparat"),
        "settings" to listOf("sozlamalar", "sozlama", "настройки", "settings", "nastroyki"),
        "gallery" to listOf("galereya", "галерея", "gallery", "rasmlar", "фото"),
        "calculator" to listOf("kalkulyator", "калькулятор", "calculator"),
        "clock" to listOf("soat", "часы", "clock", "budilnik"),
        "youtube" to listOf("youtube", "ютуб", "yutub"),
        "chrome" to listOf("chrome", "xrom", "brauzer", "браузер", "internet"),
        "phone" to listOf("telefon", "qo'ng'iroqlar", "звонки", "phone", "dialer"),
        "messages" to listOf("sms", "xabarlar", "сообщения", "messages"),
        "files" to listOf("fayllar", "файлы", "files", "provodnik"),
        "instagram" to listOf("instagram", "инстаграм", "insta"),
        "whatsapp" to listOf("whatsapp", "ватсап", "vatsap"),
        "payme" to listOf("payme", "пейми", "peymi"),
        "click" to listOf("click", "клик", "klik"),
        "maps" to listOf("xarita", "карта", "maps", "yandex xarita"),
    )

    private fun norm(s: String) = Translit.toLatin(s.lowercase()).replace(Regex("[ʻ‘’`'\\s\\-_.]"), "")

    fun findApp(name: String): AppInfo? {
        val apps = installedApps()
        val q = norm(name)
        if (q.isEmpty()) return null
        apps.firstOrNull { norm(it.label) == q }?.let { return it }
        // Taxalluslar
        val canon = aliases.entries.firstOrNull { e -> e.value.any { norm(it) == q || q.contains(norm(it)) } }?.key
        if (canon != null) {
            apps.firstOrNull { norm(it.label).contains(canon) || it.pkg.contains(canon) }?.let { return it }
            for (a in aliases[canon]!!) apps.firstOrNull { norm(it.label).contains(norm(a)) }?.let { return it }
        }
        apps.firstOrNull { norm(it.label).startsWith(q) || q.startsWith(norm(it.label)) }?.let { return it }
        apps.firstOrNull { norm(it.label).contains(q) }?.let { return it }
        // Eng yaqin nom (Levenshtein)
        val best = apps.minByOrNull { lev(norm(it.label), q) } ?: return null
        return if (lev(norm(best.label), q) <= maxOf(2, q.length / 3)) best else null
    }

    fun openApp(name: String): String {
        val a = findApp(name) ?: return "\"$name\" ilovasi topilmadi."
        val i = app.packageManager.getLaunchIntentForPackage(a.pkg) ?: return "\"${a.label}\" ni ochib bo'lmadi."
        return if (launch(i)) "\"${a.label}\" ochildi" else "\"${a.label}\" ochilmadi (fondan ochishga ruxsat kerak)."
    }

    fun openUrl(url: String): String = if (launch(Intent(Intent.ACTION_VIEW, Uri.parse(url)))) "Ochildi" else "Ochilmadi"

    @SuppressLint("Range")
    fun findContact(name: String): Pair<String, String>? {
        if (app.checkSelfPermission(android.Manifest.permission.READ_CONTACTS) != PackageManager.PERMISSION_GRANTED) return null
        val uri = Uri.withAppendedPath(ContactsContract.CommonDataKinds.Phone.CONTENT_FILTER_URI, Uri.encode(name))
        app.contentResolver.query(uri, arrayOf(ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME, ContactsContract.CommonDataKinds.Phone.NUMBER), null, null, null)?.use { c ->
            if (c.moveToFirst()) return c.getString(0) to c.getString(1)
        }
        // Lotin/kirill farqi uchun ikkinchi urinish
        val alt = Translit.toCyrillic(name)
        if (alt != name) {
            val uri2 = Uri.withAppendedPath(ContactsContract.CommonDataKinds.Phone.CONTENT_FILTER_URI, Uri.encode(alt))
            app.contentResolver.query(uri2, arrayOf(ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME, ContactsContract.CommonDataKinds.Phone.NUMBER), null, null, null)?.use { c ->
                if (c.moveToFirst()) return c.getString(0) to c.getString(1)
            }
        }
        return null
    }

    fun resolveNumber(target: String): Pair<String, String>? {
        val digits = target.replace(Regex("[^0-9+]"), "")
        if (digits.length >= 5) return target to digits
        return findContact(target)
    }

    @SuppressLint("MissingPermission")
    fun call(target: String): String {
        val (who, num) = resolveNumber(target) ?: return "\"$target\" kontaktlarda topilmadi."
        if (app.checkSelfPermission(android.Manifest.permission.CALL_PHONE) != PackageManager.PERMISSION_GRANTED)
            return if (launch(Intent(Intent.ACTION_DIAL, Uri.parse("tel:$num")))) "Raqam terildi, qo'ng'iroq tugmasini bosish kerak (ruxsat yo'q)." else "Bo'lmadi"
        return if (launch(Intent(Intent.ACTION_CALL, Uri.parse("tel:$num")))) "$who ($num) ga qo'ng'iroq qilinyapti" else "Qo'ng'iroq bo'lmadi"
    }

    fun sendSms(target: String, text: String): String {
        val (who, num) = resolveNumber(target) ?: return "\"$target\" kontaktlarda topilmadi."
        if (app.checkSelfPermission(android.Manifest.permission.SEND_SMS) != PackageManager.PERMISSION_GRANTED) return "SMS ruxsati yo'q."
        return try {
            @Suppress("DEPRECATION")
            val sm = if (Build.VERSION.SDK_INT >= 31) app.getSystemService(SmsManager::class.java) else SmsManager.getDefault()
            sm.sendMultipartTextMessage(num, null, sm.divideMessage(text), null, null)
            "$who ga SMS yuborildi"
        } catch (e: Exception) { "SMS yuborilmadi: ${e.message}" }
    }

    fun setAlarm(hour: Int, minute: Int, label: String?): String {
        val i = Intent(AlarmClock.ACTION_SET_ALARM)
            .putExtra(AlarmClock.EXTRA_HOUR, hour).putExtra(AlarmClock.EXTRA_MINUTES, minute)
            .putExtra(AlarmClock.EXTRA_SKIP_UI, true)
        if (!label.isNullOrBlank()) i.putExtra(AlarmClock.EXTRA_MESSAGE, label)
        return if (launch(i)) "Budilnik %02d:%02d ga qo'yildi".format(hour, minute) else "Budilnik qo'yilmadi"
    }

    fun setTimer(seconds: Int): String {
        val i = Intent(AlarmClock.ACTION_SET_TIMER).putExtra(AlarmClock.EXTRA_LENGTH, seconds).putExtra(AlarmClock.EXTRA_SKIP_UI, true)
        return if (launch(i)) "Taymer ${seconds / 60} daqiqa ${seconds % 60} soniyaga qo'yildi" else "Taymer qo'yilmadi"
    }

    fun setVolume(percent: Int, stream: String): String {
        val am = app.getSystemService(AudioManager::class.java)
        val s = when (stream) { "ring" -> AudioManager.STREAM_RING; "alarm" -> AudioManager.STREAM_ALARM; "call" -> AudioManager.STREAM_VOICE_CALL; else -> AudioManager.STREAM_MUSIC }
        return try {
            val max = am.getStreamMaxVolume(s)
            am.setStreamVolume(s, (percent.coerceIn(0, 100) * max + 50) / 100, AudioManager.FLAG_SHOW_UI)
            "Ovoz balandligi $percent% qilindi"
        } catch (e: Exception) { "Ovozni o'zgartirib bo'lmadi: ${e.message}" }
    }

    fun setBrightness(percent: Int): String {
        if (!Settings.System.canWrite(app)) return "Yorug'likni o'zgartirish uchun 'Tizim sozlamalarini o'zgartirish' ruxsati kerak."
        return try {
            Settings.System.putInt(app.contentResolver, Settings.System.SCREEN_BRIGHTNESS_MODE, Settings.System.SCREEN_BRIGHTNESS_MODE_MANUAL)
            Settings.System.putInt(app.contentResolver, Settings.System.SCREEN_BRIGHTNESS, (percent.coerceIn(1, 100) * 255 / 100))
            "Yorug'lik $percent% qilindi"
        } catch (e: Exception) { "Bo'lmadi: ${e.message}" }
    }

    fun flashlight(on: Boolean): String = try {
        val cm = app.getSystemService(CameraManager::class.java)
        val id = cm.cameraIdList.first { cm.getCameraCharacteristics(it).get(CameraCharacteristics.FLASH_INFO_AVAILABLE) == true }
        cm.setTorchMode(id, on)
        if (on) "Chiroq yoqildi" else "Chiroq o'chirildi"
    } catch (e: Exception) { "Chiroqni boshqarib bo'lmadi" }

    fun openPanel(what: String): String {
        val action = when (what) {
            "wifi" -> if (Build.VERSION.SDK_INT >= 29) Settings.Panel.ACTION_WIFI else Settings.ACTION_WIFI_SETTINGS
            "bluetooth" -> Settings.ACTION_BLUETOOTH_SETTINGS
            "internet" -> if (Build.VERSION.SDK_INT >= 29) Settings.Panel.ACTION_INTERNET_CONNECTIVITY else Settings.ACTION_WIRELESS_SETTINGS
            "volume" -> if (Build.VERSION.SDK_INT >= 29) Settings.Panel.ACTION_VOLUME else Settings.ACTION_SOUND_SETTINGS
            "display" -> Settings.ACTION_DISPLAY_SETTINGS
            "location" -> Settings.ACTION_LOCATION_SOURCE_SETTINGS
            "battery" -> Intent.ACTION_POWER_USAGE_SUMMARY
            else -> Settings.ACTION_SETTINGS
        }
        return if (launch(Intent(action))) "$what sozlamasi ochildi. Endi read_screen() qilib, kerakli kalitni bosing." else "Ochilmadi"
    }

    fun openCamera(video: Boolean): String =
        if (launch(Intent(if (video) MediaStore.INTENT_ACTION_VIDEO_CAMERA else MediaStore.INTENT_ACTION_STILL_IMAGE_CAMERA))) "Kamera ochildi" else "Kamera ochilmadi"

    fun openGallery(): String = if (launch(Intent(Intent.ACTION_VIEW, MediaStore.Images.Media.EXTERNAL_CONTENT_URI))) "Galereya ochildi" else openApp("galereya")

    fun dialOnly(number: String): Boolean = launch(Intent(Intent.ACTION_DIAL, Uri.parse("tel:$number")))

    companion object {
        fun lev(a: String, b: String): Int {
            val dp = IntArray(b.length + 1) { it }
            for (i in 1..a.length) {
                var prev = dp[0]; dp[0] = i
                for (j in 1..b.length) {
                    val tmp = dp[j]
                    dp[j] = minOf(dp[j] + 1, dp[j - 1] + 1, prev + if (a[i - 1] == b[j - 1]) 0 else 1)
                    prev = tmp
                }
            }
            return dp[b.length]
        }
    }
}
