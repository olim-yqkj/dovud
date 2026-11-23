package uz.olim.dovud.voice

import android.annotation.SuppressLint
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import org.json.JSONArray
import org.json.JSONObject
import org.vosk.Recognizer
import uz.olim.dovud.Bus
import java.util.ArrayDeque

/**
 * Mikrofon bilan ishlaydigan yagona dvigatel. Bitta mikrofon oqimi bor, rejimlar:
 *  WAKE    — "Dovud" so'zini telefonning o'zida kutadi (internet kerak emas)
 *  WATCH   — vazifa bajarilayotganda "to'xta" so'zini kutadi
 *  COMMAND — buyruqni yozib oladi (gap tugaguncha), so'ng onUtterance() chaqiradi
 *  MUTED   — Dovud gapirayotganda o'z ovozini eshitmaslik uchun
 */
class MicEngine(private val listener: Listener) {

    interface Listener {
        fun onWake()
        fun onStopWord()
        fun onUtterance(pcm: ShortArray)
        fun onNoSpeech()
    }

    enum class Mode { WAKE, WATCH, COMMAND, MUTED }

    @Volatile var mode: Mode = Mode.MUTED
        private set
    @Volatile private var running = false
    private var thread: Thread? = null
    private var recognizer: Recognizer? = null
    private var grammarMode = false

    // Buyruq yozish holati
    private val preRoll = ArrayDeque<ShortArray>()
    private val cmd = ArrayList<ShortArray>()
    private var speechStarted = false
    private var speechFrames = 0
    private var silenceFrames = 0
    private var cmdFrames = 0
    private var noise = 300.0
    private var startTimeoutFrames = 0
    private var maxFrames = 0

    private val wakeRegex = Regex("""\b(d[ao]v[uoi][dt]\w*|д[ао]вуд\w*|dawud|dovud)\b""", RegexOption.IGNORE_CASE)
    private val stopRegex = Regex("""\b(to['ʻ‘’`]?xta\w*|stop|стоп|bas|yetar|хватит|остановись)\b""", RegexOption.IGNORE_CASE)

    fun start() {
        if (running) return
        running = true
        setupRecognizer()
        thread = Thread({ loop() }, "dovud-mic").also { it.start() }
    }

    fun stop() {
        running = false
        thread?.join(1500)
        thread = null
        recognizer?.close(); recognizer = null
    }

    private fun setupRecognizer() {
        val m = VoskModels.model ?: return
        val words = VoskModels.wakeWords
        recognizer = if (words.any { it.startsWith("d") }) {
            grammarMode = true
            val arr = JSONArray(); words.forEach { arr.put(it) }; arr.put("[unk]")
            Recognizer(m, Pcm.RATE.toFloat(), arr.toString())
        } else {
            // Lug'atda "dovud" topilmadi — to'liq tanish rejimi (biroz ko'proq batareya)
            grammarMode = false
            Recognizer(m, Pcm.RATE.toFloat())
        }
        Bus.log("Tizim", if (grammarMode) "Uyg'otish so'zi: grammatika rejimi (${words.joinToString()})" else "Uyg'otish so'zi: to'liq tanish rejimi")
    }

    fun setMode(m: Mode) {
        if (m == mode) return
        if (m == Mode.WAKE || m == Mode.WATCH) recognizer?.reset()
        mode = m
    }

    /** Buyruqni yozishni boshlash. withPreRoll=true bo'lsa "Dovud"dan keyingi so'zlar ham qo'shiladi. */
    @Synchronized
    fun listenCommand(withPreRoll: Boolean, startTimeoutMs: Int = 6000, maxMs: Int = 15000) {
        cmd.clear()
        speechStarted = false; speechFrames = 0; silenceFrames = 0; cmdFrames = 0
        if (withPreRoll) {
            for (f in preRoll) {
                cmd.add(f)
                if (Pcm.rms(f) > threshold()) { speechFrames++; if (speechFrames >= 3) speechStarted = true }
            }
        }
        startTimeoutFrames = startTimeoutMs / FRAME_MS
        maxFrames = maxMs / FRAME_MS
        mode = Mode.COMMAND
    }

    private fun threshold() = maxOf(noise * 2.5, 450.0)

    @SuppressLint("MissingPermission")
    private fun loop() {
        val minBuf = AudioRecord.getMinBufferSize(Pcm.RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
        val rec = try {
            AudioRecord(MediaRecorder.AudioSource.VOICE_RECOGNITION, Pcm.RATE, AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT, maxOf(minBuf, FRAME * 8))
        } catch (e: Exception) {
            Bus.log("Xato", "Mikrofon ochilmadi: ${e.message}"); running = false; return
        }
        if (rec.state != AudioRecord.STATE_INITIALIZED) {
            Bus.log("Xato", "Mikrofon band yoki ruxsat yo'q"); running = false; rec.release(); return
        }
        rec.startRecording()
        try {
            while (running) {
                val buf = ShortArray(FRAME)
                var read = 0
                while (read < FRAME && running) {
                    val n = rec.read(buf, read, FRAME - read)
                    if (n <= 0) break
                    read += n
                }
                if (read < FRAME) continue
                process(buf)
            }
        } finally {
            try { rec.stop() } catch (_: Exception) {}
            rec.release()
        }
    }

    private fun process(frame: ShortArray) {
        val level = Pcm.rms(frame)
        preRoll.addLast(frame)
        while (preRoll.size > PREROLL_FRAMES) preRoll.removeFirst()

        when (mode) {
            Mode.MUTED -> {}
            Mode.WAKE, Mode.WATCH -> {
                if (!speechStarted) noise = noise * 0.98 + level * 0.02
                val r = recognizer ?: return
                val final = r.acceptWaveForm(frame, frame.size)
                val text = if (final) JSONObject(r.result).optString("text") else JSONObject(r.partialResult).optString("partial")
                if (text.isBlank()) return
                if (mode == Mode.WAKE && wakeRegex.containsMatchIn(text)) {
                    r.reset()
                    mode = Mode.MUTED
                    listener.onWake()
                } else if (mode == Mode.WATCH && (stopRegex.containsMatchIn(text) || wakeRegex.containsMatchIn(text))) {
                    r.reset()
                    listener.onStopWord()
                } else if (final) {
                    r.reset()
                }
            }
            Mode.COMMAND -> {
                synchronized(this) {
                    cmd.add(frame); cmdFrames++
                    val loud = level > threshold()
                    if (!speechStarted) {
                        if (loud) { speechFrames++; if (speechFrames >= 3) speechStarted = true } else speechFrames = 0
                        if (!speechStarted && cmdFrames > startTimeoutFrames) {
                            mode = Mode.MUTED; listener.onNoSpeech(); return
                        }
                    } else {
                        if (loud) silenceFrames = 0 else silenceFrames++
                        if (silenceFrames >= END_SILENCE_FRAMES || cmdFrames >= maxFrames) {
                            mode = Mode.MUTED
                            val total = cmd.sumOf { it.size }
                            val out = ShortArray(total); var p = 0
                            for (f in cmd) { System.arraycopy(f, 0, out, p, f.size); p += f.size }
                            cmd.clear()
                            listener.onUtterance(out)
                        }
                    }
                }
            }
        }
    }

    companion object {
        const val FRAME_MS = 30
        const val FRAME = Pcm.RATE * FRAME_MS / 1000 // 480
        const val PREROLL_FRAMES = 50                 // 1.5 soniya
        const val END_SILENCE_FRAMES = 27             // ~0.8 soniya jimlik = gap tugadi
    }
}
