"""دورة الجلب الكاملة. تُشغَّل من GitHub Actions بالجدولة (لا تعتمد على زيارة أي مستخدم)."""
import os
import sys
from dataclasses import dataclass, field

from . import dedup, feeds, matcher
from .store import StoreError, SupabaseStore

MAX_NEW_PER_SOURCE = 50
MAX_ITEMS_PER_FEED = 200


@dataclass
class Stats:
    sources_total: int = 0
    sources_failed: int = 0
    items_seen: int = 0
    items_matched: int = 0
    items_inserted: int = 0
    items_duplicate: int = 0
    errors: list[str] = field(default_factory=list)

    def as_row(self) -> dict:
        row = {k: getattr(self, k) for k in ("sources_total", "sources_failed", "items_seen", "items_matched", "items_inserted", "items_duplicate")}
        row["error_summary"] = ("؛ ".join(self.errors))[:1000] or None
        return row


def process_source(store, source: dict, keywords: list[matcher.Keyword], stats: Stats, fetch=feeds.http_get) -> None:
    sid, name, url = source["id"], source["name"], source["url"]
    if source.get("kind") != "rss":
        # الصفحات العامة (html) لا تُجلب تلقائياً: يتطلب ذلك مراجعة شروط الموقع لكل مصدر.
        store.source_result(sid, False, "نوع المصدر غير مدعوم آلياً (RSS فقط)")
        stats.sources_failed += 1
        stats.errors.append(f"{name}: نوع غير مدعوم")
        return
    try:
        items = feeds.parse_feed(fetch(url))[:MAX_ITEMS_PER_FEED]
    except feeds.FeedError as e:
        store.source_result(sid, False, str(e))
        store.log_failure(sid, url, str(e))
        stats.sources_failed += 1
        stats.errors.append(f"{name}: {e}")
        return

    stats.items_seen += len(items)
    candidates = []
    for it in items:
        m = matcher.evaluate(it.title, it.summary, keywords)
        norm = dedup.normalize_url(it.link)
        if m.matched and norm:
            candidates.append((it, m, norm))
    stats.items_matched += len(candidates)

    known_urls = store.known_original_urls([c[2] for c in candidates]) if candidates else set()
    known_fps = store.known_fingerprints([dedup.fingerprint(c[0].title) for c in candidates]) if candidates else {}
    recent = store.recent_titles() if candidates else []
    inserted = 0
    for it, m, norm in candidates:
        if norm in known_urls:
            stats.items_duplicate += 1
            continue
        fp = dedup.fingerprint(it.title)
        twin = known_fps.get(fp) or dedup.find_similar(it.title, recent)
        if twin:
            store.add_ref(twin, name, norm)  # نفس الخبر من مصدر آخر: مرجع إضافي بدل منشور مكرر
            known_urls.add(norm)
            stats.items_duplicate += 1
            continue
        if inserted >= MAX_NEW_PER_SOURCE:
            continue
        excerpt = it.summary or it.title
        row = {
            "title": it.title[:300], "excerpt": excerpt[:600], "content": excerpt,
            "source_id": sid, "source_name": name[:120], "original_url": norm,
            "region_id": m.region_id, "status": "pending", "fingerprint": fp,
            "matched_terms": m.terms,
            "source_published_at": it.published.isoformat() if it.published else None,
            "fetch_note": (m.note or None),
        }
        new_id = store.insert_news(row)
        if new_id:
            inserted += 1
            stats.items_inserted += 1
            known_urls.add(norm)
            known_fps[fp] = new_id
            recent.append((new_id, it.title))
        else:
            stats.items_duplicate += 1  # رفضته القاعدة (قيد التفرد): سباق مع تشغيل آخر
    store.source_result(sid, True, None)


def run(store, trigger: str = "schedule", fetch=feeds.http_get) -> Stats:
    stats = Stats()
    run_id = store.start_run(trigger)
    try:
        sources = store.active_sources()
        keywords = [matcher.Keyword(k["word"], k["kind"], k.get("region_id"), bool(k.get("needs_context"))) for k in store.active_keywords()]
        stats.sources_total = len(sources)
        if not keywords:
            stats.errors.append("لا توجد كلمات مفتاحية مفعّلة")
        for s in sources:
            try:
                process_source(store, s, keywords, stats, fetch)
            except StoreError as e:
                stats.sources_failed += 1
                stats.errors.append(f"{s.get('name')}: {e}")
    finally:
        store.finish_run(run_id, stats.as_row())
    return stats


def main() -> int:
    url, key = os.environ.get("SUPABASE_URL", ""), os.environ.get("SUPABASE_SERVICE_ROLE_KEY", "")
    if not url or not key:
        print("SUPABASE_URL / SUPABASE_SERVICE_ROLE_KEY غير مضبوطين (أضفهما كأسرار GitHub).", file=sys.stderr)
        return 2
    try:
        stats = run(SupabaseStore(url, key), os.environ.get("FETCH_TRIGGER", "schedule"))
    except StoreError as e:
        print(f"فشل الاتصال بقاعدة البيانات: {e}", file=sys.stderr)
        return 1
    print(f"المصادر: {stats.sources_total} (فشل {stats.sources_failed}) | شُوهد: {stats.items_seen} | مطابق: {stats.items_matched} | جديد: {stats.items_inserted} | مكرر: {stats.items_duplicate}")
    for e in stats.errors:
        print("خطأ:", e)
    return 1 if stats.sources_total and stats.sources_failed == stats.sources_total else 0


if __name__ == "__main__":
    sys.exit(main())
