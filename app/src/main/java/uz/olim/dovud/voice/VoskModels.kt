package uz.olim.dovud.voice

import android.content.Context
import android.content.res.AssetManager
import org.vosk.Model
import org.vosk.SpeakerModel
import java.io.File

/**
 * Telefonning o'zida ishlaydigan nutq modellari (internet kerak emas):
 *  - "uz"  : o'zbekcha kichik model — "Dovud" so'zini eshitish va internetsiz buyruqlar uchun
 *  - "spk" : ovoz izi (x-vector) modeli — Olim akaning ovozini tanish uchun
 * Modellar APK ichida keladi va birinchi ishga tushganda xotiraga ko'chiriladi.
 */
object VoskModels {
    @Volatile var model: Model? = null
        private set
    @Volatile var speaker: SpeakerModel? = null
        private set
    @Volatile var wakeWords: List<String> = emptyList()
        private set
    var error: String? = null
        private set

    private const val VERSION = "uz-0.22+spk-0.4"

    @Synchronized
    fun ensure(ctx: Context): Boolean {
        if (model != null) return true
        return try {
            val dir = File(ctx.filesDir, "vosk")
            val marker = File(dir, ".ver")
            if (!marker.exists() || marker.readText() != VERSION) {
                dir.deleteRecursively()
                val list = ctx.assets.list("vosk") ?: emptyArray()
                if (list.isEmpty()) { error = "Nutq modeli APK ichida yo'q"; return false }
                copyAssets(ctx.assets, "vosk", dir)
                marker.writeText(VERSION)
            }
            org.vosk.LibVosk.setLogLevel(org.vosk.LogLevel.WARNINGS)
            val uz = File(dir, "uz")
            model = Model(uz.absolutePath)
            val spk = File(dir, "spk")
            if (spk.exists()) speaker = SpeakerModel(spk.absolutePath)
            wakeWords = findWakeWords(uz)
            true
        } catch (e: Throwable) {
            error = e.message ?: e.toString()
            false
        }
    }

    /** Model lug'atida bor "Dovud" variantlarini topamiz (grammatika rejimi uchun). */
    private fun findWakeWords(uz: File): List<String> {
        val candidates = setOf(
            "dovud", "dovut", "davud", "dovid", "dovuddan", "dovudga", "dovudni",
            "to'xta", "toʻxta", "toxta", "to‘xta", "stop", "bas", "yetar"
        )
        val words = listOf(File(uz, "graph/words.txt"), File(uz, "words.txt")).firstOrNull { it.exists() }
            ?: return emptyList()
        val found = ArrayList<String>()
        words.forEachLine { line ->
            val w = line.substringBefore(' ').trim()
            if (w in candidates) found.add(w)
        }
        return found
    }

    private fun copyAssets(am: AssetManager, path: String, dest: File) {
        val children = am.list(path) ?: emptyArray()
        if (children.isEmpty()) {
            dest.parentFile?.mkdirs()
            am.open(path).use { inp -> dest.outputStream().use { inp.copyTo(it) } }
        } else {
            dest.mkdirs()
            for (c in children) copyAssets(am, "$path/$c", File(dest, c))
        }
    }
}
