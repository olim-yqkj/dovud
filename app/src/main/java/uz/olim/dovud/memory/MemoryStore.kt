package uz.olim.dovud.memory

import org.json.JSONArray
import org.json.JSONObject
import uz.olim.dovud.settings.SecureStore

/**
 * Dovudning xotirasi (shifrlangan):
 *  - history: oxirgi suhbat (tabiiy muloqot uchun)
 *  - lessons: Dovud o'rgangan narsalar (Olim akaning ruxsati bilan saqlangan)
 */
object MemoryStore {
    data class Lesson(val id: Long, val kind: String, val text: String)

    private val history = ArrayList<Pair<String, String>>() // role to text
    private val lessons = ArrayList<Lesson>()
    private var loaded = false

    @Synchronized
    private fun load() {
        if (loaded) return
        loaded = true
        val j = runCatching { JSONObject(SecureStore.get(SecureStore.MEMORY_JSON)) }.getOrNull() ?: return
        j.optJSONArray("h")?.let { a -> for (i in 0 until a.length()) { val o = a.getJSONObject(i); history.add(o.getString("r") to o.getString("t")) } }
        j.optJSONArray("l")?.let { a -> for (i in 0 until a.length()) { val o = a.getJSONObject(i); lessons.add(Lesson(o.getLong("id"), o.getString("k"), o.getString("t"))) } }
    }

    @Synchronized
    private fun save() {
        val h = JSONArray(); history.forEach { h.put(JSONObject().put("r", it.first).put("t", it.second)) }
        val l = JSONArray(); lessons.forEach { l.put(JSONObject().put("id", it.id).put("k", it.kind).put("t", it.text)) }
        SecureStore.set(SecureStore.MEMORY_JSON, JSONObject().put("h", h).put("l", l).toString())
    }

    @Synchronized fun addTurn(role: String, text: String) {
        load(); history.add(role to text.take(800))
        while (history.size > 24) history.removeAt(0)
        save()
    }

    @Synchronized fun history(): List<Pair<String, String>> { load(); return history.toList() }
    @Synchronized fun clearHistory() { load(); history.clear(); save() }

    @Synchronized fun addLesson(kind: String, text: String) {
        load(); lessons.add(Lesson(System.currentTimeMillis(), kind, text.take(600))); save()
    }
    @Synchronized fun lessons(): List<Lesson> { load(); return lessons.toList() }
    @Synchronized fun deleteLesson(id: Long) { load(); lessons.removeAll { it.id == id }; save() }

    /** Buyruqqa eng mos keladigan o'rganilgan narsalar (oddiy so'z mosligi bo'yicha). */
    fun relevant(query: String, max: Int = 8): List<Lesson> {
        val words = query.lowercase().split(Regex("[^\\p{L}\\p{N}]+")).filter { it.length > 2 }.toSet()
        val all = lessons()
        val facts = all.filter { it.kind == "fakt" }.takeLast(10)
        val scored = all.filter { it.kind != "fakt" }.map { l ->
            val lw = l.text.lowercase()
            l to words.count { lw.contains(it.take(5)) }
        }.filter { it.second > 0 }.sortedByDescending { it.second }.map { it.first }
        return (facts + scored).distinct().take(max)
    }
}
