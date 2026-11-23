package uz.olim.dovud.voice

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.util.Base64
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import uz.olim.dovud.ApiException
import uz.olim.dovud.Net
import uz.olim.dovud.brain.GeminiClient
import uz.olim.dovud.safety.StopController
import uz.olim.dovud.settings.SecureStore
import uz.olim.dovud.memory.Usage
import java.util.Locale
import kotlin.coroutines.resume

/** Nutqni matnga aylantirish (bulut). Modelni almashtirish oson bo'lishi uchun interfeys. */
interface SpeechToText { suspend fun transcribe(pcm: ShortArray): String }
interface TextToSpeechOut { suspend fun speak(text: String) ; fun stop() }

class OpenAiStt : SpeechToText {
    override suspend fun transcribe(pcm: ShortArray): String = withContext(Dispatchers.IO) {
        val key = SecureStore.get(SecureStore.OPENAI_KEY)
        if (key.isBlank()) throw ApiException(401, "OpenAI kaliti kiritilmagan")
        val models = listOf(SecureStore.get(SecureStore.OPENAI_STT_MODEL), "gpt-transcribe", "whisper-1").distinct()
        var last: Exception? = null
        for (m in models) {
            try {
                val body = MultipartBody.Builder().setType(MultipartBody.FORM)
                    .addFormDataPart("model", m)
                    .addFormDataPart("prompt", "Dovud, Olim aka, Sulaymon, YQKJ, Telegram, tasdiqlayman, to'xta. O'zbekcha, ba'zan ruscha yoki inglizcha.")
                    .addFormDataPart("file", "a.wav", Pcm.wav(pcm).toRequestBody("audio/wav".toMediaType()))
                    .build()
                val req = Request.Builder().url("https://api.openai.com/v1/audio/transcriptions")
                    .header("Authorization", "Bearer $key").post(body).build()
                Net.http.newCall(req).execute().use { r ->
                    val s = r.body?.string() ?: ""
                    if (!r.isSuccessful) throw ApiException(r.code, s.take(300))
                    Usage.addSttSeconds(Pcm.durationSec(pcm))
                    return@withContext JSONObject(s).optString("text").trim()
                }
            } catch (e: ApiException) {
                last = e
                if (e.code != 404 && e.code != 400) throw e // faqat model topilmasa keyingisini sinaymiz
            }
        }
        throw last ?: ApiException(0, "STT xatosi")
    }
}

class GeminiStt : SpeechToText {
    override suspend fun transcribe(pcm: ShortArray): String = withContext(Dispatchers.IO) {
        val parts = JSONArray()
            .put(JSONObject().put("text", "Transcribe this audio exactly as spoken (Uzbek Latin, Russian or English). Return only the transcript."))
            .put(JSONObject().put("inline_data", JSONObject().put("mime_type", "audio/wav")
                .put("data", Base64.encodeToString(Pcm.wav(pcm), Base64.NO_WRAP))))
        val body = JSONObject().put("contents", JSONArray().put(JSONObject().put("role", "user").put("parts", parts)))
        val j = GeminiClient.rawGenerate(body)
        GeminiClient.textOf(j).trim()
    }
}

/** OpenAI ovozi: PCM oqim sifatida o'ynaladi — birinchi so'z tez eshitiladi. */
class OpenAiTts : TextToSpeechOut {
    @Volatile private var track: AudioTrack? = null
    @Volatile private var cancelled = false

