package uz.olim.dovud.brain

import android.content.Context
import kotlinx.coroutines.delay
import org.json.JSONObject
import uz.olim.dovud.Bus
import uz.olim.dovud.Net
import uz.olim.dovud.bridge.SulaymonBridge
import uz.olim.dovud.control.DovudAccessibilityService
import uz.olim.dovud.control.DovudNotificationListener
import uz.olim.dovud.control.PhoneActions
import uz.olim.dovud.memory.MemoryStore
import uz.olim.dovud.memory.Usage
import uz.olim.dovud.safety.ConfirmationGate
import uz.olim.dovud.safety.RiskPolicy
import uz.olim.dovud.safety.SpeakerVerifier
import uz.olim.dovud.safety.StopController
import uz.olim.dovud.safety.StoppedException
import uz.olim.dovud.update.Updater
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Agent sikli: maqsad → ekranni o'qish → bitta harakat → natijani tekshirish → keyingi harakat.
 * 40 qadamdan keyin to'xtab "Davom etaymi?" deb so'raydi.
 */
class Agent(private val ctx: Context, private val io: Io, private val llm: LlmClient) {

    private val phone = PhoneActions(ctx)
    @Volatile var gameMode = false
        private set

    private class Cancelled(msg: String) : Exception(msg)
    private data class Result(val text: String, val image: String? = null, val finish: Boolean = false, val success: Boolean = true)

    private var notifCache: List<DovudNotificationListener.Item> = emptyList()

    suspend fun handle(command: String) {
        StopController.reset()
        Usage.addCommand()
        Bus.log("Olim aka", command)
        try {
            // 1) Sulaymonga to'g'ridan-to'g'ri
            LocalCommands.sulaymonText(command)?.let { text ->
                if (!Net.online(ctx)) { io.say("Internet yo'q, Olim aka. Sulaymonga yuborib bo'lmaydi."); return }
                if (RiskPolicy.textIsRisky(text) && !confirm("Sulaymonga \"$text\" buyrug'ini yuboraman")) { io.say("Bekor qilindi."); return }
                io.say("Sulaymonga yubordim.")
                val ans = SulaymonBridge.ask(text) { io.say("Sulaymon javob tayyorlayapti.") }
                io.say("Sulaymon javobi: $ans")
                MemoryStore.addTurn("user", command); MemoryStore.addTurn("assistant", "Sulaymon javobi: $ans")
                return
            }

            // 2) Oddiy buyruqlar — AI'siz, tez
            val online = Net.online(ctx)
            val local = LocalCommands.match(command, phone)
            if (local != null) {
                if (local.risky && !confirm(local.describe)) { io.say("Bekor qilindi."); return }
                val r = local.run()
                Bus.log("Dovud", r)
                io.say(if (online) shortOk(r) else "Internet yo'q, Olim aka. $r")
                return
            }
            if (!online) {
                io.say("Internet yo'q, Olim aka. Hozir faqat oddiy buyruqlarni bajara olaman: ilova ochish, budilnik, qo'ng'iroq, chiroq.")
                return
            }

            // 3) AI agent
            runAgent(command)
        } catch (e: StoppedException) {
            io.say("To'xtadim.")
        } catch (e: Cancelled) {
            io.say(e.message ?: "Bekor qilindi.")
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            Bus.log("Xato", e.toString())
            io.say("Kechirasiz, Olim aka, xato yuz berdi: ${humanError(e)}")
        } finally {
            io.watchForStop(false)
        }
    }

    private fun shortOk(r: String) = r

    private fun humanError(e: Exception): String {
        val m = e.message ?: ""
        return when {
            m.contains("kaliti kiritilmagan") -> m
            e is uz.olim.dovud.ApiException && e.code == 401 -> "API kaliti noto'g'ri."
            e is uz.olim.dovud.ApiException && e.code == 429 -> "limit tugagan yoki hisobda pul yo'q."
            e is java.net.UnknownHostException || e is java.net.SocketTimeoutException -> "internet sekin yoki uzilgan."
            else -> m.take(120)
        }
    }

