"""
Sulaymon tomoni: serverdan Dovud buyruqlarini oladi, Sulaymonga beradi, javobni qaytaradi.
C:\\ai_xodim papkasiga qo'yiladi. Python 3.11, faqat standart kutubxona.

Sozlash: shu papkadagi dovud_bridge_config.json faylida:
{
  "server": "https://sizning-serveringiz",
  "token": "telefondagi bilan bir xil maxfiy token",
  "handler": "modul_nomi:funksiya_nomi"
}
"handler" — Sulaymonning buyruqni bajarib, javob MATNINI qaytaradigan funksiyasi.
Bo'sh qoldirilsa, "test" rejimida ishlaydi (buyruqni qaytarib aytadi).
"""
import importlib
import json
import os
import sys
import time
import traceback
import urllib.error
import urllib.request

HERE = os.path.dirname(os.path.abspath(__file__))
sys.path.insert(0, HERE)
CFG = json.load(open(os.path.join(HERE, "dovud_bridge_config.json"), encoding="utf-8"))
SERVER = CFG["server"].rstrip("/")
TOKEN = CFG["token"]
if not SERVER.startswith("https://"):
    raise SystemExit("Xavfsizlik uchun server manzili https:// bilan boshlanishi kerak")


def load_handler():
    spec = (CFG.get("handler") or "").strip()
    if not spec:
        return lambda text: f"Test rejimi: Sulaymon buyruqni oldi — {text}"
    mod, fn = spec.split(":")
    return getattr(importlib.import_module(mod), fn)


def req(method, path, body=None, timeout=35):
    data = json.dumps(body).encode() if body is not None else None
    r = urllib.request.Request(SERVER + path, data=data, method=method, headers={
        "Authorization": f"Bearer {TOKEN}", "Content-Type": "application/json"})
    with urllib.request.urlopen(r, timeout=timeout) as resp:
        if resp.status == 204:
            return None
        return json.load(resp)


def main():
    handler = load_handler()
    print("Dovud ko'prigi ishga tushdi. Buyruqlarni kutyapman...")
    while True:
        try:
            job = req("GET", "/sulaymon/next")
            if not job:
                continue
            print("Dovuddan buyruq:", job["text"])
            try:
                answer = handler(job["text"])
                answer = str(answer) if answer is not None else "Bajarildi."
            except Exception as e:
                traceback.print_exc()
                answer = f"Sulaymonda xato: {e}"
            req("POST", "/sulaymon/answer", {"id": job["id"], "answer": answer})
            print("Javob yuborildi.")
        except urllib.error.HTTPError as e:
            print("Server xatosi:", e.code, "(token to'g'rimi?)")
            time.sleep(10)
        except Exception as e:
            print("Ulanish xatosi:", e)
            time.sleep(5)


if __name__ == "__main__":
    main()