    override suspend fun speak(text: String): Unit = withContext(Dispatchers.IO) {
        val key = SecureStore.get(SecureStore.OPENAI_KEY)
        if (key.isBlank()) throw ApiException(401, "OpenAI kaliti kiritilmagan")
        cancelled = false
        val model = SecureStore.get(SecureStore.OPENAI_TTS_MODEL)
        val body = JSONObject()
            .put("model", model)
            .put("voice", SecureStore.get(SecureStore.OPENAI_TTS_VOICE))
            .put("input", text)
            .put("response_format", "pcm")
        if (!model.startsWith("tts-1")) body.put("instructions",
            "Gapiruvchi — Dovud, samimiy va hurmatli yordamchi. Matn tilida tabiiy talaffuz bilan, tez va aniq gapir. O'zbekcha matnni o'zbekcha talaffuz qil.")
        val req = Request.Builder().url("https://api.openai.com/v1/audio/speech")
            .header("Authorization", "Bearer $key")
            .post(body.toString().toRequestBody("application/json".toMediaType())).build()
        Net.http.newCall(req).execute().use { r ->
            if (!r.isSuccessful) throw ApiException(r.code, r.body?.string()?.take(300) ?: "")
            Usage.addTtsChars(text.length)
            val rate = 24000
            val minBuf = AudioTrack.getMinBufferSize(rate, AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_16BIT)
            val t = AudioTrack.Builder()
                .setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_ASSISTANT)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build())
                .setAudioFormat(AudioFormat.Builder().setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                    .setSampleRate(rate).setChannelMask(AudioFormat.CHANNEL_OUT_MONO).build())
                .setBufferSizeInBytes(maxOf(minBuf, 8192))
                .setTransferMode(AudioTrack.MODE_STREAM).build()
            track = t
            t.play()
            val inp = r.body!!.byteStream()
            val buf = ByteArray(4096)
            var carry = -1
            var total = 0L
            try {
                while (!cancelled && !StopController.stopped) {
                    val n = inp.read(buf)
                    if (n <= 0) break
                    // 16-bit namunalar juft baytda bo'lishi kerak
                    var data = buf.copyOf(n)
                    if (carry >= 0) { data = byteArrayOf(carry.toByte()) + data; carry = -1 }
                    if (data.size % 2 == 1) { carry = data.last().toInt() and 0xff; data = data.copyOf(data.size - 1) }
                    t.write(data, 0, data.size)
                    total += data.size
                }
                // Oxirgi tovush tugashini kutamiz
                val frames = total / 2
                val deadline = System.currentTimeMillis() + 15000
                while (!cancelled && !StopController.stopped && t.playbackHeadPosition < frames && System.currentTimeMillis() < deadline) {
                    Thread.sleep(30)
                }
            } finally {
                runCatching { t.stop() }; t.release(); track = null
            }
        }
    }

    override fun stop() { cancelled = true; runCatching { track?.pause(); track?.flush() } }
}

/** Telefonning o'z ovozi — internet yo'q bo'lganda. */
class AndroidTts(ctx: Context) : TextToSpeechOut {
    @Volatile private var ready = false
    private val tts: TextToSpeech = TextToSpeech(ctx.applicationContext) { st ->
        ready = st == TextToSpeech.SUCCESS
        if (ready) pickLang()
    }

    private fun pickLang() {
        for (loc in listOf(Locale("uz", "UZ"), Locale("uz"), Locale("tr", "TR"), Locale("ru", "RU"))) {
            val r = tts.setLanguage(loc)
            if (r != TextToSpeech.LANG_MISSING_DATA && r != TextToSpeech.LANG_NOT_SUPPORTED) return
        }
    }

    override suspend fun speak(text: String) {
        var waited = 0
        while (!ready && waited < 3000) { kotlinx.coroutines.delay(100); waited += 100 }
        if (!ready) return
        suspendCancellableCoroutine { cont ->
            val id = "u" + System.nanoTime()
            tts.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                override fun onStart(utteranceId: String?) {}
                override fun onDone(utteranceId: String?) { if (cont.isActive) cont.resume(Unit) }
                @Deprecated("") override fun onError(utteranceId: String?) { if (cont.isActive) cont.resume(Unit) }
                override fun onStop(utteranceId: String?, interrupted: Boolean) { if (cont.isActive) cont.resume(Unit) }
            })
            tts.speak(text, TextToSpeech.QUEUE_FLUSH, null, id)
            cont.invokeOnCancellation { tts.stop() }
        }
    }

    override fun stop() { tts.stop() }
}
