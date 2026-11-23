package uz.olim.dovud.bridge

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import uz.olim.dovud.Net
import uz.olim.dovud.safety.StopController
import uz.olim.dovud.settings.SecureStore

/**
 * Sulaymon ko'prigi: telefon → (HTTPS, maxfiy token) → server → kompyuterdagi Sulaymon → javob → telefon.
 */
object SulaymonBridge {

    fun configured() = SecureStore.has(SecureStore.BRIDGE_URL) && SecureStore.has(SecureStore.BRIDGE_TOKEN)

    private fun base() = SecureStore.get(SecureStore.BRIDGE_URL).trimEnd('/')
    private fun auth(b: Request.Builder) = b.header("Authorization", "Bearer ${SecureStore.get(SecureStore.BRIDGE_TOKEN)}")

    suspend fun ask(text: String, timeoutSec: Int = 120, onWaiting: suspend () -> Unit = {}): String = withContext(Dispatchers.IO) {
        if (!configured()) return@withContext "Sulaymon ko'prigi sozlanmagan. Sozlamalarga server manzili va tokenni kiriting."
        if (!base().startsWith("https://")) return@withContext "Xavfsizlik uchun server manzili https:// bilan boshlanishi kerak."
        val body = JSONObject().put("text", text).toString().toRequestBody("application/json".toMediaType())
        val id = try {
            Net.http.newCall(auth(Request.Builder().url("${base()}/dovud/send")).post(body).build()).execute().use { r ->
                if (r.code == 401) return@withContext "Server tokenni qabul qilmadi (token noto'g'ri)."
                if (!r.isSuccessful) return@withContext "Server xatosi: ${r.code}"
                JSONObject(r.body!!.string()).getString("id")
            }
        } catch (e: Exception) { return@withContext "Serverga ulanib bo'lmadi: ${e.message}" }

        val start = System.currentTimeMillis()
        var announced = false
        while (System.currentTimeMillis() - start < timeoutSec * 1000L) {
            if (StopController.stopped) return@withContext "To'xtatildi."
            delay(1500)
            if (!announced && System.currentTimeMillis() - start > 6000) { announced = true; onWaiting() }
            try {
                Net.http.newCall(auth(Request.Builder().url("${base()}/dovud/result/$id")).get().build()).execute().use { r ->
                    if (r.isSuccessful) {
                        val j = JSONObject(r.body!!.string())
                        if (j.optString("status") == "done") return@withContext j.optString("answer", "(bo'sh javob)")
                    }
                }
            } catch (_: Exception) {}
        }
        "Sulaymon $timeoutSec soniyada javob bermadi. Kompyuter yoqilganmi va Sulaymon ishlayaptimi?"
    }
}
