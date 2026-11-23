"""
Dovud o'zini yaxshilash skripti (GitHub Actions ichida ishlaydi).
1. So'rov matnini va ilova kodini AI'ga beradi.
2. AI qaytargan fayl o'zgarishlarini qo'llaydi (qulflangan fayllardan tashqari).
3. APK'ni yig'ib ko'radi; xato bo'lsa AI'ga qaytarib tuzattiradi (3 martagacha).
"""
import json
import os
import pathlib
import subprocess
import sys
import urllib.request

ROOT = pathlib.Path(__file__).resolve().parent.parent
SRC = ROOT / "app" / "src" / "main"
LOCKED = ("/safety/", ".github/", "tools/")
MODEL = os.environ.get("IMPROVE_MODEL", "gpt-5.4-mini")
KEY = os.environ.get("OPENAI_API_KEY", "")


def out(name, value):
    with open(os.environ["GITHUB_OUTPUT"], "a", encoding="utf-8") as f:
        value = str(value).replace("\n", " ")[:500]
        f.write(f"{name}={value}\n")


def collect():
    files = []
    for p in sorted(SRC.rglob("*")):
        if p.is_file() and p.suffix in (".kt", ".xml") and "assets" not in p.parts:
            rel = p.relative_to(ROOT).as_posix()
            files.append(f"=== {rel} ===\n{p.read_text(encoding='utf-8')}")
    files.append("=== app/build.gradle.kts ===\n" + (ROOT / "app/build.gradle.kts").read_text(encoding="utf-8"))
    return "\n\n".join(files)


def ask(messages):
    body = json.dumps({
        "model": MODEL,
        "messages": messages,
        "response_format": {"type": "json_object"},
    }).encode()
    req = urllib.request.Request("https://api.openai.com/v1/chat/completions", data=body, headers={
        "Authorization": f"Bearer {KEY}", "Content-Type": "application/json"})
    with urllib.request.urlopen(req, timeout=600) as r:
        j = json.load(r)
    return json.loads(j["choices"][0]["message"]["content"])


def apply(changes):
    applied = []
    for ch in changes.get("files", []):
        path = ch["path"].lstrip("/")
        if any(l in "/" + path for l in LOCKED) or ".." in path or not path.startswith("app/"):
            print("RAD ETILDI (qulflangan yoki ruxsatsiz):", path)
            continue
        p = ROOT / path
        if ch.get("delete"):
            if p.exists():
                p.unlink()
        else:
            p.parent.mkdir(parents=True, exist_ok=True)
            p.write_text(ch["content"], encoding="utf-8")
        applied.append(path)
    return applied


def build():
    r = subprocess.run(["gradle", "--no-daemon", "-q", "compileReleaseKotlin"], cwd=ROOT, capture_output=True, text=True)
    return r.returncode == 0, (r.stdout + r.stderr)[-6000:]


def main():
    if not KEY:
        print("OPENAI_API_KEY secret yo'q")
        out("changed", "false")
        return
    system = (
        "You are an expert Android/Kotlin engineer maintaining the 'Dovud' voice assistant app. "
        "Implement the owner's request with minimal, safe, compiling changes. "
        "NEVER modify files under app/src/main/java/uz/olim/dovud/safety/ (confirmation, voiceprint, stop logic are locked). "
        "Never weaken confirmation requirements. Do not add hardcoded secrets. "
        "Return JSON: {\"summary\": \"short Uzbek summary\", \"files\": [{\"path\": \"app/...\", \"content\": \"FULL new file content\"}]}. "
        "Always return the FULL content of each changed file. If impossible, return {\"summary\": \"why\", \"files\": []}."
    )
    messages = [
        {"role": "system", "content": system},
        {"role": "user", "content": f"REQUEST TITLE: {os.environ.get('ISSUE_TITLE','')}\nREQUEST: {os.environ.get('ISSUE_BODY','')}\n\nCURRENT CODE:\n{collect()}"},
    ]
    for attempt in range(3):
        res = ask(messages)
        applied = apply(res)
        if not applied:
            print("O'zgarish yo'q:", res.get("summary"))
            out("changed", "false")
            return
        ok, log = build()
        if ok:
            out("changed", "true")
            out("summary", res.get("summary", "Yaxshilandi"))
            print("Muvaffaqiyatli:", applied)
            return
        print(f"Yig'ish xatosi (urinish {attempt + 1}):\n{log}")
        messages.append({"role": "assistant", "content": json.dumps(res)[:20000]})
        messages.append({"role": "user", "content": f"Build failed. Fix it. Errors:\n{log}\nReturn the same JSON format with FULL file contents."})
    subprocess.run(["git", "checkout", "--", "app"], cwd=ROOT)
    subprocess.run(["git", "clean", "-fd", "app/src"], cwd=ROOT)
    out("changed", "false")


if __name__ == "__main__":
    main()
