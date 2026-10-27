"""مراقب الجلب: يفشل (فيصلك إشعار GitHub بفشل المهمة) إذا بدا أن الجلب متوقف، بدل أن تكتشف ذلك بعد أيام.
لا يعتبر «لا أخبار جديدة» عطلاً (طبيعي). العطل = لا تشغيل حديث، أو تشغيلات متتالية فاشلة/عمياء."""
import os
import sys
from datetime import datetime, timedelta, timezone

from . import feeds
from .store import StoreError, SupabaseStore

MAX_SILENCE = timedelta(hours=8)     # الجدولة كل 3 ساعات تقريباً؛ 8 ساعات بلا تشغيل = متوقف
BAD_STREAK = 3                       # عدد التشغيلات المتتالية الفاشلة أو العمياء التي تُعدّ عطلاً


def _bad(run: dict) -> str | None:
    """سبب كون التشغيل «سيئاً»، أو None. سيئ = كل المصادر فشلت، أو لم يُفحص أي عنصر. فشل بعض المصادر مع فحص عناصر ليس عطلاً."""
    # error_summary يُملأ عند فشل أي مصدر (حتى لو نجح الباقي)، فلا يُعدّ وحده عطلاً
    err = ("خطأ: " + str(run["error_summary"])[:120]) if run.get("error_summary") else ""
    total, failed = run.get("sources_total") or 0, run.get("sources_failed") or 0
    if total and failed >= total:
        return f"فشلت كل المصادر ({failed}/{total})"
    if (run.get("items_seen") or 0) == 0:
        return "لم يُفحص أي عنصر" + (" | " + err if err else "")
    return None


def evaluate(runs: list[dict], now: datetime) -> list[str]:
    """قائمة المشكلات (فارغة = سليم). runs من الأحدث للأقدم."""
    problems: list[str] = []
    if not runs:
        return ["لا يوجد أي تشغيل جلب مسجّل"]
    last = feeds.parse_date(runs[0].get("started_at"), now)
    if last is None:
        problems.append("تاريخ آخر تشغيل غير مقروء")
    elif now - last > MAX_SILENCE:
        hours = int((now - last).total_seconds() // 3600)
        problems.append(f"آخر تشغيل جلب قبل {hours} ساعة (الحد {int(MAX_SILENCE.total_seconds() // 3600)})")
    recent = runs[:BAD_STREAK]
    reasons = [_bad(r) for r in recent]
    if len(recent) >= BAD_STREAK and all(reasons):
        problems.append(f"آخر {BAD_STREAK} تشغيلات سيئة: " + " | ".join(r for r in reasons if r))
    return problems


def main() -> int:
    url, key = os.environ.get("SUPABASE_URL"), os.environ.get("SUPABASE_SERVICE_ROLE_KEY")
    if not url or not key:
        print("ناقص SUPABASE_URL أو SUPABASE_SERVICE_ROLE_KEY")
        return 2
    try:
        runs = SupabaseStore(url, key).recent_runs(8)
    except StoreError as e:
        print(f"تعذر قراءة سجل الجلب: {e}")
        return 1
    problems = evaluate(runs, datetime.now(timezone.utc))
    if problems:
        print("⚠ الجلب يبدو متوقفاً:")
        for p in problems:
            print(" -", p)
        return 1
    print(f"✅ الجلب سليم (آخر تشغيل: {runs[0].get('started_at')})")
    return 0


if __name__ == "__main__":
    sys.exit(main())
