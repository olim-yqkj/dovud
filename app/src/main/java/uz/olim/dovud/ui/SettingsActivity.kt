package uz.olim.dovud.ui

import android.os.Bundle
import android.text.InputType
import android.widget.CheckBox
import android.widget.EditText
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import uz.olim.dovud.memory.MemoryStore
import uz.olim.dovud.memory.Usage
import uz.olim.dovud.settings.SecureStore

/** Kalitlar va sozlamalar — hammasi shifrlangan holda saqlanadi. */
class SettingsActivity : AppCompatActivity() {

    private val fields = ArrayList<Pair<String, EditText>>()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        title = "Sozlamalar"
        val col = Ui.column(this)
        col.addView(Ui.text(this, "Kalitlar telefonda shifrlangan holda saqlanadi va hech kimga ko'rsatilmaydi."))

        fun field(key: String, label: String, secret: Boolean, hint: String = "") {
            col.addView(Ui.text(this, label, 15f))
            val e = EditText(this).apply {
                setText(SecureStore.get(key)); this.hint = hint; isSingleLine = true
                inputType = if (secret) InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD else InputType.TYPE_CLASS_TEXT
            }
            col.addView(e); fields.add(key to e)
        }

        col.addView(Ui.title(this, "AI kalitlari"))
        field(SecureStore.OPENAI_KEY, "OpenAI API kaliti (sk-...)", true, "sk-...")
        field(SecureStore.GEMINI_KEY, "Gemini API kaliti (zaxira, bepul)", true, "AIza...")

        col.addView(Ui.title(this, "Sulaymon ko'prigi"))
        field(SecureStore.BRIDGE_URL, "Server manzili", false, "https://sizning-serveringiz.uz")
        field(SecureStore.BRIDGE_TOKEN, "Maxfiy token", true)

        col.addView(Ui.title(this, "O'zini yaxshilash (GitHub)"))
        field(SecureStore.GITHUB_REPO, "GitHub repo (egasi/nomi)", false, "olim/dovud")
        field(SecureStore.GITHUB_TOKEN, "GitHub token (faqat Issues ruxsati)", true, "github_pat_...")

        col.addView(Ui.title(this, "Modellar (o'zgartirish shart emas)"))
        field(SecureStore.OPENAI_CHAT_MODEL, "Suhbat modeli", false)
        field(SecureStore.OPENAI_AGENT_MODEL, "Telefonni boshqarish modeli", false)
        field(SecureStore.OPENAI_STT_MODEL, "Nutqni matnga aylantirish", false)
        field(SecureStore.OPENAI_TTS_MODEL, "Ovoz modeli", false)
        field(SecureStore.OPENAI_TTS_VOICE, "Ovoz turi (onyx, alloy, echo, ash...)", false)
        field(SecureStore.GEMINI_MODEL, "Gemini modeli (auto = eng arzonini o'zi tanlaydi)", false)
        field(SecureStore.VOICE_THRESHOLD, "Ovoz izi chegarasi (0.3–0.8; kattaroq = qattiqroq)", false)

        val wake = CheckBox(this).apply { text = "\"Dovud\" so'zini doim eshitib turish"; isChecked = SecureStore.flag(SecureStore.WAKE_ENABLED) }
        val voice = CheckBox(this).apply { text = "Faqat mening ovozimga javob berish (ovoz izi)"; isChecked = SecureStore.flag(SecureStore.VOICE_CHECK) }
        col.addView(wake); col.addView(voice)

        col.addView(Ui.button(this, "💾 Saqlash") {
            for ((k, e) in fields) SecureStore.set(k, e.text.toString())
            SecureStore.set(SecureStore.WAKE_ENABLED, if (wake.isChecked) "1" else "0")
            SecureStore.set(SecureStore.VOICE_CHECK, if (voice.isChecked) "1" else "0")
            Toast.makeText(this, "Saqlandi", Toast.LENGTH_SHORT).show()
            finish()
        })
        col.addView(Ui.button(this, "Suhbat tarixini tozalash") { MemoryStore.clearHistory(); Toast.makeText(this, "Tozalandi", Toast.LENGTH_SHORT).show() })
        col.addView(Ui.title(this, "Bu oygi sarf"))
        col.addView(Ui.text(this, Usage.report(), 13f))
        setContentView(Ui.scroll(this, col))
    }
}
