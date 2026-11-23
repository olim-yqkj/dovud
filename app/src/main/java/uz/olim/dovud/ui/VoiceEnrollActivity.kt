package uz.olim.dovud.ui

import android.os.Bundle
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import uz.olim.dovud.safety.SpeakerVerifier
import uz.olim.dovud.service.DovudService
import uz.olim.dovud.voice.VoskModels

/** Ovoz izini yozish: Olim aka 5 ta gap aytadi. */
class VoiceEnrollActivity : AppCompatActivity() {

    private val phrases = listOf(
        "Dovud, bugun ob-havo qanday?",
        "Telegramni ochib, oxirgi xabarlarni o'qib ber.",
        "Men Olim, Surxondaryodagi yong'inga qarshi kurash jamiyati egasiman.",
        "Ertalab soat yettiga budilnik qo'y.",
        "Tasdiqlayman.",
    )

    private lateinit var info: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        title = "Ovoz izi"
        val col = Ui.column(this)
        col.addView(Ui.title(this, "Ovozingizni tanishtiramiz"))
        col.addView(Ui.text(this, "Tinch joyda turing. \"Boshlash\" ni bosing. Har signal (\"bip\") dan keyin ekrandagi gapni odatdagi ovozingizda o'qing. 5 ta gap bo'ladi."))
        info = Ui.text(this, if (SpeakerVerifier.isEnrolled()) "Ovoz izi allaqachon yozilgan. Qayta yozsangiz, eskisi almashtiriladi." else "", 18f)
        col.addView(info)
        col.addView(Ui.button(this, "▶ Boshlash") { run() })
        setContentView(Ui.scroll(this, col))
    }

    private fun run() {
        lifecycleScope.launch {
            var s = DovudService.instance
            if (s == null) { DovudService.start(this@VoiceEnrollActivity); var n = 0; while (s == null && n < 30) { delay(300); s = DovudService.instance; n++ }; delay(1500) }
            val svc = s ?: run { info.text = "Xizmat ishga tushmadi. Mikrofon ruxsatini tekshiring."; return@launch }
            if (!withContext(Dispatchers.IO) { VoskModels.ensure(this@VoiceEnrollActivity) }) { info.text = "Ovoz modeli yuklanmadi: ${VoskModels.error}"; return@launch }
            val vectors = ArrayList<FloatArray>()
            for ((i, p) in phrases.withIndex()) {
                info.text = "${i + 1}/5. Signaldan keyin o'qing:\n\n\"$p\""
                delay(1200)
                val pcm = svc.captureRaw(8000)
                if (pcm == null) { info.text = "Ovoz eshitilmadi. Qaytadan \"Boshlash\" ni bosing."; return@launch }
                val a = withContext(Dispatchers.Default) { SpeakerVerifier.analyze(pcm) }
                if (a.vector != null) vectors.add(a.vector)
            }
            if (vectors.size < 3) { info.text = "Yetarli namuna olinmadi (${vectors.size}). Qaytadan urinib ko'ring."; return@launch }
            val thr = SpeakerVerifier.enroll(vectors)
            info.text = "✅ Tayyor! Ovoz izingiz saqlandi (chegara ${"%.2f".format(thr)}).\nEndi Dovud faqat sizning ovozingizga javob beradi."
        }
    }
}
