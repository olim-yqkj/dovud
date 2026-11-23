package uz.olim.dovud.safety

/**
 * QULFLANGAN QISM. Qaysi amal "muhim" ekanini belgilaydi.
 * Muhim amal = tasdiqsiz bajarilmaydi. Bu ro'yxatni AI o'zgartira olmaydi.
 */
object RiskPolicy {

    /** Har doim tasdiq talab qiladigan asboblar. */
    val alwaysRiskyTools = setOf(
        "call", "send_sms", "reply_notification", "request_improvement", "request_rollback", "install_update"
    )

    private val riskyWords = listOf(
        // o'zbekcha
        "to'la", "tola", "to‘la", "toʻla", "o'tkaz", "otkaz", "o‘tkaz", "oʻtkaz", "sotib ol", "xarid", "yubor",
        "o'chir", "ochir", "o‘chir", "oʻchir", "tasdiqla", "parol", "pin", "kod", "obuna", "buyurtma",
        "qo'ng'iroq", "qongiroq", "jo'nat", "jonat", "tozala", "formatla", "chiqib ket", "akkauntdan",
        // ruscha
        "оплат", "плат", "перев", "купит", "покуп", "отправ", "удал", "подтверд", "пароль", "заказ", "подпис",
        "звон", "сбрось", "очист",
        // inglizcha
        "pay", "send", "transfer", "buy", "purchase", "delete", "remove", "confirm", "password", "order",
        "subscribe", "checkout", "erase", "reset", "uninstall", "sign out", "log out", "call"
    )

    fun textIsRisky(text: String?): Boolean {
        if (text.isNullOrBlank()) return false
        val t = text.lowercase()
        return riskyWords.any { w ->
            if (w.length <= 4) Regex("""(^|[^\p{L}])${Regex.escape(w)}""").containsMatchIn(t) else t.contains(w)
        }
    }
}
