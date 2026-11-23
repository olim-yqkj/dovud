package uz.olim.dovud.brain

import uz.olim.dovud.control.PhoneActions
import uz.olim.dovud.control.Translit

/**
 * Oddiy buyruqlarni AI'siz, telefonning o'zida tushunadi — tez, bepul va internetsiz ishlaydi.
 */
object LocalCommands {
    data class Local(val risky: Boolean, val describe: String, val run: suspend () -> String)

    fun norm(s: String): String = Translit.toLatin(s.lowercase())
        .replace(Regex("[ʻ‘’`]"), "'")
        .replace(Regex("^(dovud|dovut|davud|dawud|dovid)[,.!\\s]*"), "")
        .replace(Regex("[,.!?]"), " ")
        .replace(Regex("\\s+"), " ").trim()

    private val units = mapOf("nol" to 0, "bir" to 1, "ikki" to 2, "uch" to 3, "to'rt" to 4, "tort" to 4, "besh" to 5, "olti" to 6,
        "yetti" to 7, "sakkiz" to 8, "to'qqiz" to 9, "toqqiz" to 9)
    private val tens = mapOf("o'n" to 10, "on" to 10, "yigirma" to 20, "o'ttiz" to 30, "ottiz" to 30, "qirq" to 40, "ellik" to 50)

    /** "yetti yarim", "olti o'ttiz", "7:30", "7" → soat va daqiqa */
    fun parseTime(t: String): Pair<Int, Int>? {
        Regex("""(\d{1,2})\s*[:.]\s*(\d{2})""").find(t)?.let { return it.groupValues[1].toInt() to it.groupValues[2].toInt() }
        val words = t.split(" ")
        val nums = ArrayList<Int>()
        var i = 0
        while (i < words.size) {
            val w = words[i].removeSuffix("ga").removeSuffix("da")
            val d = w.toIntOrNull()
            if (d != null) { nums.add(d); i++; continue }
            val tv = tens[w]
            if (tv != null) {
                val next = words.getOrNull(i + 1)?.removeSuffix("ga")?.let { units[it] }
                nums.add(tv + (next ?: 0)); i += if (next != null) 2 else 1; continue
            }
            units[w]?.let { nums.add(it) }
            i++
        }
        if (nums.isEmpty()) return null
        var h = nums[0]; var m = nums.getOrNull(1) ?: 0
        if (t.contains("yarim") || t.contains("половин")) m = 30
        if ((t.contains("kechqurun") || t.contains("kechki") || t.contains("vechera") || t.contains("tushdan keyin")) && h < 12) h += 12
        if (h !in 0..23 || m !in 0..59) return null
        return h to m
    }

    fun match(raw: String, phone: PhoneActions): Local? {
        val t = norm(raw)
        if (t.isBlank()) return null

        // Ilova ochish
        Regex("""^(.+?)\s*(ni|ny)?\s+(och|ochib ber|ochgin|oching|ochsang|ochib qo'y)\b.*$""").find(t)?.let { m ->
            val name = m.groupValues[1].removeSuffix("ilovasi").removeSuffix(" ilovasini").trim()
            if (name.split(" ").size <= 3 && phone.findApp(name) != null) return Local(false, "") { phone.openApp(name) }
        }
        Regex("""^(otkroy|zapusti|open|launch)\s+(.+)$""").find(t)?.let { m ->
            val name = m.groupValues[2]
            if (phone.findApp(name) != null) return Local(false, "") { phone.openApp(name) }
        }

        // Budilnik
        if (t.contains("budilnik") || t.contains("uyg'ot") || t.contains("alarm")) {
            parseTime(t)?.let { (h, m) -> return Local(false, "") { phone.setAlarm(h, m, null) } }
        }

        // Taymer
        Regex("""(\d+|\p{L}+)\s*(daqiqa|minut|minute)""").find(t)?.takeIf { t.contains("taymer") || t.contains("taimer") || t.contains("timer") }?.let { m ->
            val n = m.groupValues[1].toIntOrNull() ?: parseTime(m.groupValues[1])?.first ?: return@let
            return Local(false, "") { phone.setTimer(n * 60) }
        }

        // Chiroq
        if (t.contains("chiroq") || t.contains("fonar") || t.contains("flashlight")) {
            val off = t.contains("o'chir") || t.contains("ochir") || t.contains("vyklyuch") || t.contains(" off")
            return Local(false, "") { phone.flashlight(!off) }
        }

        // Qo'ng'iroq
        Regex("""^(.+?)\s*(ga|ge)\s+(qo'ng'iroq|qongiroq|zvonok|tel)\s*(qil|qilib ber|qiling|qilgin)?.*$""").find(t)?.let { m ->
            val who = m.groupValues[1].trim()
            return Local(true, "$who ga qo'ng'iroq qilaman") { phone.call(who) }
        }
        Regex("""^(pozvoni|call)\s+(.+)$""").find(t)?.let { m ->
            val who = m.groupValues[2].trim()
            return Local(true, "$who ga qo'ng'iroq qilaman") { phone.call(who) }
        }
        return null
    }

    /** "Sulaymonga ayt: ..." */
    fun sulaymonText(raw: String): String? {
        val t = Translit.toLatin(raw).trim().replace(Regex("^(?i)(dovud|dovut|davud)[,.!\\s]*"), "")
        val m = Regex("""^(?i)sulaymonga\s*(ayt|aytib qo'y|ayting|de|buyur|so'ra|sora)?[:,\s-]+(.+)$""").find(t) ?: return null
        val body = m.groupValues[2].trim()
        return body.ifBlank { null }
    }
}
