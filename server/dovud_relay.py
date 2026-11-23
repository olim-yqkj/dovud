"""
Dovud ↔ Sulaymon RELAY SERVERI (serverda ishlaydi).
Faqat Python 3 standart kutubxonasi — hech narsa o'rnatish shart emas.

Telefon:   POST /dovud/send {"text": ...}          -> {"id": ...}
           GET  /dovud/result/<id>                 -> {"status": "waiting"|"done", "answer": ...}
Sulaymon:  GET  /sulaymon/next                     -> {"id", "text"} yoki 204 (25 soniya kutadi)
           POST /sulaymon/answer {"id", "answer"}  -> {"ok": true}
Hamma so'rovlar: Authorization: Bearer <TOKEN>

Ishga tushirish:  DOVUD_TOKEN=uzun_maxfiy_token python3 dovud_relay.py
HTTPS uchun oldiga Caddy qo'yiladi (yo'riqnomada).
"""
import hmac
import json
import os
import threading
import time
import uuid
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer

TOKEN = os.environ.get("DOVUD_TOKEN", "")
PORT = int(os.environ.get("DOVUD_PORT", "8787"))
if len(TOKEN) < 24:
    raise SystemExit("DOVUD_TOKEN kamida 24 belgidan iborat bo'lishi kerak")

lock = threading.Condition()
queue = []          # [(id, text)]
results = {}        # id -> {"status", "answer", "t"}


def cleanup():
    now = time.time()
    for k in [k for k, v in results.items() if now - v["t"] > 3600]:
        results.pop(k, None)


class H(BaseHTTPRequestHandler):
    server_version = "DovudRelay/1"

    def log_message(self, fmt, *args):
        pass  # maxfiy matnlar logga yozilmasin

    def _auth(self):
        got = self.headers.get("Authorization", "")
        ok = hmac.compare_digest(got.encode(), f"Bearer {TOKEN}".encode())
        if not ok:
            self._send(401, {"error": "unauthorized"})
        return ok

    def _send(self, code, obj=None):
        data = json.dumps(obj or {}, ensure_ascii=False).encode()
        self.send_response(code)
        self.send_header("Content-Type", "application/json; charset=utf-8")
        self.send_header("Content-Length", str(len(data)))
        self.end_headers()
        if code != 204:
            self.wfile.write(data)

    def _body(self):
        n = int(self.headers.get("Content-Length", "0") or 0)
        if n > 100_000:
            return {}
        return json.loads(self.rfile.read(n) or b"{}")

    def do_GET(self):
        if not self._auth():
            return
        if self.path.startswith("/dovud/result/"):
            rid = self.path.rsplit("/", 1)[-1]
            with lock:
                r = results.get(rid)
            if not r:
                return self._send(404, {"error": "not found"})
            return self._send(200, {"status": r["status"], "answer": r.get("answer", "")})
        if self.path.startswith("/sulaymon/next"):
            deadline = time.time() + 25
            with lock:
                while not queue and time.time() < deadline:
                    lock.wait(timeout=deadline - time.time())
                if queue:
                    rid, text = queue.pop(0)
                    return self._send(200, {"id": rid, "text": text})
            return self._send(204)
        if self.path == "/health":
            return self._send(200, {"ok": True})
        self._send(404, {"error": "no route"})

    def do_POST(self):
        if not self._auth():
            return
        try:
            b = self._body()
        except Exception:
            return self._send(400, {"error": "bad json"})
        if self.path == "/dovud/send":
            text = str(b.get("text", "")).strip()[:4000]
            if not text:
                return self._send(400, {"error": "empty"})
            rid = uuid.uuid4().hex
            with lock:
                cleanup()
                results[rid] = {"status": "waiting", "t": time.time()}
                queue.append((rid, text))
                lock.notify_all()
            return self._send(200, {"id": rid})
        if self.path == "/sulaymon/answer":
            rid = str(b.get("id", ""))
            with lock:
                if rid in results:
                    results[rid].update(status="done", answer=str(b.get("answer", ""))[:8000], t=time.time())
            return self._send(200, {"ok": True})
        self._send(404, {"error": "no route"})


if __name__ == "__main__":
    print(f"Dovud relay 127.0.0.1:{PORT} da ishlayapti")
    ThreadingHTTPServer(("127.0.0.1", PORT), H).serve_forever()
