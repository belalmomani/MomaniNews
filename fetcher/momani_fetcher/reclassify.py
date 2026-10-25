"""يصنّف الأخبار القديمة غير المصنّفة (category null) بنفس قواعد الجالب. لا يمسّ خبراً له تصنيف (ولا يعيد كتابة تصنيف حدّده المدير يدوياً).
الخبر الذي لا يطابق شيئاً يبقى null. يُشغَّل يدوياً من GitHub Actions، وبـ --dry-run يطبع ما سيفعله دون كتابة."""
import os
import sys
from collections import Counter

from . import classify
from .store import StoreError, SupabaseStore

BATCH = 500
MAX_TOTAL = 5000    # سقف أمان لتشغيل واحد


def run_reclassify(store, dry_run: bool = False, max_total: int = MAX_TOTAL) -> dict:
    rep = {"seen": 0, "classified": 0, "unmatched": 0, "by_category": Counter(), "dry_run": dry_run}
    after = 0
    while rep["seen"] < max_total:
        rows = store.unclassified_news(BATCH, after)
        if not rows:
            break
        for r in rows:
            after = max(after, r["id"])
            rep["seen"] += 1
            cat = classify.classify(r.get("title") or "", r.get("excerpt") or "")
            if not cat:
                rep["unmatched"] += 1
                continue
            if not dry_run:
                store.set_category(r["id"], cat)
            rep["classified"] += 1
            rep["by_category"][cat] += 1
        if len(rows) < BATCH:
            break
    return rep


def report(rep: dict) -> str:
    lines = [("تجربة بلا كتابة (dry-run)" if rep["dry_run"] else "تم التصنيف"),
             f"فُحص: {rep['seen']} | صُنّف: {rep['classified']} | بلا تصنيف: {rep['unmatched']}"]
    for cat, n in rep["by_category"].most_common():
        lines.append(f"  {classify.CATEGORY_LABELS.get(cat, cat)}: {n}")
    return "\n".join(lines)


def main() -> int:
    url, key = os.environ.get("SUPABASE_URL"), os.environ.get("SUPABASE_SERVICE_ROLE_KEY")
    if not url or not key:
        print("ناقص SUPABASE_URL أو SUPABASE_SERVICE_ROLE_KEY")
        return 2
    try:
        rep = run_reclassify(SupabaseStore(url, key), dry_run="--dry-run" in sys.argv)
    except StoreError as e:
        print(f"فشل: {e}\n(هل شغّلت SQL عمود category؟)")
        return 1
    print(report(rep))
    return 0


if __name__ == "__main__":
    sys.exit(main())
