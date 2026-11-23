package uz.olim.dovud.service

import android.app.Notification
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.media.AudioManager
import android.media.ToneGenerator
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import uz.olim.dovud.Bus
import uz.olim.dovud.DovudApp
import uz.olim.dovud.Net
import uz.olim.dovud.R
import uz.olim.dovud.brain.Agent
import uz.olim.dovud.brain.FailoverLlm
import uz.olim.dovud.brain.GeminiClient
import uz.olim.dovud.brain.Heard
import uz.olim.dovud.brain.Io
import uz.olim.dovud.brain.OpenAiClient
import uz.olim.dovud.safety.SpeakerVerifier
import uz.olim.dovud.safety.StopController
import uz.olim.dovud.settings.SecureStore
import uz.olim.dovud.ui.MainActivity
import uz.olim.dovud.update.Updater
import uz.olim.dovud.voice.AndroidTts
import uz.olim.dovud.voice.GeminiStt
import uz.olim.dovud.voice.MicEngine
import uz.olim.dovud.voice.OpenAiStt
import uz.olim.dovud.voice.OpenAiTts
import uz.olim.dovud.voice.VoskModels

/**
 * Fonda doim ishlaydigan xizmat: mikrofon, "Dovud" so'zi, ovoz ↔ miya ↔ telefon.
 */
class DovudService : Service(), MicEngine.Listener, Io {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private lateinit var mic: MicEngine
    private lateinit var agent: Agent
    private val openAiTts = OpenAiTts()
    private lateinit var androidTts: AndroidTts
    private val stt = OpenAiStt()
    private val sttBackup = GeminiStt()

