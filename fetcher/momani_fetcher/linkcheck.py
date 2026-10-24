"""فحص روابط الأخبار المخزّنة: هل الرابط ما زال يعرض الخبر نفسه؟ (لا يكفي أن يعيد الخادم 200). يُشغَّل بعد الجلب ويكتب في link_checks.
لا يُحذف أي خبر تلقائياً؛ التقرير يضع الأخبار المعطلة أمام المدير. روابط محركات البحث (تحويلات Google/Bing) لا تُفحص لأنها ليست صفحة الخبر."""
import os
import sys
import time
from datetime import datetime, timedelta, timezone
from urllib.parse import urlparse

from . import feeds, validate
from .store import StoreError, SupabaseStore

MAX_PER_RUN = 40
PAUSE = 1.0                    # ثانية بين الطلبات (أدب مع المواقع الصغيرة)
MAX_UNREACHABLE_ATTEMPTS = 3   # تعذّر الوصول 3 مرات متتالية = معطّل
RECHECK = {validate.OK: timedelta(days=7), validate.BROKEN: timedelta(days=14), validate.CHANGED: timedelta(days=7),
           validate.UNREACHABLE: timedelta(days=1), validate.BLOCKED: timedelta(days=30)}
SKIP_HOSTS = ("news.google.", "bing.com", "google.com")


def run_linkcheck(store, probe_fn=validate.http_probe, robots_fetch=feeds.http_get, now: datetime | None = None, sleep=time.sleep, limit: int = MAX_PER_RUN) -> dict:
    now = now or datetime.now(timezone.utc)
    rows = [r for r in store.recent_news_links() if not any(h in (urlparse(r["original_url"]).hostname or "") for h in SKIP_HOSTS)]
    existing = store.link_checks_for([r["id"] for r in rows]) if rows else {}
    due = []
    for r in rows:
        c = existing.get(r["id"])
        nxt = feeds.parse_date(c["next_check_at"], now + timedelta(days=400)) if c else None
        if not c or (nxt and nxt <= now):
            due.append(r)
    rep = {"candidates": len(rows), "checked": 0, "counts": {}, "problems": []}
    for r in due[:limit]:
        status, reason = validate.check_link(r["original_url"], r["title"], probe_fn, robots_fetch, now)
        prev = existing.get(r["id"]) or {}
        attempts = (prev.get("attempts") or 0) + 1 if status == validate.UNREACHABLE else 0
        if status == validate.UNREACHABLE and attempts >= MAX_UNREACHABLE_ATTEMPTS:
            status, reason = validate.BROKEN, f"لا يستجيب منذ {attempts} محاولات متتالية ({reason})"
        store.save_link_check({"news_id": r["id"], "url": r["original_url"][:500], "status": status, "reason": reason[:300], "attempts": attempts,
                               "checked_at": now.isoformat(), "next_check_at": (now + RECHECK[status]).isoformat()})
        rep["checked"] += 1
        rep["counts"][status] = rep["counts"].get(status, 0) + 1
        if status in (validate.BROKEN, validate.CHANGED):
            rep["problems"].append(f"#{r['id']} [{status}] {r['title'][:60]} — {reason}")
        sleep(PAUSE)
    rep["remaining"] = max(0, len(due) - limit)
    return rep


def report(rep: dict) -> str:
    L = ["## فحص روابط الأخبار", "", f"- أخبار بروابط مباشرة (آخر 21 يوماً): {rep['candidates']} | فُحص الآن: {rep['checked']} | متبقٍ لاحقاً: {rep['remaining']}"]
    if rep["counts"]:
        L.append("- النتائج: " + "، ".join(f"{k}: {v}" for k, v in sorted(rep["counts"].items())))
    if rep["problems"]:
        L += ["", "**روابط معطلة أو تغيّر محتواها (راجعها في لوحة الأدمن):**"] + [f"- {p}" for p in rep["problems"]]
    elif rep["checked"]:
        L.append("- ✅ كل ما فُحص سليم فعلاً (لا مجرد استجابة ناجحة)")
    return "\n".join(L) + "\n"


def main() -> int:
    url, key = os.environ.get("SUPABASE_URL", ""), os.environ.get("SUPABASE_SERVICE_ROLE_KEY", "")
    if not url or not key:
        print("SUPABASE_URL / SUPABASE_SERVICE_ROLE_KEY غير مضبوطين.", file=sys.stderr)
        return 2
    try:
        rep = run_linkcheck(SupabaseStore(url, key))
    except StoreError as e:
        print(f"فشل: {e}. هل طُبّق الترحيل 20261022100000_link_checks.sql؟", file=sys.stderr)
        return 1
    text = report(rep)
    print(text)
    summary = os.environ.get("GITHUB_STEP_SUMMARY")
    if summary:
        try:
            with open(summary, "a", encoding="utf-8") as f:
                f.write(text)
        except OSError:
            pass
    return 0


if __name__ == "__main__":
    sys.exit(main())
