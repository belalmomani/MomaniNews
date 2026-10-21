"""دورة الجلب الكاملة. تُشغَّل من GitHub Actions بالجدولة (لا تعتمد على زيارة أي مستخدم)."""
import os
import sys
from collections import Counter
from dataclasses import dataclass, field

from . import dedup, feeds, matcher
from .store import StoreError, SupabaseStore

MAX_NEW_PER_SOURCE = 50
MAX_ITEMS_PER_FEED = 200
MAX_PAGES = 5          # حد أقصى لصفحات الخلاصة الأقدم (?paged=n) في وضع الاستكمال
MAX_REJECT_SAMPLES = 15


@dataclass
class Stats:
    sources_total: int = 0
    sources_failed: int = 0
    items_seen: int = 0
    items_matched: int = 0
    items_inserted: int = 0
    items_duplicate: int = 0
    items_rejected: int = 0
    reject_reasons: Counter = field(default_factory=Counter)
    reject_samples: list[str] = field(default_factory=list)   # عناوين رُفضت بسبب التباس (لمراجعة دقة الفلترة)
    sources: list[dict] = field(default_factory=list)          # تقرير موجز لكل مصدر
    errors: list[str] = field(default_factory=list)

    def as_row(self) -> dict:
        row = {k: getattr(self, k) for k in ("sources_total", "sources_failed", "items_seen", "items_matched", "items_inserted", "items_duplicate")}
        row["error_summary"] = ("؛ ".join(self.errors))[:1000] or None
        row["items_rejected"] = self.items_rejected
        row["reject_reasons"] = dict(self.reject_reasons)
        row["details"] = self.sources[:60]
        return row


def process_source(store, source: dict, keywords: list[matcher.Keyword], stats: Stats, fetch=feeds.http_get, pages: int = 1) -> None:
    sid, name, url = source["id"], source["name"], source["url"]
    rep = {"name": name, "seen": 0, "matched": 0, "inserted": 0, "duplicate": 0, "rejected": 0, "error": None}
    stats.sources.append(rep)
    if source.get("kind") != "rss":
        # الصفحات العامة (html) لا تُجلب تلقائياً: يتطلب ذلك مراجعة شروط الموقع لكل مصدر.
        store.source_result(sid, False, "نوع المصدر غير مدعوم آلياً (RSS فقط)")
        stats.sources_failed += 1
        stats.errors.append(f"{name}: نوع غير مدعوم")
        rep["error"] = "نوع غير مدعوم"
        return
    try:
        items, used_url = feeds.load_items(url, fetch)
        items = items[:MAX_ITEMS_PER_FEED]
    except feeds.FeedError as e:
        store.source_result(sid, False, str(e))
        store.log_failure(sid, url, str(e))
        stats.sources_failed += 1
        stats.errors.append(f"{name}: {e}")
        rep["error"] = str(e)[:120]
        return
    # الأخبار الأقدم: صفحات إضافية من الخلاصة نفسها (وضع الاستكمال فقط). فشل صفحة إضافية لا يُفشل المصدر.
    for page in range(2, max(1, min(pages, MAX_PAGES)) + 1):
        nxt = feeds.paged_url(used_url, page)
        if not nxt:
            break
        try:
            more, _ = feeds.load_items(nxt, fetch)
        except feeds.FeedError:
            break
        if not more or {m.link for m in more} <= {i.link for i in items}:
            break
        items += more

    stats.items_seen += len(items)
    rep["seen"] = len(items)
    candidates = []
    for it in items:
        m = matcher.evaluate(it.title, it.summary, keywords, it.text)
        norm = dedup.normalize_url(it.link)
        if m.matched and norm:
            candidates.append((it, m, norm))
        elif not m.matched:
            stats.items_rejected += 1
            rep["rejected"] += 1
            stats.reject_reasons[m.reason or matcher.R_NO_MATCH] += 1
            if m.reason in (matcher.R_AMBIGUOUS, matcher.R_NAME_NO_CONTEXT) and len(stats.reject_samples) < MAX_REJECT_SAMPLES:
                stats.reject_samples.append(f"[{matcher.REASON_LABELS[m.reason]}] {it.title[:90]} ({name})")
    stats.items_matched += len(candidates)
    rep["matched"] = len(candidates)

    known_urls = store.known_original_urls([c[2] for c in candidates]) if candidates else set()
    known_fps = store.known_fingerprints([dedup.fingerprint(c[0].title) for c in candidates]) if candidates else {}
    recent = store.recent_titles() if candidates else []
    inserted = 0
    for it, m, norm in candidates:
        if norm in known_urls:
            stats.items_duplicate += 1
            rep["duplicate"] += 1
            continue
        fp = dedup.fingerprint(it.title)
        twin = known_fps.get(fp) or dedup.find_similar(it.title, recent)
        if twin:
            store.add_ref(twin, name, norm)  # نفس الخبر من مصدر آخر: مرجع إضافي بدل منشور مكرر
            known_urls.add(norm)
            stats.items_duplicate += 1
            rep["duplicate"] += 1
            continue
        if inserted >= MAX_NEW_PER_SOURCE:
            continue
        excerpt = it.summary or it.title
        row = {
            "title": it.title[:300], "excerpt": excerpt[:600], "content": excerpt,
            "source_id": sid, "source_name": (it.publisher or name)[:120], "original_url": norm,
            "region_id": m.region_id, "status": "pending", "fingerprint": fp,
            "matched_terms": m.terms,
            "source_published_at": it.published.isoformat() if it.published else None,
            "fetch_note": (m.note or None),
        }
        new_id = store.insert_news(row)
        if new_id:
            inserted += 1
            stats.items_inserted += 1
            rep["inserted"] += 1
            known_urls.add(norm)
            known_fps[fp] = new_id
            recent.append((new_id, it.title))
        else:
            stats.items_duplicate += 1
            rep["duplicate"] += 1  # رفضته القاعدة (قيد التفرد): سباق مع تشغيل آخر
    store.source_result(sid, True, None)


