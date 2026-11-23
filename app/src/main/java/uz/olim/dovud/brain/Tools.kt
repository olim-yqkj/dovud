package uz.olim.dovud.brain

import uz.olim.dovud.brain.Params.bool
import uz.olim.dovud.brain.Params.enumOf
import uz.olim.dovud.brain.Params.int
import uz.olim.dovud.brain.Params.obj
import uz.olim.dovud.brain.Params.str

/** AI'ga beriladigan "asboblar" ro'yxati. Har bir asbob — telefonda bitta harakat. */
object Tools {
    private val intent = "intent" to str("Bu harakat nima qilishini o'zbekcha bitta qisqa gapda yozing (tasdiq so'rashda aytiladi).")
    private val risky = "risky" to bool("true — agar bu harakat pul, to'lov, xarid, o'chirish, xabar yuborish, qo'ng'iroq, sozlama yoki parolni o'zgartirishga olib kelsa.")

    val all: List<ToolSpec> = listOf(
        ToolSpec("read_screen", "Hozirgi ekrandagi elementlar ro'yxatini oladi: [raqam] tur \"matn\" (x,y) belgilar. Koordinatalar 0..1000. Arzon — har doim birinchi shuni ishlating.", obj()),
        ToolSpec("screenshot", "Ekranning rasmini oladi (0..1000 to'r chiziqlari bilan). read_screen yetmasa yoki o'yin/rasm bo'lsa ishlating.", obj()),
        ToolSpec("tap", "Element raqami bo'yicha bosadi (read_screen'dagi [raqam]).", obj("id" to int("Element raqami"), intent, risky, required = listOf("id"))),
        ToolSpec("tap_xy", "Ekrandagi nuqtani bosadi (0..1000 koordinata).", obj("x" to int("0..1000"), "y" to int("0..1000"), intent, risky, required = listOf("x", "y"))),
        ToolSpec("long_press", "Uzoq bosib turadi: element raqami yoki x,y bo'yicha.", obj("id" to int("Element raqami (ixtiyoriy)"), "x" to int("0..1000"), "y" to int("0..1000"), "ms" to int("Necha millisekund (standart 800)"), intent, risky)),
        ToolSpec("swipe", "Barmoq bilan suradi (0..1000 koordinatalar).", obj("x1" to int(""), "y1" to int(""), "x2" to int(""), "y2" to int(""), "ms" to int("Davomiyligi, standart 300"), required = listOf("x1", "y1", "x2", "y2"))),
        ToolSpec("tap_sequence", "O'yinlar uchun: bir nechta nuqtani tez ketma-ket bosadi.", obj("points" to org.json.JSONObject().put("type", "array").put("description", "[[x,y],[x,y],...] 0..1000")
            .put("items", org.json.JSONObject().put("type", "array").put("items", org.json.JSONObject().put("type", "integer"))), "gap_ms" to int("Bosishlar orasidagi vaqt, standart 120"), required = listOf("points"))),
        ToolSpec("type_text", "Matn yozadi. id berilmasa, fokusdagi maydonga yozadi.", obj("id" to int("Yozish maydoni raqami (ixtiyoriy)"), "text" to str("Matn"), "append" to bool("Eski matnga qo'shish"), "submit" to bool("Yozgandan keyin Enter bosish"), intent, risky, required = listOf("text"))),
        ToolSpec("scroll", "Varaqlaydi.", obj("direction" to enumOf("Yo'nalish", "down", "up", "left", "right"), "id" to int("Varaqlanadigan element raqami (ixtiyoriy)"), required = listOf("direction"))),
        ToolSpec("press", "Tizim tugmasi: back (orqaga), home (bosh ekran), recents, notifications (bildirishnoma paneli), quick_settings, lock.", obj("key" to enumOf("", "back", "home", "recents", "notifications", "quick_settings", "lock"), required = listOf("key"))),
        ToolSpec("open_app", "Ilovani nomi bo'yicha ochadi (masalan: Telegram, Payme, Kamera, Sozlamalar).", obj("name" to str("Ilova nomi"), required = listOf("name"))),
        ToolSpec("open_url", "Havolani yoki maxsus manzilni ochadi (https://..., tg://resolve?domain=..., geo:...).", obj("url" to str(""), required = listOf("url"))),
        ToolSpec("list_apps", "O'rnatilgan ilovalar ro'yxati.", obj()),
        ToolSpec("wait", "Kutadi (ekran yuklanishi uchun).", obj("ms" to int("100..5000"), required = listOf("ms"))),
        ToolSpec("call", "Kontakt yoki raqamga qo'ng'iroq qiladi. (Tasdiq avtomatik so'raladi.)", obj("target" to str("Ism yoki raqam"), intent, required = listOf("target"))),
        ToolSpec("send_sms", "SMS yuboradi. (Tasdiq avtomatik so'raladi.)", obj("target" to str("Ism yoki raqam"), "text" to str("Matn"), intent, required = listOf("target", "text"))),
        ToolSpec("set_alarm", "Budilnik qo'yadi.", obj("hour" to int("0..23"), "minute" to int("0..59"), "label" to str("Izoh"), required = listOf("hour", "minute"))),
        ToolSpec("set_timer", "Taymer qo'yadi.", obj("seconds" to int(""), required = listOf("seconds"))),
        ToolSpec("set_volume", "Ovoz balandligi (foiz).", obj("percent" to int("0..100"), "stream" to enumOf("", "media", "ring", "alarm", "call"), required = listOf("percent"))),
        ToolSpec("set_brightness", "Ekran yorug'ligi (foiz).", obj("percent" to int("1..100"), required = listOf("percent"))),
        ToolSpec("flashlight", "Chiroqni yoqadi/o'chiradi.", obj("on" to bool(""), required = listOf("on"))),
        ToolSpec("open_settings_panel", "Sozlama oynasini ochadi: wifi, bluetooth, internet, volume, display, location, battery, main. So'ng read_screen bilan kalitni bosing.", obj("what" to enumOf("", "wifi", "bluetooth", "internet", "volume", "display", "location", "battery", "main"), required = listOf("what"))),
        ToolSpec("open_camera", "Kamerani ochadi.", obj("video" to bool("Video rejimi"))),
        ToolSpec("open_gallery", "Galereyani (rasmlarni) ochadi.", obj()),
        ToolSpec("read_notifications", "Oxirgi bildirishnomalarni o'qiydi.", obj()),
        ToolSpec("reply_notification", "Bildirishnomaga javob yozadi (raqami read_notifications'dan). (Tasdiq avtomatik so'raladi.)", obj("n" to int("Bildirishnoma raqami"), "text" to str("Javob matni"), intent, required = listOf("n", "text"))),
        ToolSpec("open_notification", "Bildirishnomani ochadi.", obj("n" to int(""), required = listOf("n"))),
        ToolSpec("ask_sulaymon", "Kompyuterdagi Sulaymon yordamchisiga buyruq yuboradi va javobini qaytaradi.", obj("text" to str("Sulaymonga buyruq"), intent, required = listOf("text"))),
        ToolSpec("ask_user", "Olim akadan aniqlashtiruvchi savol so'raydi va javobini qaytaradi.", obj("question" to str(""), required = listOf("question"))),
        ToolSpec("ask_confirmation", "Muhim amal ketma-ketligini boshlashdan OLDIN tasdiq so'raydi. Masalan: 'Payme orqali Akmalga 50 000 so'm o'tkazaman'.", obj("summary" to str("Nima qilinishi — bitta gap"), required = listOf("summary"))),
        ToolSpec("say", "Vazifa davomida Olim akaga qisqa xabar aytadi (masalan, nima qilayotganingiz).", obj("text" to str(""), required = listOf("text"))),
        ToolSpec("remember", "Olim aka tuzatgan yoki o'rgatgan narsani eslab qolish (ruxsat so'raladi).", obj("text" to str("Eslab qolinadigan qoida yoki fakt"), required = listOf("text"))),
        ToolSpec("game_mode", "O'yin rejimini yoqadi/o'chiradi (tez harakat, har qadamdan keyin skrinshot).", obj("on" to bool(""), required = listOf("on"))),
        ToolSpec("check_update", "Dovudning yangi versiyasi bormi — tekshiradi.", obj()),
        ToolSpec("install_update", "Dovudning yangi versiyasini yuklab o'rnatish oynasini ochadi. (Tasdiq avtomatik so'raladi.)", obj(intent)),
        ToolSpec("request_improvement", "Dovud biror ishni qila olmasa: o'zini yaxshilash so'rovini GitHub'ga yuboradi (yangi versiya yig'iladi). (Tasdiq avtomatik so'raladi.)", obj("what" to str("Nima yetishmayapti va nima qo'shilishi kerak — batafsil"), intent, required = listOf("what"))),
        ToolSpec("request_rollback", "Oxirgi yangilanishni bekor qilib, oldingi versiyaga qaytarish so'rovi. (Tasdiq avtomatik so'raladi.)", obj(intent)),
        ToolSpec("finish", "Vazifa tugadi. Olim akaga aytiladigan yakuniy qisqa javob.", obj("answer" to str("Yakuniy javob (1–2 gap)"), "success" to bool("Vazifa muvaffaqiyatli bajarildimi"), required = listOf("answer"))),
    )
}
