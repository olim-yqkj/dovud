package uz.olim.dovud.control

import android.app.Notification
import android.app.RemoteInput
import android.content.Intent
import android.os.Bundle
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification

/** Bildirishnomalarni o'qish va ularga javob berish. */
class DovudNotificationListener : NotificationListenerService() {

    data class Item(val n: Int, val key: String, val app: String, val title: String, val text: String, val time: Long, val canReply: Boolean)

    override fun onListenerConnected() { instance = this }
    override fun onListenerDisconnected() { if (instance === this) instance = null }

    fun list(max: Int = 15): List<Item> {
        val arr = runCatching { activeNotifications }.getOrNull() ?: return emptyList()
        return arr.filter { it.packageName != packageName && !it.isOngoing }
            .sortedByDescending { it.postTime }
            .take(max)
            .mapIndexed { i, s ->
                val ex = s.notification.extras
                val app = runCatching { packageManager.getApplicationLabel(packageManager.getApplicationInfo(s.packageName, 0)).toString() }.getOrDefault(s.packageName)
                Item(i + 1, s.key, app,
                    ex.getCharSequence(Notification.EXTRA_TITLE)?.toString().orEmpty(),
                    (ex.getCharSequence(Notification.EXTRA_BIG_TEXT) ?: ex.getCharSequence(Notification.EXTRA_TEXT))?.toString().orEmpty().take(300),
                    s.postTime, replyAction(s) != null)
            }
    }

    private fun replyAction(s: StatusBarNotification): Notification.Action? =
        s.notification.actions?.firstOrNull { a -> a.remoteInputs?.isNotEmpty() == true }

    fun reply(key: String, text: String): String {
        val s = runCatching { activeNotifications }.getOrNull()?.firstOrNull { it.key == key } ?: return "Bildirishnoma topilmadi (o'chirilgan bo'lishi mumkin)."
        val action = replyAction(s) ?: return "Bu bildirishnomada 'Javob berish' tugmasi yo'q. Ilovani ochib yozish kerak."
        val intent = Intent()
        val bundle = Bundle()
        action.remoteInputs.forEach { ri -> bundle.putCharSequence(ri.resultKey, text) }
        RemoteInput.addResultsToIntent(action.remoteInputs, intent, bundle)
        return try { action.actionIntent.send(this, 0, intent); "Javob yuborildi" } catch (e: Exception) { "Yuborilmadi: ${e.message}" }
    }

    fun open(key: String): String {
        val s = runCatching { activeNotifications }.getOrNull()?.firstOrNull { it.key == key } ?: return "Topilmadi"
        return try { s.notification.contentIntent?.send(); "Ochildi" } catch (e: Exception) { "Ochilmadi: ${e.message}" }
    }

    companion object { @Volatile var instance: DovudNotificationListener? = null }
}