    private fun checkStop() { if (StopController.stopped) throw StoppedException() }

    // ---------------- Tasdiq tizimi ----------------
    private var confirmed = false

    /** "…. Tasdiqlaysizmi?" → faqat Olim akaning ovozidan "Tasdiqlayman" qabul qilinadi. */
    private suspend fun confirm(description: String): Boolean {
        repeat(2) { attempt ->
            checkStop()
            io.say(if (attempt == 0) "$description. Tasdiqlaysizmi?" else "Iltimos, \"Tasdiqlayman\" yoki \"Yo'q\" deng.")
            val h = io.hear(9000) ?: return@repeat
            Bus.log("Olim aka", h.text)
            when (ConfirmationGate.parse(h.text)) {
                ConfirmationGate.Answer.CONFIRM -> {
                    if (SpeakerVerifier.isEnabled() && h.owner != true) {
                        io.say("Tasdiqni faqat Olim akaning ovozidan qabul qilaman.")
                        return false
                    }
                    return true
                }
                ConfirmationGate.Answer.REJECT -> return false
                ConfirmationGate.Answer.UNCLEAR -> {}
            }
        }
        return false
    }

    // ---------------- Agent sikli ----------------
    private fun systemPrompt(command: String): String {
        val now = SimpleDateFormat("yyyy-MM-dd HH:mm, EEEE", Locale("uz")).format(Date())
        val lessons = MemoryStore.relevant(command)
        val acc = DovudAccessibilityService.instance
        val sb = StringBuilder()
        sb.append("""
Sen — Dovud, Olim akaning shaxsiy ovozli yordamchisisan. Olim aka Surxondaryodagi yong'inga qarshi kurash jamiyati (YQKJ) egasi.
Unga doim "Olim aka" deb murojaat qil. U qaysi tilda gapirsa (o'zbekcha lotin, ruscha, inglizcha) — o'sha tilda javob ber; standart — o'zbekcha lotin.
Javoblaring OVOZ bilan aytiladi: qisqa (1–2 gap), sodda, raqamlarni so'z bilan emas, oddiy yoz. Markdown, ro'yxat, emoji ishlatma.

Hozir: $now. Joriy ilova: ${acc?.currentPackage() ?: "noma'lum"}. Telefonni boshqarish xizmati: ${if (acc != null) "yoqilgan" else "O'CHIQ (Olim akaga Accessibility'ni yoqishni ayt)"}.

QOIDALAR:
1. Olim aka buyurgan HAR QANDAY ishni bajar. Hech qaysi ilova taqiqlanmagan.
2. Muhim amaldan oldin (pul o'tkazish, to'lov, xarid, o'chirish, xabar yuborish, qo'ng'iroq, sozlama yoki parolni o'zgartirish, o'yinda xarid) AVVAL ask_confirmation(summary) chaqir — bitta gapda nima qilishingni ayt. Tasdiq olinsa, vazifani OXIRIGACHA bajar, qayta so'rama. Bunday harakatlarda risky=true qo'y.
3. Oddiy amallarda (ochish, varaqlash, o'qish, qidirish) tasdiq so'rama.
4. Telefonda ishlash: avval read_screen (arzon). Elementni [raqam] bo'yicha tap qil. Yetmasa — screenshot. Har harakatdan keyin natija avtomatik qaytadi — uni tekshir, keyin keyingi harakatni qil.
5. Bitta javobda faqat BITTA telefon harakatini qil (o'yin rejimidagi tap_sequence bundan mustasno).
6. Ekran yashirilgan yoki skrinshot bloklangan bo'lsa — sababini Olim akaga ochiq ayt va boshqa yo'lni sina (read_screen, boshqa menyu, ilovaning boshqa bo'limi).
7. Telegram xabar: Telegram'ni och → qidiruv → ism → chat → matn yoz → yuborish tugmasini bos (risky=true).
8. Agar biror narsani umuman qila olmasang — halol ayt va request_improvement taklif qil (Olim aka rozi bo'lsa).
9. Olim aka seni tuzatsa yoki yangi narsa o'rgatsa — remember chaqir.
10. Vazifa tugagach finish(answer) chaqir. Oddiy savol-suhbatda asbobsiz, to'g'ridan-to'g'ri javob ber.
11. "Sulaymonga ayt ..." — ask_sulaymon.
""".trimIndent())
        if (lessons.isNotEmpty()) {
            sb.append("\n\nOLDIN O'RGANGANLARING (Olim aka ruxsati bilan saqlangan):\n")
            lessons.forEach { sb.append("- ").append(it.text).append('\n') }
        }
        if (gameMode) sb.append("""

O'YIN REJIMI YOQILGAN:
- Har harakatdan keyin yangi skrinshot avtomatik keladi (0..1000 to'r bilan). Tez qaror qil, kam o'yla.
- Koordinatalarni to'r chiziqlariga qarab aniq hisobla. Ketma-ket bosishlar uchun tap_sequence ishlat.
- O'yin ichidagi xarid (pul, olmos, sotib olish) — faqat ask_confirmation bilan.
- Halol bo'l: tez reaksiya talab qiladigan o'yinlarda (yugurish, otishma, ritm) sekin ekaningni ayt; navbatli o'yinlarda (shashka, shaxmat, so'z, boshqotirma, 2048) yaxshiroq o'ynaysan.
""")
        return sb.toString()
    }

