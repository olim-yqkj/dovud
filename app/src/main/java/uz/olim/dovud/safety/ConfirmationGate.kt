package uz.olim.dovud.safety

/**
 * QULFLANGAN QISM. Tasdiq so'zlarini tekshiradi.
 * Faqat "Tasdiqlayman" (yoki "ha, tasdiqlayman") — tasdiq. "Yo'q"/"bekor" — rad.
 */
object ConfirmationGate {
    enum class Answer { CONFIRM, REJECT, UNCLEAR }

    private fun norm(s: String) = s.lowercase()
        .replace(Regex("[ʻ‘’`']"), "")
        .replace(Regex("[^\\p{L}\\p{N} ]"), " ")
        .replace(Regex("\\s+"), " ").trim()

    fun parse(text: String): Answer {
        val t = norm(text)
        if (t.isBlank()) return Answer.UNCLEAR
        val confirm = listOf("tasdiqlayman", "tasdiqlaymn", "tasdiklayman", "подтверждаю", "i confirm", "тасдиқлайман", "тасдиклайман")
        val reject = listOf("yoq", "yuq", "bekor", "kerakmas", "kerak emas", "toxta", "нет", "отмена", "не надо", "cancel", "no ", "stop", "йук", "йўқ", "бекор")
        if (reject.any { (" $t ").contains(" ${it.trim()} ") || t.startsWith(it.trim()) }) return Answer.REJECT
        if (confirm.any { t.contains(it) }) return Answer.CONFIRM
        return Answer.UNCLEAR
    }

    fun isYes(text: String): Boolean {
        val t = norm(text)
        return listOf("ha", "xa", "hа", "davom", "mayli", "eslab qol", "tasdiqlayman", "да", "yes", "ok", "хорошо", "давай")
            .any { (" $t ").contains(" $it") }
    }
}
