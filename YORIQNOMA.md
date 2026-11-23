# DOVUD — o'rnatish va sozlash yo'riqnomasi

Olim aka, bu yo'riqnoma qadamma-qadam yozilgan. Har bir qadamni tartib bilan bajaring.

---

## 1. APK'ni yuklab olish va o'rnatish (telefonda)

1. Telefonda **Chrome**'ni oching va manzil qatoriga yozing:
   `https://github.com/olim-yqkj/dovud/releases/latest/download/dovud.apk`
2. Yuklab olish boshlanadi. Agar "Fayl zararli bo'lishi mumkin" desa — **Baribir yuklab olish**.
3. Pastda chiqqan **Ochish** ni bosing (yoki: Fayllar → Yuklanmalar → `dovud.apk`).
4. "Noma'lum manbalardan o'rnatish" so'ralsa: **Sozlamalar** → **Ushbu manbadan ruxsat berish** kalitini yoqing → orqaga qayting.
5. **O'rnatish** ni bosing.
6. Xiaomi "Xavfsizlik tekshiruvi" desa — **Baribir o'rnatish** (kod ochiq, GitHub'da turibdi).
7. **Ochish** ni bosing.

## 2. Ruxsatlarni yoqish

Dovud birinchi ochilganda "Ruxsatlar" oynasini o'zi ochadi. Har qatordagi **Yoqish** tugmasini bosing, ko'rsatmani bajaring va orqaga qayting. ✅ — tayyor.

**Eng muhim (Redmi 15C uchun):**
- **4-qadam — Cheklangan sozlamalar.** Android internetdan o'rnatilgan ilovalarga Accessibility'ni bloklaydi. Ilova ma'lumotlari oynasida o'ng yuqoridagi **⋮** → **Cheklangan sozlamalarga ruxsat berish**. Agar ⋮ ko'rinmasa: avval 5-qadamni bosib, Dovud'ni yoqishga urinib ko'ring (u "cheklangan" deydi), keyin 4-qadamga qayting — ⋮ paydo bo'ladi.
- **5-qadam — Accessibility.** Sozlamalar → Maxsus imkoniyatlar → **Yuklab olingan ilovalar** → **Dovud** → yoqing. Xiaomi 10 soniya kutishni so'rashi mumkin.
- **11–13-qadamlar — Xiaomi.** Avtozapusk, "Fonda qalqib chiquvchi oynalar", Batareya → "Cheklovlar yo'q". Bularsiz Xiaomi Dovud'ni fonda o'chirib qo'yadi.

## 3. API kalitlarini kiritish

