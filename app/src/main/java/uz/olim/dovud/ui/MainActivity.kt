package uz.olim.dovud.ui

import android.content.Intent
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.view.Gravity
import android.view.inputmethod.EditorInfo
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.launch
import uz.olim.dovud.BuildConfig
import uz.olim.dovud.Bus
import uz.olim.dovud.service.DovudService
import uz.olim.dovud.settings.SecureStore
import uz.olim.dovud.update.Updater

class MainActivity : AppCompatActivity() {

    private lateinit var status: TextView
    private lateinit var log: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        title = "Dovud ${BuildConfig.VERSION_NAME}"
        val col = Ui.column(this)

        status = Ui.text(this, "…", 17f).apply { gravity = Gravity.CENTER }
        col.addView(status)

        val talk = Button(this).apply {
            text = "🎤\nGapirish"; textSize = 22f; isAllCaps = false; setTextColor(Color.WHITE)
            background = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(Color.parseColor("#1E6B52")) }
            val s = Ui.dp(this@MainActivity, 170)
            layoutParams = LinearLayout.LayoutParams(s, s).apply { gravity = Gravity.CENTER_HORIZONTAL; topMargin = Ui.dp(this@MainActivity, 12) }
            setOnClickListener { DovudService.instance?.talk() ?: DovudService.start(this@MainActivity) }
        }
        col.addView(talk)
        col.addView(Ui.button(this, "⏹ To'xta") { DovudService.instance?.stopAll() })

        val input = EditText(this).apply {
            hint = "Yoki buyruqni shu yerga yozing…"; imeOptions = EditorInfo.IME_ACTION_SEND; isSingleLine = true
            setOnEditorActionListener { v, _, _ ->
                val t = v.text.toString().trim()
                if (t.isNotEmpty()) { DovudService.instance?.submitText(t); v.text = "" }
                true
            }
        }
        col.addView(input)

        val r1 = Ui.row(this)
        r1.addView(Ui.button(this, "Ruxsatlar") { startActivity(Intent(this, SetupActivity::class.java)) }.apply { layoutParams = LinearLayout.LayoutParams(0, -2, 1f) })
        r1.addView(Ui.button(this, "Sozlamalar") { startActivity(Intent(this, SettingsActivity::class.java)) }.apply { layoutParams = LinearLayout.LayoutParams(0, -2, 1f) })
        col.addView(r1)
        val r2 = Ui.row(this)
        r2.addView(Ui.button(this, "Ovoz izi") { startActivity(Intent(this, VoiceEnrollActivity::class.java)) }.apply { layoutParams = LinearLayout.LayoutParams(0, -2, 1f) })
        r2.addView(Ui.button(this, "O'rganganlari") { startActivity(Intent(this, MemoryActivity::class.java)) }.apply { layoutParams = LinearLayout.LayoutParams(0, -2, 1f) })
        col.addView(r2)
        col.addView(Ui.button(this, "Yangilanishni tekshirish") { checkUpdate() })

        log = Ui.text(this, "", 13f).apply { setTextIsSelectable(true) }
        col.addView(log)
        setContentView(Ui.scroll(this, col))

        lifecycleScope.launch { Bus.status.collect { status.text = it } }
        lifecycleScope.launch { Bus.log.collect { log.text = it.takeLast(60).reversed().joinToString("\n") } }

        if (!SecureStore.flag(SecureStore.SETUP_DONE)) startActivity(Intent(this, SetupActivity::class.java))
        if (intent?.getBooleanExtra("update", false) == true) checkUpdate()
    }

    override fun onResume() {
        super.onResume()
        // Xizmatni ilova ochiqligida ishga tushiramiz (Android 14+ talabi)
        if (DovudService.instance == null && SetupActivity.micGranted(this)) DovudService.start(this)
    }

    private fun checkUpdate() {
        lifecycleScope.launch {
            val rel = Updater.newer()
            if (rel == null) { Bus.log("Tizim", "Yangi versiya yo'q (hozirgi: ${BuildConfig.VERSION_NAME})"); return@launch }
            androidx.appcompat.app.AlertDialog.Builder(this@MainActivity)
                .setTitle("Yangi versiya: ${rel.tag}")
                .setMessage(rel.notes.ifBlank { "O'rnataymi?" })
                .setPositiveButton("O'rnatish") { _, _ -> lifecycleScope.launch { Bus.log("Tizim", Updater.installLatest(this@MainActivity)) } }
                .setNegativeButton("Keyinroq", null).show()
        }
    }
}