    private suspend fun runAgent(command: String) {
        confirmed = false
        io.watchForStop(true)
        val msgs = ArrayList<Msg>()
        for ((role, text) in MemoryStore.history().takeLast(12)) msgs.add(Msg(if (role == "user") "user" else "assistant", text))
        msgs.add(Msg("user", command))
        MemoryStore.addTurn("user", command)

        var steps = 0
        var budget = 40
        var usedPhone = false
        val trace = ArrayList<String>()
        Bus.status("O'ylayapman…")

        while (true) {
            checkStop()
            if (steps >= budget) {
                io.say("$steps ta qadam bajardim, Olim aka. Oxirgi qilganim: ${trace.lastOrNull() ?: "-"}. Davom etaymi?")
                val h = io.hear(9000)
                if (h != null && ConfirmationGate.isYes(h.text) && ConfirmationGate.parse(h.text) != ConfirmationGate.Answer.REJECT) {
                    budget += 40
                } else { io.say("Mayli, shu yerda to'xtadim."); return }
            }
            pruneHistory(msgs)
            val reply = llm.chat(systemPrompt(command), msgs, Tools.all, agent = usedPhone || gameMode, fast = gameMode)
            checkStop()

            if (reply.toolCalls.isEmpty()) {
                val answer = reply.text?.trim().orEmpty().ifBlank { "Bajarildi, Olim aka." }
                Bus.log("Dovud", answer)
                MemoryStore.addTurn("assistant", answer)
                io.say(answer)
                if (steps >= 4) learn(command, trace)
                return
            }

            msgs.add(Msg("assistant", reply.text, reply.toolCalls))
            var pendingImage: String? = null
            var finished: Result? = null
            for (call in reply.toolCalls) {
                checkStop()
                val res = try { execute(call) } catch (e: Cancelled) { throw e } catch (e: StoppedException) { throw e }
                    catch (e: Exception) { Result("Xato: ${e.message}") }
                Bus.log("Harakat", "${call.name} ${call.args.toString().take(120)} → ${res.text.take(160)}")
                msgs.add(Msg("tool", res.text, toolCallId = call.id, toolName = call.name))
                if (res.image != null) pendingImage = res.image
                if (call.name !in setOf("say", "ask_user", "ask_confirmation", "finish", "remember")) { usedPhone = true; steps++ }
                trace.add("${call.name}(${call.args.toString().take(100)}) → ${res.text.take(80)}")
                if (res.finish) { finished = res; break }
            }
            if (finished != null) {
                // Javobsiz qolgan tool chaqiriqlarini yopamiz (API talabi)
                val answered = msgs.filter { it.role == "tool" }.mapNotNull { it.toolCallId }.toSet()
                reply.toolCalls.filter { it.id !in answered }.forEach { msgs.add(Msg("tool", "bekor", toolCallId = it.id, toolName = it.name)) }
                Bus.log("Dovud", finished.text)
                MemoryStore.addTurn("assistant", finished.text)
                io.say(finished.text)
                if (finished.success && steps >= 4) learn(command, trace)
                return
            }
            if (pendingImage != null) msgs.add(Msg("user", "Ekran rasmi (0..1000 to'r bilan):", imageJpegB64 = pendingImage))
        }
    }