### OpenAI kaliti (asosiy)
1. Kompyuterda `https://platform.openai.com/api-keys` ga kiring (Google akkaunt bilan).
2. **Billing**'da hisobni to'ldiring ($10 yetarli, **Auto recharge — o'chiq**).
3. **Create new secret key** → nom: `dovud` → **Create secret key**.
4. `sk-...` bilan boshlanadigan kalit chiqadi. **Copy** ni bosing. (U faqat bir marta ko'rsatiladi!)
5. Kalitni telefonga o'tkazing (masalan, Telegram'da "Saqlangan xabarlar"ga yuboring, keyin u xabarni o'chiring).
6. Dovud → **Sozlamalar** → "OpenAI API kaliti" maydoniga joylang → **Saqlash**.

### Gemini kaliti (zaxira, bepul)
1. `https://aistudio.google.com/apikey` ga kiring.
2. **Create API key** → kalitni nusxalang.
3. Dovud → Sozlamalar → "Gemini API kaliti" → **Saqlash**.

> Kalitlarni hech kimga, hatto chatda ham yubormang. Telefonda ular shifrlangan holda saqlanadi.

## 4. Ovoz izini yozish
Dovud → **Ovoz izi** → **Boshlash**. Tinch joyda, har "bip"dan keyin ekrandagi gapni odatdagi ovozingizda o'qing (5 ta gap).
Ovozingizni tanimasa: Sozlamalar → "Ovoz izi chegarasi"ni kamaytiring (masalan, 0.45). Begonaga javob bersa — oshiring (0.6).

## 5. Dovud qanday ishlatiladi
- **"Dovud"** deng → "bip" → buyruqni ayting. Yoki shunday: "Dovud, Telegramni och" — bir nafasda.
- Yoki ekrandagi katta **🎤 Gapirish** tugmasi / bildirishnoma panelidagi **🎤 Gapirish**.
- **"Dovud, to'xta"** yoki **⏹ To'xta** — istalgan ishni darhol to'xtatadi.
- Muhim amalda Dovud: *"… Tasdiqlaysizmi?"* deydi → **"Tasdiqlayman"** deng (yoki "Yo'q").

---

## 6. Sulaymon ko'prigi

### 6.0. ODDIY YO'L — Telegram orqali (tavsiya etiladi)
1. Telegram'da Sulaymon botining nomini toping (chat sarlavhasini bosing → `@...` nom).
2. Dovud → **Sozlamalar** → "Sulaymon botining Telegram nomi" → masalan `@yqkj_sulaymon_bot` → **Saqlash**.
3. Sinash: **"Dovud, Sulaymonga ayt: salom"**. Dovud Telegram'ni ochadi, yozadi, javobni kutadi va o'qib beradi.
Shartlar: kompyuter yoqiq va Sulaymon ishlayapti; telefon ekrani qulfdan ochiq.

### 6.1–6.4. MURAKKAB YO'L — server orqali (ixtiyoriy)

Sxema: **Telefon → (HTTPS + maxfiy token) → Server → Kompyuterdagi Sulaymon → javob → Telefon**.

### 6.1. Kerakli narsalar
- Linux server (Ubuntu) va unga **SSH** orqali kirish (provayder bergan IP, login, parol).
- **Domen nomi** (HTTPS uchun). Domeningiz bo'lmasa — bepul `duckdns.org` subdomeni olsa bo'ladi.
- **Maxfiy token** — o'zingiz o'ylab topgan, kamida 32 belgili tasodifiy matn (harf va raqamlar).

### 6.2. Serverga o'rnatish
Kompyuterda **Пуск** → `cmd` yozing → Enter. Ochilgan qora oynaga:
```
ssh root@SERVER_IP
```
Parolni kiriting (yozilayotgani ko'rinmaydi — bu normal). Keyin ketma-ket:
```
apt update && apt install -y python3 caddy curl
mkdir -p /opt/dovud && cd /opt/dovud
curl -fsSL -o dovud_relay.py https://raw.githubusercontent.com/olim-yqkj/dovud/main/server/dovud_relay.py
```
Xizmat faylini yarating (TOKEN o'rniga o'z tokeningizni yozing):
```
cat > /etc/systemd/system/dovud-relay.service <<'EOF'
[Unit]
Description=Dovud relay
After=network.target
[Service]
Environment=DOVUD_TOKEN=TOKEN
ExecStart=/usr/bin/python3 /opt/dovud/dovud_relay.py
Restart=always
[Install]
WantedBy=multi-user.target
EOF
systemctl daemon-reload && systemctl enable --now dovud-relay
```
HTTPS (DOMEN o'rniga domeningizni yozing):
```
echo 'DOMEN {
  reverse_proxy 127.0.0.1:8787
}' > /etc/caddy/Caddyfile
systemctl restart caddy
```
Tekshirish (brauzerda): `https://DOMEN/health` → `{"error": "unauthorized"}` chiqsa — server ishlayapti ✅.

### 6.3. Telefonda
Dovud → Sozlamalar → "Server manzili": `https://DOMEN`, "Maxfiy token": tokeningiz → **Saqlash**.

### 6.4. Kompyuterda (C:\ai_xodim)
1. GitHub'dan 3 ta faylni yuklab oling (har birini oching → o'ng yuqorida **Download raw file**):
   - `https://github.com/olim-yqkj/dovud/blob/main/sulaymon/dovud_bridge.py`
   - `https://github.com/olim-yqkj/dovud/blob/main/sulaymon/dovud_bridge_config.json`
   - `https://github.com/olim-yqkj/dovud/blob/main/sulaymon/dovud_bridge_start.bat`
2. Uchalasini `C:\ai_xodim` papkasiga ko'chiring.
3. `dovud_bridge_config.json` ni **Блокнот** (Notepad) bilan oching va to'ldiring:
   - `"server"`: `"https://DOMEN"`
   - `"token"`: tokeningiz
   - `"handler"`: Sulaymonning buyruq bajaradigan funksiyasi (masalan `"main:handle_command"`). **Buni Claude bilan birga aniqlaymiz** — hozircha bo'sh qoldiring: "test rejimi"da ishlaydi.
4. **Fayl → Сохранить**.
5. `dovud_bridge_start.bat` ni ikki marta bosing. "Dovud ko'prigi ishga tushdi" yozuvi chiqadi — oyna ochiq tursin.
6. Telefonda: **"Dovud, Sulaymonga ayt: salom"** → "Test rejimi: Sulaymon buyruqni oldi — salom" deb javob kelsa ✅.

Kompyuter yonganda avtomatik ishga tushishi uchun: `Win+R` → `shell:startup` → Enter → ochilgan papkaga `dovud_bridge_start.bat` ning **yorlig'ini** (ярлык) qo'ying.

---

## 7. O'zini yaxshilash (ixtiyoriy)

Dovud biror ishni qila olmasa, "O'zimni yaxshilash so'rovini yuboraymi?" deydi. Ishlashi uchun:

1. **GitHub'da OpenAI kaliti:** `https://github.com/olim-yqkj/dovud/settings/secrets/actions/new` → Name: `OPENAI_API_KEY`, Secret: `sk-...` → **Add secret**.
2. **Telefon uchun GitHub token:**
   - `https://github.com/settings/personal-access-tokens/new`
   - Token name: `dovud`; Expiration: 1 yil
   - Repository access: **Only select repositories** → `dovud`
   - Permissions → Repository permissions → **Issues: Read and write**
   - **Generate token** → nusxalang.
   - Dovud → Sozlamalar → "GitHub repo": `olim-yqkj/dovud`, "GitHub token": tokenni joylang → **Saqlash**.

Yangi versiya tayyor bo'lganda bildirishnoma keladi; "O'rnatish" tugmasini o'zingiz bosasiz. Yangi versiya yoqmasa: **"Dovud, oldingi versiyaga qayt"**.

Tasdiq tizimi, ovoz izi va "to'xta" qismi **qulflangan** — Dovud ularni o'zi o'zgartira olmaydi.
