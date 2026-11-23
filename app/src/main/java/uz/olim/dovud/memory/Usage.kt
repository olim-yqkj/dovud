package uz.olim.dovud.memory

import org.json.JSONObject
import uz.olim.dovud.settings.SecureStore
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Oylik sarfni hisoblash uchun token/daqiqa hisoblagich. */
object Usage {
    @Synchronized
    private fun update(block: (JSONObject) -> Unit) {
        val month = SimpleDateFormat("yyyy-MM", Locale.US).format(Date())
        val all = runCatching { JSONObject(SecureStore.get(SecureStore.USAGE_JSON)) }.getOrDefault(JSONObject())
        val m = all.optJSONObject(month) ?: JSONObject()
        block(m)
        all.put(month, m)
        SecureStore.set(SecureStore.USAGE_JSON, all.toString())
    }

    fun addLlm(model: String, inTok: Int, outTok: Int) = update { m ->
        m.put("in:$model", m.optLong("in:$model") + inTok)
        m.put("out:$model", m.optLong("out:$model") + outTok)
    }
    fun addSttSeconds(s: Double) = update { it.put("stt_sec", it.optDouble("stt_sec", 0.0) + s) }
    fun addTtsChars(n: Int) = update { it.put("tts_chars", it.optLong("tts_chars") + n) }
    fun addCommand() = update { it.put("commands", it.optLong("commands") + 1) }

    fun report(): String {
        val month = SimpleDateFormat("yyyy-MM", Locale.US).format(Date())
        val m = runCatching { JSONObject(SecureStore.get(SecureStore.USAGE_JSON)).optJSONObject(month) }.getOrNull() ?: return "Bu oy hali sarf yo'q."
        val sb = StringBuilder("Oy: $month\n")
        for (k in m.keys()) sb.append("$k = ${m.get(k)}\n")
        return sb.toString()
    }
}
