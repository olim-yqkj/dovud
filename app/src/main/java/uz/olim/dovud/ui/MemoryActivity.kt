package uz.olim.dovud.ui

import android.os.Bundle
import android.widget.LinearLayout
import androidx.appcompat.app.AppCompatActivity
import uz.olim.dovud.memory.MemoryStore

/** "Dovud nimani o'rgandi" — ko'rish va o'chirish. */
class MemoryActivity : AppCompatActivity() {
    private lateinit var col: LinearLayout

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        title = "Dovud nimani o'rgandi"
        col = Ui.column(this)
        setContentView(Ui.scroll(this, col))
        render()
    }

    private fun render() {
        col.removeAllViews()
        val ls = MemoryStore.lessons()
        col.addView(Ui.text(this, if (ls.isEmpty()) "Hozircha hech narsa o'rganilmagan." else "Keraksizini o'chiring:"))
        for (l in ls.reversed()) {
            col.addView(Ui.text(this, "• [${l.kind}] ${l.text}", 14f))
            col.addView(Ui.button(this, "🗑 O'chirish") { MemoryStore.deleteLesson(l.id); render() })
        }
    }
}
