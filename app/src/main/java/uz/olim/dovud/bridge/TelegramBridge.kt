package uz.olim.dovud.bridge

import android.content.Intent
import android.graphics.Rect
import android.net.Uri
import android.os.Bundle
import android.view.accessibility.AccessibilityNodeInfo
import kotlinx.coroutines.delay
import uz.olim.dovud.control.DovudAccessibilityService
import uz.olim.dovud.safety.StopController
import uz.olim.dovud.settings.SecureStore

/**
 * Sulaymon ko'prigi — Telegram orqali (server kerak emas).
 * Dovud Telegram'da Sulaymon botining chatini ochadi, buyruqni yozib yuboradi,
 * yangi javob paydo bo'lguncha kutadi va uni o'qib qaytaradi.
 */
object TelegramBridge {

    private const val TG = "org.telegram"

    fun configured() = SecureStore.has(SecureStore.SULAYMON_TG)

    private fun username() = SecureStore.get(SecureStore.SULAYMON_TG).trim().removePrefix("@").removePrefix("https://t.me/").removePrefix("t.me/")

    private val statusWords = listOf("yozmoqda", "typing", "печатает", "online", "onlayn", "в сети", "bot", "бот",
        "last seen", "был", "xabar yozish", "message", "сообщение", "yuborish", "send", "отправить")

    /** Chatdagi xabarlar matnlari (sarlavha va tugmalarsiz). */
    private fun messages(acc: DovudAccessibilityService): List<String> {
        val res = ArrayList<String>()
        for (n in acc.nodesOf(TG)) {
            if (n.isEditable || n.isClickable && (n.className?.contains("Button") == true)) continue
            val t = (n.text?.toString() ?: n.contentDescription?.toString())?.trim() ?: continue
            if (t.length < 2) continue
            res.add(t)
        }
        return res
    }

    private fun clean(s: String): String = s
        .replace(Regex("""\b\d{1,2}:\d{2}(\s?[AP]M)?\b"""), "")
        .replace(Regex("""(Sent|Read|Edited|Отправлено|Прочитано|изменено|Received|Получено)""", RegexOption.IGNORE_CASE), "")
        .trim(' ', ',', '.', '\n')

    private fun findInput(acc: DovudAccessibilityService): AccessibilityNodeInfo? =
        acc.nodesOf(TG).lastOrNull { it.isEditable && it.isVisibleToUser }

    private fun findSend(acc: DovudAccessibilityService, input: AccessibilityNodeInfo): AccessibilityNodeInfo? {
        val nodes = acc.nodesOf(TG)
        val byName = nodes.firstOrNull { n ->
            val d = (n.contentDescription ?: n.text)?.toString()?.lowercase() ?: ""
            n.isVisibleToUser && (d == "send" || d == "отправить" || d == "yuborish" || d.startsWith("send ") || d.startsWith("отправ"))
        }
        if (byName != null) return byName
        // Topilmasa: yozish maydonining o'ng tomonidagi eng yaqin bosiladigan element
        val ir = Rect(); input.getBoundsInScreen(ir)
        return nodes.filter { it.isClickable && it.isVisibleToUser }.map { n -> val r = Rect(); n.getBoundsInScreen(r); n to r }
            .filter { (_, r) -> r.left >= ir.right - 10 && r.centerY() in (ir.top - 60)..(ir.bottom + 60) }
            .minByOrNull { (_, r) -> r.left }?.first
    }

    suspend fun ask(text: String, timeoutSec: Int = 120, onWaiting: suspend () -> Unit = {}): String {
        val acc = DovudAccessibilityService.instance
            ?: return "Telefonni boshqarish xizmati (Accessibility) o'chiq — Sulaymonga Telegram orqali yozib bo'lmaydi."
        val user = username()
        if (user.isBlank()) return "Sulaymon botining Telegram nomi sozlanmagan."

        // 1) Sulaymon chatini ochish
        val i = Intent(Intent.ACTION_VIEW, Uri.parse("tg://resolve?domain=$user")).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        try { acc.startActivity(i) } catch (e: Exception) { return "Telegram ochilmadi: ${e.message}" }

        // 2) Yozish maydonini kutish
        var input: AccessibilityNodeInfo? = null
        repeat(20) { if (input == null) { delay(400); input = findInput(acc) } }
        val field = input ?: return "Telegram'da Sulaymon chatini ocholmadim. Bot nomi to'g'rimi? (@$user)"

        // Bot hali boshlanmagan bo'lsa "START/BOSHLASH" tugmasi chiqishi mumkin
        acc.nodesOf(TG).firstOrNull { n -> val t = n.text?.toString()?.lowercase() ?: ""; n.isClickable && (t == "start" || t == "запустить" || t == "boshlash") }
            ?.performAction(AccessibilityNodeInfo.ACTION_CLICK)

        val before = messages(acc).toSet()

        // 3) Buyruqni yozish va yuborish
        field.performAction(AccessibilityNodeInfo.ACTION_FOCUS)
        val args = Bundle().apply { putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, text) }
        if (!field.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)) return "Telegram'da matn yozib bo'lmadi."
        delay(500)
        val send = findSend(acc, findInput(acc) ?: field) ?: return "Telegram'da 'Yuborish' tugmasi topilmadi."
        if (!send.performAction(AccessibilityNodeInfo.ACTION_CLICK)) {
            val r = Rect(); send.getBoundsInScreen(r)
            acc.gestureTap(r.exactCenterX(), r.exactCenterY(), 60)
        }

        // 4) Javobni kutish: yangi xabar paydo bo'lib, 3 soniya o'zgarmay tursa — tayyor
        val start = System.currentTimeMillis()
        var announced = false
        var last = ""
        var stableSince = 0L
        val mine = text.trim().take(40)
        while (System.currentTimeMillis() - start < timeoutSec * 1000L) {
            if (StopController.stopped) return "To'xtatildi."
            delay(1500)
            if (!announced && System.currentTimeMillis() - start > 8000) { announced = true; onWaiting() }
            val fresh = messages(acc).filter { it !in before }
                .filter { !it.contains(mine) }
                .filter { m -> val l = m.lowercase(); m.length > 3 && statusWords.none { l == it || (l.length < 25 && l.contains(it)) } }
                .map { clean(it) }.filter { it.isNotBlank() }.distinct()
            val cur = fresh.joinToString("\n")
            if (cur.isBlank()) continue
            if (cur != last) { last = cur; stableSince = System.currentTimeMillis() }
            else if (System.currentTimeMillis() - stableSince > 3000) return cur
        }
        return if (last.isNotBlank()) last else "Sulaymon $timeoutSec soniyada javob bermadi. Kompyuter yoqilganmi?"
    }
}
