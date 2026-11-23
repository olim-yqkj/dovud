package uz.olim.dovud.settings

import android.content.Context
import android.content.SharedPreferences
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey

/**
 * Barcha kalitlar, tokenlar va sozlamalar shu yerda SHIFRLANGAN holda saqlanadi.
 * Kod ichida hech qanday kalit yozilmagan.
 */
object SecureStore {
    private lateinit var prefs: SharedPreferences

    // Kalit nomlari
    const val OPENAI_KEY = "openai_key"
    const val GEMINI_KEY = "gemini_key"
    const val OPENAI_CHAT_MODEL = "openai_chat_model"
    const val OPENAI_AGENT_MODEL = "openai_agent_model"
    const val OPENAI_STT_MODEL = "openai_stt_model"
    const val OPENAI_TTS_MODEL = "openai_tts_model"
    const val OPENAI_TTS_VOICE = "openai_tts_voice"
    const val GEMINI_MODEL = "gemini_model"
    const val BRIDGE_URL = "bridge_url"
    const val BRIDGE_TOKEN = "bridge_token"
    const val GITHUB_TOKEN = "github_token"
    const val GITHUB_REPO = "github_repo"
    const val VOICE_PRINT = "voice_print"
    const val VOICE_THRESHOLD = "voice_threshold"
    const val VOICE_CHECK = "voice_check"
    const val WAKE_ENABLED = "wake_enabled"
    const val MEMORY_JSON = "memory_json"
    const val USAGE_JSON = "usage_json"
    const val SETUP_DONE = "setup_done"
    const val MANUAL_STEPS = "manual_steps"

    val defaults = mapOf(
        OPENAI_CHAT_MODEL to "gpt-5.4-nano",
        OPENAI_AGENT_MODEL to "gpt-5.4-mini",
        OPENAI_STT_MODEL to "gpt-4o-mini-transcribe",
        OPENAI_TTS_MODEL to "gpt-4o-mini-tts",
        OPENAI_TTS_VOICE to "onyx",
        GEMINI_MODEL to "auto",
        VOICE_CHECK to "1",
        WAKE_ENABLED to "1",
    )

    fun init(ctx: Context) {
        prefs = try {
            create(ctx)
        } catch (e: Exception) {
            // Shifrlash kaliti buzilgan bo'lsa (masalan, zaxiradan tiklanganda) — yangidan boshlaymiz
            ctx.deleteSharedPreferences("dovud_secure")
            create(ctx)
        }
    }

    private fun create(ctx: Context): SharedPreferences {
        val mk = MasterKey.Builder(ctx).setKeyScheme(MasterKey.KeyScheme.AES256_GCM).build()
        return EncryptedSharedPreferences.create(
            ctx, "dovud_secure", mk,
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
        )
    }

    fun get(key: String): String = prefs.getString(key, null)?.takeIf { it.isNotEmpty() } ?: (defaults[key] ?: "")
    fun set(key: String, value: String) { prefs.edit().putString(key, value.trim()).apply() }
    fun has(key: String): Boolean = get(key).isNotBlank()
    fun flag(key: String): Boolean = get(key) == "1"
}
