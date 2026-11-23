package uz.olim.dovud

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Ekranga holat va suhbat yozuvlarini yetkazadigan oddiy "shina". */
object Bus {
    private val _status = MutableStateFlow("Tayyor")
    val status: StateFlow<String> = _status
    private val _log = MutableStateFlow<List<String>>(emptyList())
    val log: StateFlow<List<String>> = _log

    fun status(s: String) { _status.value = s }

    fun log(who: String, text: String) {
        val t = SimpleDateFormat("HH:mm:ss", Locale.US).format(Date())
        val line = "$t  $who: $text"
        android.util.Log.i("Dovud", line)
        _log.value = (_log.value + line).takeLast(200)
    }
}