    /** Tokenlarni tejash: eski skrinshotlar va eski ekran ro'yxatlarini qisqartiramiz. */
    private fun pruneHistory(msgs: MutableList<Msg>) {
        val lastImg = msgs.indexOfLast { it.imageJpegB64 != null }
        val screenIdx = msgs.withIndex().filter { it.value.role == "tool" && (it.value.text?.length ?: 0) > 600 }.map { it.index }
        val keepScreens = screenIdx.takeLast(2).toSet()
        for (i in msgs.indices) {
            val m = msgs[i]
            if (m.imageJpegB64 != null && i != lastImg) msgs[i] = m.copy(imageJpegB64 = null, text = "(eski skrinshot olib tashlandi)")
            if (i in screenIdx && i !in keepScreens) msgs[i] = m.copy(text = m.text!!.take(300) + "\n…(eski ekran, qisqartirildi)")
        }
    }

    private fun intentOf(c: ToolCall, fallback: String) = c.args.optString("intent").ifBlank { fallback }

    private suspend fun gate(c: ToolCall, needed: Boolean, description: String) {
        if (!needed || confirmed) return
        if (confirm(description)) { confirmed = true } else throw Cancelled("Bekor qilindi, Olim aka.")
    }

    private suspend fun observe(): String {
        delay(if (gameMode) 150 else 700)
        val acc = DovudAccessibilityService.instance ?: return ""
        return if (gameMode) "" else "\n\nYangi ekran:\n" + acc.readScreen()
    }

    private suspend fun shotAfter(): String? {
        if (!gameMode) return null
        return DovudAccessibilityService.instance?.screenshot(maxWidth = 540, grid = true, quality = 50)?.jpegB64
    }

    private fun acc(): DovudAccessibilityService =
        DovudAccessibilityService.instance ?: throw Cancelled("Telefonni boshqarish xizmati (Accessibility) o'chiq, Olim aka. Ruxsatlar oynasidan yoqing.")

