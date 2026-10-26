"""يملأ صورة/فيديو الأخبار المعتمدة القديمة التي لا وسائط لها: يفتح صفحة الخبر عند مصدره (بعد robots.txt وبفاصل زمني)
ويقرأ og:image وog:video. يحفظ الروابط فقط، ولا يستبدل وسائط موجودة. الصفحة بلا og:image تبقى بلا صورة (لا نخمّن صورة من متن الصفحة).
يُشغَّل يدوياً من GitHub Actions؛ --dry-run يطبع ما سيجده دون كتابة. التشغيل التالي يكمل بـ before_id الذي يطبعه التقرير."""
import os
import sys
import time

from . import feeds, media
from .pages import extract_page
from .store import StoreError, SupabaseStore

DEFAULT_LIMIT = 100
MAX_LIMIT = 300
DELAY = 1.5


def run_backfill(store, fetch=None, sleep=time.sleep, dry_run: bool = True, limit: int = DEFAULT_LIMIT,
                 before_id: int | None = None, delay: float = DELAY) -> dict:
    fetch = fetch or feeds.http_get
    limit = max(1, min(int(limit), MAX_LIMIT))
    rep = {"dry_run": dry_run, "seen": 0, "images": 0, "videos": 0, "nothing": 0, "robots": 0, "errors": 0,
           "last_id": None, "samples": []}
    rows = store.news_without_image(limit, before_id)
    for i, r in enumerate(rows):
        rep["seen"] += 1
        rep["last_id"] = r["id"]
        url = str(r.get("original_url") or "")
        if not url.lower().startswith("https://"):
            rep["nothing"] += 1
            continue
        if i:
            sleep(delay)
        try:
            data = feeds._guarded(fetch, url)
            it = extract_page(data, url)
        except feeds.FeedError as e:
            rep["robots" if "robots" in str(e) else "errors"] += 1
            continue
        except Exception:
            rep["errors"] += 1
            continue
        img = media.good_image(it.image_url) if it and it.image_url else None
        vid = it.video_url if it and it.video_url and media.is_video_url(it.video_url) else None
        if not img and not vid:
            rep["nothing"] += 1
            continue
        if img:
            rep["images"] += 1
        if vid:
            rep["videos"] += 1
        if len(rep["samples"]) < 12:
            rep["samples"].append((r["id"], img, vid))
        if not dry_run:
            try:
                store.set_media(r["id"], img, vid)
            except StoreError:
                rep["errors"] += 1
    return rep


def report(rep: dict) -> str:
    lines = [("تجربة بلا كتابة (dry-run)" if rep["dry_run"] else "تمت التعبئة"),
             f"فُحص: {rep['seen']} | صور وُجدت: {rep['images']} | فيديو: {rep['videos']} | بلا وسائط: {rep['nothing']} | "
             f"يمنعها robots: {rep['robots']} | أخطاء: {rep['errors']}"]
    for nid, img, vid in rep["samples"]:
        lines.append(f"  #{nid}: {img or '-'}" + (f" | فيديو: {vid}" if vid else ""))
    if rep["last_id"]:
        lines.append(f"للمتابعة مع الأقدم: before_id={rep['last_id']}")
    return "\n".join(lines)


def main() -> int:
    url, key = os.environ.get("SUPABASE_URL"), os.environ.get("SUPABASE_SERVICE_ROLE_KEY")
    if not url or not key:
        print("ناقص SUPABASE_URL أو SUPABASE_SERVICE_ROLE_KEY")
        return 2
    try:
        limit = int(os.environ.get("BACKFILL_LIMIT") or DEFAULT_LIMIT)
        before = int(os.environ.get("BACKFILL_BEFORE_ID") or 0) or None
    except ValueError:
        print("limit/before_id يجب أن تكون أرقاماً")
        return 2
    try:
        rep = run_backfill(SupabaseStore(url, key), dry_run="--dry-run" in sys.argv, limit=limit, before_id=before)
    except StoreError as e:
        print(f"فشل: {e}")
        return 1
    print(report(rep))
    return 0


if __name__ == "__main__":
    sys.exit(main())
