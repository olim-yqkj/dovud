package uz.olim.dovud.safety

import org.json.JSONArray
import org.json.JSONObject
import org.vosk.Recognizer
import uz.olim.dovud.settings.SecureStore
import uz.olim.dovud.voice.Pcm
import uz.olim.dovud.voice.VoskModels
import kotlin.math.sqrt

/**
 * QULFLANGAN QISM. Dovud o'zini o'zi yangilaganda bu faylni o'zgartira olmaydi.
 * Ovoz izi: telefonning o'zida, Vosk x-vector modeli orqali.
 */
object SpeakerVerifier {

    data class Analysis(val offlineText: String, val vector: FloatArray?)

    /** Ovozni tahlil qiladi: internetsiz matn + ovoz izi vektori. */
    fun analyze(pcm: ShortArray): Analysis {
        val m = VoskModels.model ?: return Analysis("", null)
        val spk = VoskModels.speaker
        val rec = if (spk != null) Recognizer(m, Pcm.RATE.toFloat(), spk) else Recognizer(m, Pcm.RATE.toFloat())
        return try {
            var i = 0
            val chunk = 4000
            while (i < pcm.size) {
                val n = minOf(chunk, pcm.size - i)
                rec.acceptWaveForm(pcm.copyOfRange(i, i + n), n)
                i += n
            }
            val j = JSONObject(rec.finalResult)
            val v = j.optJSONArray("spk")?.let { arr -> FloatArray(arr.length()) { arr.getDouble(it).toFloat() } }
            Analysis(j.optString("text"), v)
        } catch (e: Exception) {
            Analysis("", null)
        } finally {
            rec.close()
        }
    }

    fun isEnrolled(): Boolean = SecureStore.has(SecureStore.VOICE_PRINT)
    fun isEnabled(): Boolean = SecureStore.flag(SecureStore.VOICE_CHECK) && isEnrolled()

    /** Ro'yxatdan o'tkazish: bir nechta namunadan o'rtacha ovoz izi va chegarani hisoblaydi. */
    fun enroll(samples: List<FloatArray>): Float {
        require(samples.size >= 2)
        val dim = samples[0].size
        val mean = FloatArray(dim)
        for (s in samples) { val ns = norm(s); for (k in 0 until dim) mean[k] += ns[k] / samples.size }
        val sims = samples.map { cosine(it, mean) }
        // Chegara: eng past o'xshashlikdan biroz pastroq, lekin oqilona oraliqda
        val thr = (sims.min() - 0.12f).coerceIn(0.30f, 0.80f)
        val arr = JSONArray(); mean.forEach { arr.put(it.toDouble()) }
        SecureStore.set(SecureStore.VOICE_PRINT, arr.toString())
        SecureStore.set(SecureStore.VOICE_THRESHOLD, thr.toString())
        return thr
    }

    fun similarity(v: FloatArray): Float? {
        val s = SecureStore.get(SecureStore.VOICE_PRINT)
        if (s.isBlank()) return null
        val arr = JSONArray(s)
        val ref = FloatArray(arr.length()) { arr.getDouble(it).toFloat() }
        if (ref.size != v.size) return null
        return cosine(v, ref)
    }

    /**
     * true = Olim aka, false = begona, null = aniqlab bo'lmadi (gap juda qisqa yoki ovoz izi yozilmagan).
     */
    fun isOwner(v: FloatArray?): Boolean? {
        if (!isEnabled()) return null
        if (v == null) return null
        val sim = similarity(v) ?: return null
        val thr = SecureStore.get(SecureStore.VOICE_THRESHOLD).toFloatOrNull() ?: 0.5f
        return sim >= thr
    }

    private fun norm(v: FloatArray): FloatArray {
        var s = 0.0; for (x in v) s += x * x
        val n = sqrt(s).toFloat().takeIf { it > 0 } ?: 1f
        return FloatArray(v.size) { v[it] / n }
    }

    private fun cosine(a: FloatArray, b: FloatArray): Float {
        var d = 0.0; var na = 0.0; var nb = 0.0
        for (i in a.indices) { d += a[i] * b[i]; na += a[i] * a[i]; nb += b[i] * b[i] }
        return if (na == 0.0 || nb == 0.0) 0f else (d / (sqrt(na) * sqrt(nb))).toFloat()
    }
}
