package uz.olim.dovud.control

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Rect
import android.os.Build
import android.os.Bundle
import android.util.Base64
import android.view.Display
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.view.accessibility.AccessibilityWindowInfo
import kotlinx.coroutines.delay
import kotlinx.coroutines.suspendCancellableCoroutine
import uz.olim.dovud.service.DovudService
import java.io.ByteArrayOutputStream
import kotlin.coroutines.resume

/**
 * Telefonni boshqaruvchi "qo'llar va ko'zlar": ekranni o'qish, bosish, yozish, varaqlash, skrinshot.
 * Koordinatalar hamma joyda 0..1000 shkalada (ekran kengligi va balandligiga nisbatan).
 */
class DovudAccessibilityService : AccessibilityService() {

    data class Element(val id: Int, val node: AccessibilityNodeInfo, val bounds: Rect, val label: String)

    @Volatile var lastPackage: String = ""
        private set
    private val elements = HashMap<Int, Element>()

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
        // Telefon qayta yonganda ham Dovud ishga tushsin
        runCatching { DovudService.start(this) }
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        event?.packageName?.let { if (event.eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) lastPackage = it.toString() }
    }

    override fun onInterrupt() {}

    override fun onDestroy() {
        if (instance === this) instance = null
        super.onDestroy()
    }

    /** Haqiqiy ekran o'lchami (holat va navigatsiya panellari bilan) — imo-ishoralar va skrinshot bir xil koordinatada. */
    private fun realSize(): android.graphics.Point {
        val pt = android.graphics.Point()
        @Suppress("DEPRECATION")
        getSystemService(android.view.WindowManager::class.java).defaultDisplay.getRealSize(pt)
        return pt
    }
    val screenW get() = realSize().x
    val screenH get() = realSize().y

    private fun nx(x: Int) = (x * 1000L / maxOf(1, screenW)).toInt()
    private fun ny(y: Int) = (y * 1000L / maxOf(1, screenH)).toInt()
    fun px(x: Int) = (x.coerceIn(0, 1000) * screenW / 1000f)
    fun py(y: Int) = (y.coerceIn(0, 1000) * screenH / 1000f)

    /** Ekrandagi elementlar ro'yxati — arzon va tez (skrinshotsiz). */
    fun readScreen(maxItems: Int = 160): String {
        elements.clear()
        val roots = ArrayList<AccessibilityNodeInfo>()
        runCatching {
            for (w in windows) {
                if (w.type == AccessibilityWindowInfo.TYPE_ACCESSIBILITY_OVERLAY) continue
                w.root?.let { roots.add(it) }
            }
        }
        if (roots.isEmpty()) rootInActiveWindow?.let { roots.add(it) }
        if (roots.isEmpty()) return "Ekran o'qilmadi (ilova ekranni yashirgan bo'lishi mumkin). screenshot() ni sinang."
        val sb = StringBuilder()
        val pkg = rootInActiveWindow?.packageName?.toString() ?: lastPackage
        sb.append("Ilova: $pkg\n")
        var counter = 1
        fun walk(n: AccessibilityNodeInfo, depth: Int) {
            if (counter > maxItems || depth > 40) return
            if (!n.isVisibleToUser) return
            val text = n.text?.toString()?.trim().orEmpty()
            val desc = n.contentDescription?.toString()?.trim().orEmpty()
            val hint = if (Build.VERSION.SDK_INT >= 26) n.hintText?.toString().orEmpty() else ""
            val useful = text.isNotEmpty() || desc.isNotEmpty() || n.isClickable || n.isEditable || n.isScrollable || n.isCheckable || n.isLongClickable
            if (useful) {
                val r = Rect(); n.getBoundsInScreen(r)
                if (r.width() > 2 && r.height() > 2) {
                    val id = counter++
                    val cls = n.className?.toString()?.substringAfterLast('.') ?: "View"
                    val flags = buildList {
                        if (n.isClickable) add("bosiladi")
                        if (n.isLongClickable) add("uzoq-bosiladi")
                        if (n.isEditable) add("yoziladi")
                        if (n.isScrollable) add("varaqlanadi")
                        if (n.isCheckable) add(if (n.isChecked) "yoqilgan" else "o'chirilgan")
                        if (n.isPassword) add("PAROL")
                        if (n.isFocused) add("fokusda")
                        if (!n.isEnabled) add("faol-emas")
                    }
                    val label = listOf(text, desc, hint).filter { it.isNotEmpty() }.distinct().joinToString(" | ").take(80)
                    elements[id] = Element(id, n, r, label)
                    sb.append("[$id] $cls \"$label\" (${nx(r.centerX())},${ny(r.centerY())}) ${flags.joinToString(",")}\n")
                }
            }
            for (i in 0 until n.childCount) n.getChild(i)?.let { walk(it, depth + 1) }
        }
        for (r in roots) walk(r, 0)
        if (counter == 1) sb.append("(Ekranda o'qiladigan element yo'q — o'yin yoki rasm bo'lishi mumkin. screenshot() ni ishlating.)\n")
        return sb.toString()
    }

    fun element(id: Int): Element? = elements[id]

    suspend fun tapElement(id: Int): String {
        val e = elements[id] ?: return "Xato: [$id] element topilmadi. Avval read_screen() qiling."
        var n: AccessibilityNodeInfo? = e.node
        var depth = 0
        while (n != null && depth < 6) {
            if (n.isClickable && n.performAction(AccessibilityNodeInfo.ACTION_CLICK)) return "Bosildi: \"${e.label}\""
            n = n.parent; depth++
        }
        val ok = gestureTap(e.bounds.exactCenterX(), e.bounds.exactCenterY(), 60)
        return if (ok) "Bosildi (barmoq bilan): \"${e.label}\"" else "Bosib bo'lmadi"
    }

    suspend fun longPressElement(id: Int, ms: Long): String {
        val e = elements[id] ?: return "Xato: [$id] element topilmadi."
        if (e.node.isLongClickable && e.node.performAction(AccessibilityNodeInfo.ACTION_LONG_CLICK)) return "Uzoq bosildi"
        return if (gestureTap(e.bounds.exactCenterX(), e.bounds.exactCenterY(), ms)) "Uzoq bosildi" else "Bo'lmadi"
    }

    suspend fun tapXY(x: Int, y: Int, holdMs: Long = 60): Boolean = gestureTap(px(x), py(y), holdMs)

    suspend fun gestureTap(x: Float, y: Float, holdMs: Long): Boolean {
        val p = Path().apply { moveTo(x, y) }
        return dispatch(GestureDescription.Builder().addStroke(GestureDescription.StrokeDescription(p, 0, maxOf(1L, holdMs))).build())
    }

    suspend fun swipe(x1: Int, y1: Int, x2: Int, y2: Int, ms: Long): Boolean {
        val p = Path().apply { moveTo(px(x1), py(y1)); lineTo(px(x2), py(y2)) }
        return dispatch(GestureDescription.Builder().addStroke(GestureDescription.StrokeDescription(p, 0, ms.coerceIn(10, 5000))).build())
    }

    /** Bir nechta tezkor bosish ketma-ket (o'yinlar uchun). */
    suspend fun multiTap(points: List<Pair<Int, Int>>, gapMs: Long): Int {
        var ok = 0
        for ((x, y) in points) { if (tapXY(x, y, 40)) ok++; delay(gapMs) }
        return ok
    }

    private suspend fun dispatch(g: GestureDescription): Boolean = suspendCancellableCoroutine { cont ->
        val sent = dispatchGesture(g, object : GestureResultCallback() {
            override fun onCompleted(gestureDescription: GestureDescription?) { if (cont.isActive) cont.resume(true) }
            override fun onCancelled(gestureDescription: GestureDescription?) { if (cont.isActive) cont.resume(false) }
        }, null)
        if (!sent && cont.isActive) cont.resume(false)
    }

    fun typeText(id: Int?, text: String, append: Boolean): String {
        val target = id?.let { elements[it]?.node }
            ?: rootInActiveWindow?.findFocus(AccessibilityNodeInfo.FOCUS_INPUT)
            ?: elements.values.firstOrNull { it.node.isEditable }?.node
            ?: return "Xato: yozish maydoni topilmadi. Avval maydonni bosing."
        target.performAction(AccessibilityNodeInfo.ACTION_FOCUS)
        val finalText = if (append) (target.text?.toString().orEmpty() + text) else text
        val args = Bundle().apply { putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, finalText) }
        return if (target.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)) "Yozildi" else "Yozib bo'lmadi"
    }

    fun pressEnter(): Boolean {
        if (Build.VERSION.SDK_INT < 30) return false
        val f = rootInActiveWindow?.findFocus(AccessibilityNodeInfo.FOCUS_INPUT) ?: return false
        return f.performAction(AccessibilityNodeInfo.AccessibilityAction.ACTION_IME_ENTER.id)
    }

    suspend fun scroll(direction: String, id: Int?): String {
        val node = id?.let { elements[it]?.node } ?: elements.values.firstOrNull { it.node.isScrollable }?.node
        val forward = direction == "down" || direction == "right"
        if (node != null) {
            val act = if (forward) AccessibilityNodeInfo.ACTION_SCROLL_FORWARD else AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD
            if (node.performAction(act)) return "Varaqlandi ($direction)"
        }
        val ok = when (direction) {
            "down" -> swipe(500, 750, 500, 250, 350)
            "up" -> swipe(500, 250, 500, 750, 350)
            "right" -> swipe(850, 500, 150, 500, 300)
            else -> swipe(150, 500, 850, 500, 300)
        }
        return if (ok) "Varaqlandi (barmoq bilan, $direction)" else "Varaqlab bo'lmadi"
    }

    fun global(action: String): Boolean = performGlobalAction(when (action) {
        "back" -> GLOBAL_ACTION_BACK
        "home" -> GLOBAL_ACTION_HOME
        "recents" -> GLOBAL_ACTION_RECENTS
        "notifications" -> GLOBAL_ACTION_NOTIFICATIONS
        "quick_settings" -> GLOBAL_ACTION_QUICK_SETTINGS
        "lock" -> if (Build.VERSION.SDK_INT >= 28) GLOBAL_ACTION_LOCK_SCREEN else GLOBAL_ACTION_BACK
        "screenshot_save" -> if (Build.VERSION.SDK_INT >= 28) GLOBAL_ACTION_TAKE_SCREENSHOT else GLOBAL_ACTION_BACK
        else -> GLOBAL_ACTION_BACK
    })

    data class Shot(val jpegB64: String?, val error: String?)

    private var lastShotAt = 0L

    /** Skrinshot (Android 11+). 0..1000 to'r chiziqlari bilan — AI koordinatani aniqroq topishi uchun. */
    suspend fun screenshot(maxWidth: Int = 720, grid: Boolean = true, quality: Int = 60): Shot {
        if (Build.VERSION.SDK_INT < 30) return Shot(null, "Skrinshot Android 11 dan boshlab ishlaydi.")
        val wait = 350 - (System.currentTimeMillis() - lastShotAt)
        if (wait > 0) delay(wait)
        lastShotAt = System.currentTimeMillis()
        val bmp: Bitmap = suspendCancellableCoroutine<Any> { cont ->
            takeScreenshot(Display.DEFAULT_DISPLAY, mainExecutor, object : TakeScreenshotCallback {
                override fun onSuccess(result: ScreenshotResult) {
                    val hb = result.hardwareBuffer
                    val b = Bitmap.wrapHardwareBuffer(hb, result.colorSpace)?.copy(Bitmap.Config.ARGB_8888, true)
                    hb.close()
                    if (cont.isActive) cont.resume(b ?: "Bitmap yaratilmadi")
                }
                override fun onFailure(errorCode: Int) {
                    val why = when (errorCode) {
                        ERROR_TAKE_SCREENSHOT_SECURE_WINDOW -> "Bu ilova ekranini ko'rishni BLOKLAGAN (himoyalangan oyna). read_screen() orqali ishlang."
                        ERROR_TAKE_SCREENSHOT_INTERVAL_TIME_SHORT -> "Skrinshotlar orasida vaqt juda qisqa."
                        else -> "Skrinshot olinmadi (kod $errorCode)."
                    }
                    if (cont.isActive) cont.resume(why)
                }
            })
        }.let { r -> if (r is Bitmap) r else return Shot(null, r.toString()) }

        val scale = maxWidth.toFloat() / bmp.width
        val out = if (scale < 1f) Bitmap.createScaledBitmap(bmp, maxWidth, (bmp.height * scale).toInt(), true) else bmp
        if (grid) {
            val c = Canvas(out)
            val line = Paint().apply { color = Color.argb(70, 255, 0, 255); strokeWidth = 1f }
            val txt = Paint().apply { color = Color.argb(200, 255, 0, 255); textSize = out.width / 45f; isAntiAlias = true }
            for (i in 1..9) {
                val x = out.width * i / 10f; val y = out.height * i / 10f
                c.drawLine(x, 0f, x, out.height.toFloat(), line)
                c.drawLine(0f, y, out.width.toFloat(), y, line)
                c.drawText("${i * 100}", x + 2, txt.textSize, txt)
                c.drawText("${i * 100}", 2f, y - 2, txt)
            }
        }
        val bos = ByteArrayOutputStream()
        out.compress(Bitmap.CompressFormat.JPEG, quality, bos)
        return Shot(Base64.encodeToString(bos.toByteArray(), Base64.NO_WRAP), null)
    }

    fun currentPackage(): String = rootInActiveWindow?.packageName?.toString() ?: lastPackage

    /** Berilgan ilova oynasidagi barcha tugunlar (Telegram ko'prigi uchun). */
    fun nodesOf(pkgPrefix: String): List<AccessibilityNodeInfo> {
        val out = ArrayList<AccessibilityNodeInfo>()
        val roots = ArrayList<AccessibilityNodeInfo>()
        runCatching { for (w in windows) w.root?.let { roots.add(it) } }
        if (roots.isEmpty()) rootInActiveWindow?.let { roots.add(it) }
        fun walk(n: AccessibilityNodeInfo, d: Int) {
            if (d > 50) return
            out.add(n)
            for (i in 0 until n.childCount) n.getChild(i)?.let { walk(it, d + 1) }
        }
        for (r in roots) if (r.packageName?.toString()?.startsWith(pkgPrefix) == true) walk(r, 0)
        return out
    }

    companion object {
        @Volatile var instance: DovudAccessibilityService? = null
            private set
    }
}
