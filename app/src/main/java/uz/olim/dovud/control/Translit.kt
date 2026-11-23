package uz.olim.dovud.control

/** Kirill ↔ lotin (o'zbekcha/ruscha) oddiy o'girish — ilova va kontakt nomlarini topish uchun. */
object Translit {
    private val c2l = mapOf(
        'а' to "a", 'б' to "b", 'в' to "v", 'г' to "g", 'д' to "d", 'е' to "e", 'ё' to "yo", 'ж' to "j", 'з' to "z",
        'и' to "i", 'й' to "y", 'к' to "k", 'л' to "l", 'м' to "m", 'н' to "n", 'о' to "o", 'п' to "p", 'р' to "r",
        'с' to "s", 'т' to "t", 'у' to "u", 'ф' to "f", 'х' to "x", 'ц' to "ts", 'ч' to "ch", 'ш' to "sh", 'щ' to "sh",
        'ъ' to "", 'ы' to "i", 'ь' to "", 'э' to "e", 'ю' to "yu", 'я' to "ya", 'ў' to "o'", 'қ' to "q", 'ғ' to "g'", 'ҳ' to "h"
    )

    fun toLatin(s: String): String {
        val sb = StringBuilder()
        for (ch in s) {
            val low = ch.lowercaseChar()
            val r = c2l[low]
            if (r == null) sb.append(ch) else sb.append(if (ch.isUpperCase()) r.replaceFirstChar { it.uppercase() } else r)
        }
        return sb.toString()
    }

    fun toCyrillic(s: String): String {
        var t = s.lowercase()
        val pairs = listOf("o'" to "ў", "g'" to "ғ", "sh" to "ш", "ch" to "ч", "yo" to "ё", "yu" to "ю", "ya" to "я", "ts" to "ц")
        for ((l, c) in pairs) t = t.replace(l, c)
        val single = mapOf('a' to 'а', 'b' to 'б', 'v' to 'в', 'g' to 'г', 'd' to 'д', 'e' to 'е', 'j' to 'ж', 'z' to 'з', 'i' to 'и',
            'y' to 'й', 'k' to 'к', 'l' to 'л', 'm' to 'м', 'n' to 'н', 'o' to 'о', 'p' to 'п', 'r' to 'р', 's' to 'с', 't' to 'т',
            'u' to 'у', 'f' to 'ф', 'x' to 'х', 'q' to 'қ', 'h' to 'ҳ')
        return t.map { single[it] ?: it }.joinToString("").replaceFirstChar { it.uppercase() }
    }
}
