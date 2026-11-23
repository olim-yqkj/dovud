package uz.olim.dovud.brain

/** Agent bilan ovoz qatlami orasidagi ko'prik. */
data class Heard(val text: String, val owner: Boolean?)

interface Io {
    suspend fun say(text: String)
    /** Olim akani tinglaydi. null = hech narsa eshitilmadi. */
    suspend fun hear(timeoutMs: Int = 8000): Heard?
    /** Vazifa bajarilayotganda "to'xta" so'zini kutish rejimi. */
    fun watchForStop(on: Boolean)
}