    private suspend fun execute(c: ToolCall): Result {
        val a = c.args
        val riskyFlag = a.optBoolean("risky", false)
        return when (c.name) {
            "read_screen" -> Result(acc().readScreen())
            "screenshot" -> {
                val s = acc().screenshot(maxWidth = if (gameMode) 540 else 720)
                if (s.jpegB64 != null) Result("Skrinshot olindi, quyida.", s.jpegB64)
                else Result("Skrinshot olinmadi: ${s.error}")
            }
            "tap" -> {
                val id = a.getInt("id")
                val label = acc().element(id)?.label
                val isPwd = acc().element(id)?.node?.isPassword == true
                gate(c, riskyFlag || RiskPolicy.textIsRisky(label) || isPwd, intentOf(c, "\"${label ?: id}\" tugmasini bosaman"))
                val r = acc().tapElement(id)
                Result(r + observe(), shotAfter())
            }
            "tap_xy" -> {
                gate(c, riskyFlag, intentOf(c, "Ekrandagi tugmani bosaman"))
                val ok = acc().tapXY(a.getInt("x"), a.getInt("y"))
                Result((if (ok) "Bosildi" else "Bosilmadi") + observe(), shotAfter())
            }
            "long_press" -> {
                gate(c, riskyFlag, intentOf(c, "Uzoq bosaman"))
                val ms = a.optLong("ms", 800)
                val r = if (a.has("id")) acc().longPressElement(a.getInt("id"), ms)
                else if (acc().tapXY(a.optInt("x", 500), a.optInt("y", 500), ms)) "Uzoq bosildi" else "Bo'lmadi"
                Result(r + observe(), shotAfter())
            }
            "swipe" -> {
                val ok = acc().swipe(a.getInt("x1"), a.getInt("y1"), a.getInt("x2"), a.getInt("y2"), a.optLong("ms", 300))
                Result((if (ok) "Surildi" else "Surilmadi") + observe(), shotAfter())
            }
            "tap_sequence" -> {
                val arr = a.getJSONArray("points")
                val pts = (0 until arr.length()).map { val p = arr.getJSONArray(it); p.getInt(0) to p.getInt(1) }
                val n = acc().multiTap(pts, a.optLong("gap_ms", 120))
                Result("$n ta bosildi" + observe(), shotAfter())
            }
            "type_text" -> {
                val id = if (a.has("id")) a.getInt("id") else null
                val isPwd = id?.let { acc().element(it)?.node?.isPassword } == true
                gate(c, riskyFlag || isPwd, intentOf(c, "Matn yozaman"))
                var r = acc().typeText(id, a.getString("text"), a.optBoolean("append"))
                if (a.optBoolean("submit")) r += if (acc().pressEnter()) ", Enter bosildi" else ", Enter bosilmadi"
                Result(r + observe(), shotAfter())
            }
            "scroll" -> Result(acc().scroll(a.getString("direction"), if (a.has("id")) a.getInt("id") else null) + observe(), shotAfter())
            "press" -> { val ok = acc().global(a.getString("key")); Result((if (ok) "Bajarildi" else "Bo'lmadi") + observe(), shotAfter()) }
            "open_app" -> Result(phone.openApp(a.getString("name")) + run { delay(800); observe() }, shotAfter())
            "open_url" -> Result(phone.openUrl(a.getString("url")) + run { delay(800); observe() })
            "list_apps" -> Result(phone.installedApps().joinToString(", ") { it.label })
            "wait" -> { val ms = a.optLong("ms", 1000).coerceIn(100, 5000); var t = 0L; while (t < ms) { checkStop(); delay(100); t += 100 }; Result("Kutildi" + observe()) }
            "call" -> { gate(c, true, intentOf(c, "${a.getString("target")} ga qo'ng'iroq qilaman")); Result(phone.call(a.getString("target"))) }
            "send_sms" -> { gate(c, true, intentOf(c, "${a.getString("target")} ga SMS yuboraman: ${a.getString("text")}")); Result(phone.sendSms(a.getString("target"), a.getString("text"))) }
            "set_alarm" -> Result(phone.setAlarm(a.getInt("hour"), a.getInt("minute"), a.optString("label")))
            "set_timer" -> Result(phone.setTimer(a.getInt("seconds")))
            "set_volume" -> Result(phone.setVolume(a.getInt("percent"), a.optString("stream", "media")))
            "set_brightness" -> Result(phone.setBrightness(a.getInt("percent")))
            "flashlight" -> Result(phone.flashlight(a.getBoolean("on")))
            "open_settings_panel" -> Result(phone.openPanel(a.getString("what")) + run { delay(700); observe() })
            "open_camera" -> Result(phone.openCamera(a.optBoolean("video")) + run { delay(900); observe() })
            "open_gallery" -> Result(phone.openGallery() + run { delay(900); observe() })
            "read_notifications" -> {
                val l = DovudNotificationListener.instance ?: return Result("Bildirishnomalarni o'qish ruxsati yo'q.")
                notifCache = l.list()
                Result(if (notifCache.isEmpty()) "Yangi bildirishnoma yo'q." else notifCache.joinToString("\n") {
                    "${it.n}. [${it.app}] ${it.title}: ${it.text}${if (it.canReply) " (javob berish mumkin)" else ""}"
                })
            }
            "reply_notification" -> {
                val l = DovudNotificationListener.instance ?: return Result("Ruxsat yo'q.")
                val item = notifCache.firstOrNull { it.n == a.getInt("n") } ?: return Result("Bunday raqamli bildirishnoma yo'q. Avval read_notifications.")
                gate(c, true, intentOf(c, "${item.title} ga \"${a.getString("text")}\" deb javob yozaman"))
                Result(l.reply(item.key, a.getString("text")))
            }
            "open_notification" -> {
                val l = DovudNotificationListener.instance ?: return Result("Ruxsat yo'q.")
                val item = notifCache.firstOrNull { it.n == a.getInt("n") } ?: return Result("Topilmadi.")
                Result(l.open(item.key) + run { delay(900); observe() })
            }
            "ask_sulaymon" -> {
                val text = a.getString("text")
                gate(c, riskyFlag || RiskPolicy.textIsRisky(text), intentOf(c, "Sulaymonga \"$text\" buyrug'ini yuboraman"))
                io.say("Sulaymonga yubordim.")
                Result("Sulaymon javobi: " + SulaymonBridge.ask(text) { io.say("Sulaymon javob tayyorlayapti.") })
            }
            "ask_user" -> {
                io.say(a.getString("question"))
                val h = io.hear(10000)
                Result(h?.text?.let { "Olim aka javobi: $it" } ?: "Javob eshitilmadi.")
            }
            "ask_confirmation" -> {
                if (confirmed) return Result("Allaqachon tasdiqlangan. Davom et.")
                if (confirm(a.getString("summary"))) { confirmed = true; Result("Olim aka TASDIQLADI. Vazifani oxirigacha bajar.") }
                else throw Cancelled("Bekor qilindi, Olim aka.")
            }
            "say" -> { io.say(a.getString("text")); Result("Aytildi") }
            "remember" -> {
                val t = a.getString("text")
                io.say("Buni eslab qolaymi: $t?")
                val h = io.hear(8000)
                if (h != null && ConfirmationGate.isYes(h.text) && ConfirmationGate.parse(h.text) != ConfirmationGate.Answer.REJECT) {
                    MemoryStore.addLesson("fakt", t); Result("Saqlandi")
                } else Result("Olim aka saqlashni xohlamadi")
            }
            "game_mode" -> { gameMode = a.getBoolean("on"); Result(if (gameMode) "O'yin rejimi yoqildi" else "O'yin rejimi o'chirildi", shotAfter()) }
            "check_update" -> Result(Updater.newer()?.let { "Yangi versiya bor: ${it.tag}. ${it.notes}" } ?: "Yangi versiya yo'q.")
            "install_update" -> { gate(c, true, intentOf(c, "Dovudning yangi versiyasini o'rnataman")); Result(Updater.installLatest(ctx)) }
            "request_improvement" -> {
                val what = a.getString("what")
                gate(c, true, intentOf(c, "O'zimni yaxshilash uchun so'rov yuboraman: $what"))
                Result(Updater.requestImprovement(what))
            }
            "request_rollback" -> { gate(c, true, intentOf(c, "Oldingi versiyaga qaytish so'rovini yuboraman")); Result(Updater.requestRollback()) }
            "finish" -> Result(a.getString("answer"), finish = true, success = a.optBoolean("success", true))
            else -> Result("Noma'lum asbob: ${c.name}")
        }
    }

    /** 1-yo'l: tajribadan o'rganish — ruxsat so'rab, ish usulini eslab qoladi. */
    private suspend fun learn(command: String, trace: List<String>) {
        if (MemoryStore.relevant(command, 3).any { it.kind == "usul" }) return
        try {
            val r = llm.chat(
                "Quyidagi muvaffaqiyatli bajarilgan vazifadan keyingi safar uchun qisqa ish usulini (recipe) o'zbekcha yoz. 3 qatordan oshmasin. Faqat usulni yoz.",
                listOf(Msg("user", "Vazifa: $command\nQadamlar:\n" + trace.takeLast(30).joinToString("\n"))),
                emptyList(), agent = false, fast = true)
            val recipe = r.text?.trim().orEmpty()
            if (recipe.isBlank()) return
            io.say("Bu ishni qanday bajarganimni eslab qolaymi?")
            val h = io.hear(7000) ?: return
            if (ConfirmationGate.isYes(h.text) && ConfirmationGate.parse(h.text) != ConfirmationGate.Answer.REJECT) {
                MemoryStore.addLesson("usul", "\"$command\" → $recipe")
                io.say("Eslab qoldim.")
            }
        } catch (_: Exception) {}
    }
}