    @Volatile private var busy = false
    @Volatile private var watching = false
    @Volatile private var speaking = false
    @Volatile private var waiter: CompletableDeferred<ShortArray?>? = null
    private var tone: ToneGenerator? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        instance = this
        try { goForeground() } catch (e: Exception) {
            Bus.log("Xato", "Fon xizmati ishga tushmadi: ${e.message}")
            instance = null; stopSelf(); return
        }
        androidTts = AndroidTts(this)
        tone = runCatching { ToneGenerator(AudioManager.STREAM_MUSIC, 70) }.getOrNull()
        agent = Agent(this, this, FailoverLlm(OpenAiClient(), GeminiClient()))
        mic = MicEngine(this)
        StopController.onStop { openAiTts.stop(); androidTts.stop() }
        scope.launch {
            Bus.status("Nutq modeli yuklanyapti…")
            if (!VoskModels.ensure(this@DovudService)) Bus.log("Xato", "Nutq modeli: ${VoskModels.error}")
            mic.start()
            idle()
            checkUpdate()
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_TALK -> talk()
            ACTION_STOP -> stopAll()
        }
        return START_STICKY
    }

    override fun onDestroy() {
        instance = null
        if (::mic.isInitialized) runCatching { mic.stop() }
        scope.cancel()
        tone?.release()
        super.onDestroy()
    }

    private fun goForeground() {
        val open = PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE)
        val talk = PendingIntent.getBroadcast(this, 1, Intent(this, NotificationActionReceiver::class.java).setAction(ACTION_TALK), PendingIntent.FLAG_IMMUTABLE)
        val stop = PendingIntent.getBroadcast(this, 2, Intent(this, NotificationActionReceiver::class.java).setAction(ACTION_STOP), PendingIntent.FLAG_IMMUTABLE)
        val n: Notification = NotificationCompat.Builder(this, DovudApp.CH_SERVICE)
            .setSmallIcon(R.drawable.ic_notif)
            .setContentTitle("Dovud tinglayapti")
            .setContentText("\"Dovud\" deng yoki tugmani bosing")
            .setContentIntent(open)
            .setOngoing(true)
            .addAction(0, "🎤 Gapirish", talk)
            .addAction(0, "⏹ To'xta", stop)
            .build()
        val type = if (Build.VERSION.SDK_INT >= 30) ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE else 0
        ServiceCompat.startForeground(this, 7, n, type)
    }

    // ---------- Rejimlar ----------
    private fun idle() {
        busy = false; watching = false
        mic.setMode(if (SecureStore.flag(SecureStore.WAKE_ENABLED)) MicEngine.Mode.WAKE else MicEngine.Mode.MUTED)
        Bus.status(if (SecureStore.flag(SecureStore.WAKE_ENABLED)) "\"Dovud\" deyishingizni kutyapman" else "Tugmani bosing")
    }

    private fun restoreMicMode() {
        if (waiter != null) return
        mic.setMode(when {
            busy && watching -> MicEngine.Mode.WATCH
            busy -> MicEngine.Mode.MUTED
            SecureStore.flag(SecureStore.WAKE_ENABLED) -> MicEngine.Mode.WAKE
            else -> MicEngine.Mode.MUTED
        })
    }

    fun talk() {
        if (busy) return
        beep()
        Bus.status("Tinglayapman…")
        mic.listenCommand(withPreRoll = false)
    }

    fun stopAll() {
        StopController.requestStop()
        Bus.log("Tizim", "To'xtatildi")
    }

    fun submitText(text: String) {
        if (busy) { Bus.log("Tizim", "Dovud band. Avval to'xtating."); return }
        busy = true
        scope.launch { try { agent.handle(text) } finally { idle() } }
    }

    private fun beep() { runCatching { tone?.startTone(ToneGenerator.TONE_PROP_BEEP, 120) } }

    // ---------- MicEngine.Listener ----------
    override fun onWake() {
        if (busy) return
        beep()
        Bus.status("Tinglayapman…")
        mic.listenCommand(withPreRoll = true)
    }

    override fun onStopWord() {
        if (busy && !speaking) stopAll()
    }

    override fun onNoSpeech() {
        waiter?.let { it.complete(null); return }
        idle()
    }

    override fun onUtterance(pcm: ShortArray) {
        waiter?.let { it.complete(pcm); return }
        if (busy) return
        busy = true
        scope.launch {
            try {
                Bus.status("Tushunyapman…")
                val heard = recognize(pcm)
                if (heard == null) return@launch
                if (heard.owner == false) { Bus.log("Tizim", "Begona ovoz — javob bermadim"); return@launch }
                val cmd = heard.text.replace(Regex("^(?i)\\s*(dovud|dovut|davud|dawud|довуд|давуд)[,.!\\s]*"), "").trim()
                if (cmd.isBlank()) { say("Labbay, Olim aka?"); val h2 = hear(8000) ?: return@launch; if (h2.owner == false) return@launch; agent.handle(h2.text); return@launch }
                if (Regex("^(?i)(to['ʻ‘’]?xta|stop|стоп)").containsMatchIn(cmd)) { say("Xo'p."); return@launch }
                agent.handle(cmd)
            } finally { idle() }
        }
    }

    /** Ovoz → matn (bulut) + ovoz izi (telefonda) parallel. */
    private suspend fun recognize(pcm: ShortArray): Heard? {
        val analysisJob = scope.async(Dispatchers.Default) { SpeakerVerifier.analyze(pcm) }
        val online = Net.online(this)
        val cloudJob = if (online) scope.async(Dispatchers.IO) {
            runCatching { stt.transcribe(pcm) }.getOrElse { e ->
                Bus.log("Tizim", "OpenAI STT ishlamadi: ${e.message?.take(80)}")
                runCatching { sttBackup.transcribe(pcm) }.getOrNull()
            }
        } else null
        val analysis = analysisJob.await()
        val owner = SpeakerVerifier.isOwner(analysis.vector)
        if (owner == false) { cloudJob?.cancel(); return Heard("", false) }
        val text = cloudJob?.await()?.takeIf { it.isNotBlank() } ?: analysis.offlineText
        if (text.isBlank()) return null
        return Heard(text, owner)
    }

    // ---------- Io (Agent uchun) ----------
    override suspend fun say(text: String) {
        if (text.isBlank()) return
        Bus.log("Dovud", text)
        speaking = true
        mic.setMode(MicEngine.Mode.MUTED)
        try {
            val useCloud = Net.online(this) && SecureStore.has(SecureStore.OPENAI_KEY)
            if (useCloud) {
                try { openAiTts.speak(text) } catch (e: Exception) {
                    Bus.log("Tizim", "OpenAI ovozi ishlamadi: ${e.message?.take(60)}"); androidTts.speak(text)
                }
            } else androidTts.speak(text)
        } finally {
            speaking = false
            restoreMicMode()
        }
    }

    override suspend fun hear(timeoutMs: Int): Heard? {
        val pcm = captureRaw(timeoutMs) ?: return null
        return recognize(pcm)
    }

    /** Ovozni yozib oladi (tahlilsiz). Ovoz izini yozishda ham ishlatiladi. */
    suspend fun captureRaw(timeoutMs: Int): ShortArray? {
        val d = CompletableDeferred<ShortArray?>()
        waiter = d
        beep()
        Bus.status("Javobingizni kutyapman…")
        mic.listenCommand(withPreRoll = false, startTimeoutMs = timeoutMs)
        return try { withTimeoutOrNull(timeoutMs + 16000L) { d.await() } } finally { waiter = null; restoreMicMode() }
    }

    override fun watchForStop(on: Boolean) {
        watching = on
        if (!speaking && waiter == null) restoreMicMode()
    }

    private suspend fun checkUpdate() {
        val rel = runCatching { Updater.newer() }.getOrNull() ?: return
        val open = PendingIntent.getActivity(this, 3, Intent(this, MainActivity::class.java).putExtra("update", true), PendingIntent.FLAG_IMMUTABLE)
        val n = NotificationCompat.Builder(this, DovudApp.CH_ALERT).setSmallIcon(R.drawable.ic_notif)
            .setContentTitle("Dovudning yangi versiyasi tayyor (${rel.tag})")
            .setContentText("O'rnatish uchun bosing yoki \"Dovud, yangilanishni o'rnat\" deng")
            .setContentIntent(open).setAutoCancel(true).build()
        getSystemService(NotificationManager::class.java).notify(9, n)
    }

    companion object {
        const val ACTION_TALK = "uz.olim.dovud.TALK"
        const val ACTION_STOP = "uz.olim.dovud.STOP"
        @Volatile var instance: DovudService? = null
            private set

        fun start(ctx: Context): Boolean = try {
            if (ctx.checkSelfPermission(android.Manifest.permission.RECORD_AUDIO) != android.content.pm.PackageManager.PERMISSION_GRANTED)
                throw IllegalStateException("Mikrofon ruxsati yo'q")
            val i = Intent(ctx, DovudService::class.java)
            if (Build.VERSION.SDK_INT >= 26) ctx.startForegroundService(i) else ctx.startService(i)
            true
        } catch (e: Exception) {
            Bus.log("Xato", "Xizmat ishga tushmadi: ${e.message}")
            false
        }
    }
}