def run(store, trigger: str = "schedule", fetch=feeds.http_get, pages: int = 1) -> Stats:
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
                process_source(store, s, keywords, stats, fetch, pages)
            except StoreError as e:
                stats.sources_failed += 1
                stats.errors.append(f"{s.get('name')}: {e}")
    finally:
        store.finish_run(run_id, stats.as_row())
    return stats


def report(stats: Stats) -> str:
    """تقرير موجز بعد كل تشغيل (Markdown). يفرّق بين «نجاح التشغيل» و«نجاح اكتشاف أخبار جديدة»."""
    ok = stats.sources_total - stats.sources_failed
    lines = ["## تقرير الجلب", ""]
    lines.append(f"- المصادر: {stats.sources_total} (سليمة {ok}، متعطلة {stats.sources_failed})")
    lines.append(f"- عناصر فُحصت: {stats.items_seen} | مقبولة: {stats.items_matched} | مرفوضة: {stats.items_rejected} | مكررة: {stats.items_duplicate} | **جديدة: {stats.items_inserted}**")
    if stats.reject_reasons:
        lines.append("- أسباب الرفض: " + "، ".join(f"{matcher.REASON_LABELS.get(k, k)} ({v})" for k, v in stats.reject_reasons.most_common()))
    if stats.sources_total == 0:
        lines.append("- ⚠ لا توجد مصادر مفعّلة")
    elif ok == 0:
        lines.append("- ❌ فشلت كل المصادر")
    elif stats.items_inserted == 0:
        lines.append("- ✅ التشغيل نجح تقنياً، لكن ⚠ لم تُكتشف أخبار جديدة ذات صلة في هذه الدورة")
    else:
        lines.append(f"- ✅ نجح التشغيل وأُضيفت {stats.items_inserted} أخبار بانتظار اعتماد المدير")
    productive = [s for s in stats.sources if s["inserted"]]
    if productive:
        lines += ["", "**مصادر أنتجت أخباراً جديدة:**"] + [f"- {s['name']}: {s['inserted']}" for s in productive]
    failing = [s for s in stats.sources if s["error"]]
    if failing:
        lines += ["", "**مصادر متعطلة:**"] + [f"- {s['name']}: {s['error']}" for s in failing]
    if stats.reject_samples:
        lines += ["", "**عيّنة من أخبار رُفضت لالتباس (راجع الفلترة إن كانت صالحة):**"] + [f"- {x}" for x in stats.reject_samples]
    return "\n".join(lines) + "\n"


def main() -> int:
    url, key = os.environ.get("SUPABASE_URL", ""), os.environ.get("SUPABASE_SERVICE_ROLE_KEY", "")
    if not url or not key:
        print("SUPABASE_URL / SUPABASE_SERVICE_ROLE_KEY غير مضبوطين (أضفهما كأسرار GitHub).", file=sys.stderr)
        return 2
    try:
        pages = max(1, min(int(os.environ.get("FETCH_PAGES", "1") or "1"), MAX_PAGES))
    except ValueError:
        pages = 1
    try:
        stats = run(SupabaseStore(url, key), os.environ.get("FETCH_TRIGGER", "schedule"), pages=pages)
    except StoreError as e:
        print(f"فشل الاتصال بقاعدة البيانات: {e}", file=sys.stderr)
        return 1
    text = report(stats)
    print(text)
    summary = os.environ.get("GITHUB_STEP_SUMMARY")
    if summary:
        try:
            with open(summary, "a", encoding="utf-8") as f:
                f.write(text)
        except OSError:
            pass
    if stats.sources_total and stats.items_inserted == 0 and stats.sources_failed < stats.sources_total:
        print("::notice::التشغيل نجح ولم تُكتشف أخبار جديدة ذات صلة")
    return 1 if stats.sources_total and stats.sources_failed == stats.sources_total else 0


if __name__ == "__main__":
    sys.exit(main())
