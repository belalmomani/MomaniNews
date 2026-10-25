"""دورة الجلب الكاملة. تُشغَّل من GitHub Actions بالجدولة (لا تعتمد على زيارة أي مستخدم)."""
import os
import sys
from collections import Counter, defaultdict
from urllib.parse import urlparse
from dataclasses import dataclass, field

from datetime import datetime, timedelta, timezone

from . import classify, dedup, discover, feeds, matcher, pages as pagemod, queries, validate, wpapi
from .store import StoreError, SupabaseStore

MAX_NEW_PER_SOURCE = 100
CATCHUP_GAP = timedelta(hours=3)   # انقطاع أطول من هذا ← استدراك (صفحات أكثر) لما فات
CATCHUP_PAGES = 60
CATCHUP_LOOKBACK = timedelta(days=30)   # سقف الرجوع للوراء مهما طال الانقطاع: لا نعيد مسح الأرشيف كله
CATCHUP_BUDGET = 400    # أقصى طلبات شبكة في التشغيل الواحد قبل تأجيل استدراك المصادر المتبقية للدورة التالية
BACKFILL_DAYS = 30      # الأثر الرجعي عند اكتشاف مصدر جديد
BACKFILL_PAGES = 5
BACKFILL_SITEMAP_PAGES = 40
SEARCH_INTERVAL = timedelta(hours=3)     # الاستعلامات الأساسية لا تُسأل كل 30 دقيقة (أدب مع الخدمة وتفادي الحجب)
EXT_INTERVAL = timedelta(hours=24)        # الاستعلامات الموسّعة (قائمة المالك): كل استعلام مرة يومياً على الأكثر
EXT_PER_RUN = 15                          # وبحد أقصى 15 استعلاماً موسّعاً في الدورة (الأقدم فحصاً أولاً)
SEARCH_PAUSE = 1.0                        # ثانية بين طلبات البحث
MAX_ITEMS_PER_FEED = 200
MAX_PAGES = 5          # حد أقصى لصفحات الخلاصة الأقدم (?paged=n) في وضع الاستكمال
MAX_REJECT_SAMPLES = 15
MAX_SEARCH_AGE = timedelta(days=365)   # نتيجة بحث أقدم من هذا لا تُقبل
MAX_NOTED_DOMAINS = 40  # مرشحون جدد من داخل الأخبار في التشغيل الواحد


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
    noted_domains: set = field(default_factory=set)
    errors: list[str] = field(default_factory=list)
    requests: int = 0
    search_skipped: int = 0
    catchup_deferred: int = 0
    health: list[str] = field(default_factory=list)            # مشاكل صحة الخلاصات (فارغة/معطوبة/أُعيد اكتشافها)
    from_news_domains: int = 0                                  # نطاقات مرشحة اكتُشفت من داخل الأخبار
    source_hosts: set = field(default_factory=set)
    engines_down: set = field(default_factory=set)
    ext_total: int = 0
    items_dropped_link: int = 0      # ذات صلة لكن بلا رابط https صالح (كانت تُحذف بصمت)
    dropped_samples: list = field(default_factory=list)
    engines_blocked: list = field(default_factory=list)
    found: dict = field(default_factory=lambda: defaultdict(set))   # بصمة الخبر ← {(محرك، رقم المصدر)} لقياس المساهمة الفريدة
    engines: dict = field(default_factory=dict)                # محرك ← أرقام مجمّعة (تُحسب في finalize)
    variants: list[dict] = field(default_factory=list)         # قياس كل استعلام بحث

    def finalize(self) -> None:
        """يحسب المساهمة الفريدة: خبر وجده مصدر/محرك واحد فقط في هذا التشغيل (بلا تكرار مع غيره)."""
        eng: dict = {}
        for i, rep in enumerate(self.sources):
            e = rep.get("engine", queries.ENGINE_DIRECT)
            d = eng.setdefault(e, {"sources": 0, "seen": 0, "matched": 0, "inserted": 0, "duplicate": 0, "rejected": 0, "failed": 0, "stories": 0, "unique": 0})
            d["sources"] += 1
            for k in ("seen", "matched", "inserted", "duplicate", "rejected"):
                d[k] += rep.get(k, 0)
            d["failed"] += 1 if rep.get("error") else 0
            rep["unique"] = 0
        for fp, who in self.found.items():
            engines = {e for (e, _i) in who}
            for e in engines:
                eng.setdefault(e, {"sources": 0, "seen": 0, "matched": 0, "inserted": 0, "duplicate": 0, "rejected": 0, "failed": 0, "stories": 0, "unique": 0})["stories"] += 1
            if len(engines) == 1:
                eng[next(iter(engines))]["unique"] += 1
            if len(who) == 1:
                (_e, i), = who
                self.sources[i]["unique"] += 1
        self.engines = eng
        self.variants = [
            {"name": r["name"], "engine": r.get("engine"), "seen": r["seen"], "matched": r["matched"], "inserted": r["inserted"],
             "rejected": r["rejected"], "unique": r.get("unique", 0), "error": r.get("error"),
             "noise": round(r["rejected"] / r["seen"], 2) if r["seen"] else None}
            for r in self.sources if r.get("origin") == "search"]

    def as_row(self) -> dict:
        row = {k: getattr(self, k) for k in ("sources_total", "sources_failed", "items_seen", "items_matched", "items_inserted", "items_duplicate")}
        row["error_summary"] = ("؛ ".join(self.errors))[:1000] or None
        row["items_rejected"] = self.items_rejected
        row["reject_reasons"] = dict(self.reject_reasons)
        row["details"] = {"sources": self.sources[:60], "engines": self.engines, "variants": self.variants[:60], "health": self.health[:30],
                          "dropped_no_https": self.items_dropped_link, "engines_blocked": self.engines_blocked, "requests": self.requests, "ext_total": self.ext_total, "search_skipped": self.search_skipped, "catchup_deferred": self.catchup_deferred}
        return row


def _host_of(u: str) -> str:
    return (urlparse(u).hostname or "").lower().removeprefix("www.")


def _all_known(store, seen_lookup, items) -> bool:
    """هل كل روابط الصفحة معروفة (خبر محفوظ أو صفحة فُحصت سابقاً)؟ = وصلنا لمنطقة جُلبت من قبل فنتوقف عن النزول للأقدم."""
    norms = [n for n in (dedup.normalize_url(i.link) for i in items) if n]
    if not norms:
        return False
    return set(norms) <= (store.known_original_urls(norms) | seen_lookup(norms))


def process_source(store, source: dict, keywords: list[matcher.Keyword], stats: Stats, fetch=feeds.http_get, pages: int = 1, sleep=None,
                   now: datetime | None = None, backfill: bool = False) -> None:
    sid, name, url = source["id"], source["name"], source["url"]
    origin = source.get("origin") or "manual"
    engine = queries.engine_of(url)
    rep = {"name": name, "seen": 0, "matched": 0, "inserted": 0, "duplicate": 0, "rejected": 0, "error": None, "engine": engine, "origin": origin}
    stats.sources.append(rep)
    idx = len(stats.sources) - 1
    kind = source.get("kind")
    if kind not in ("rss", "html"):
        # النوع manual لا يُجلب آلياً
        store.source_result(sid, False, "نوع المصدر غير مدعوم آلياً")
        stats.sources_failed += 1
        stats.errors.append(f"{name}: نوع غير مدعوم")
        rep["error"] = "نوع غير مدعوم"
        return
    via_pages = False
    now = now or datetime.now(timezone.utc)
    since = feeds.parse_date(source.get("last_success_at"), now)
    floor = now - (timedelta(days=BACKFILL_DAYS) if backfill else CATCHUP_LOOKBACK)
    if since and since < floor:
        since = floor     # سقف الرجوع: انقطاع شهرين لا يعني مسح شهرين
    catchup = bool(backfill or (since and now - since > CATCHUP_GAP))  # انقطع الجلب (أو مصدر جديد): نستدرك ما فات
    advance = True        # هل نقدّم last_success_at؟ لا نقدّمه إن بقي جزء من الفجوة غير مجلوب
    if catchup and not backfill and stats.requests > CATCHUP_BUDGET:
        catchup = False   # ميزانية الاستدراك استُنفدت: نجلب الأحدث فقط ونُبقي نقطة الاستدراك كما هي للدورة التالية
        advance = False
        stats.catchup_deferred += 1
        rep["catchup_deferred"] = True
    if catchup:
        pages = max(pages, BACKFILL_PAGES if backfill else 3)
        rep["backfill" if backfill else "catchup"] = True
    kw = {} if sleep is None else {"sleep": sleep}
    if catchup:
        kw["max_pages"] = BACKFILL_SITEMAP_PAGES if backfill else CATCHUP_PAGES
    seen_lookup = getattr(store, "known_seen_urls", lambda urls: set())
    try:
        if kind == "html" and wpapi.is_wp_api(url):
            terms = [k.word for k in keywords if k.kind in ("region", "alias", "name")]
            items = wpapi.collect(url, fetch, since, terms, max_pages=5 if catchup else 2, now=now)
            used_url, via_pages = url, True
        elif kind == "html" or (kind == "rss" and "sitemap" in url.lower()):
            items, _mode = pagemod.collect(store, url, fetch, known_seen=seen_lookup, **kw)
            used_url, via_pages = url, True
        else:
            try:
                items, used_url = feeds.load_items(url, fetch)
            except feeds.FeedError as e:
                if not str(e).startswith("لم يُعثر على خلاصة"):
                    raise
                # لا RSS: نجرّب sitemap المعلن في robots.txt أو المسارات الشائعة بدل الإخفاق
                items = None
                for sm in pagemod.discover_sitemaps(url, fetch):
                    try:
                        items, _mode = pagemod.collect(store, sm, fetch, known_seen=seen_lookup, **kw)
                        used_url, via_pages = sm, True
                        break
                    except feeds.FeedError:
                        continue
                    except StoreError:
                        raise
                    except Exception:  # noqa: BLE001 - sitemap معطوب لا يُسقط المصدر ولا الدورة
                        continue
                if items is None:
                    raise
        items = items[:MAX_ITEMS_PER_FEED]
    except feeds.FeedError as e:
        store.source_result(sid, False, str(e))
        store.log_failure(sid, url, str(e))
        stats.sources_failed += 1
        stats.errors.append(f"{name}: {e}")
        rep["error"] = str(e)[:120]
        return
    if via_pages:
        rep["via"] = "sitemap/صفحات"
    # صحة الخلاصة: XML صالح لا يكفي. خلاصة فارغة/معطوبة في موقع مباشر غالباً تعني تغيّر بنية الموقع.
    if not via_pages and origin != "search":
        problem = validate.check_feed_items(items, now, used_url)
        if problem and problem[0] != "stale":
            fixed = None
            root = f"https://{_host_of(used_url)}/"
            try:
                new_items, new_url = feeds.load_items(root, fetch)
                if new_url.rstrip("/") != used_url.rstrip("/") and not validate.check_feed_items(new_items, now, new_url):
                    fixed = (new_items, new_url)
            except feeds.FeedError:
                pass
            if fixed and origin != "manual" and hasattr(store, "update_source_url"):
                items, used_url = fixed
                store.update_source_url(sid, used_url)
                stats.health.append(f"{name}: {problem[1]} ← وُجدت خلاصة بديلة وحُدّث الرابط ({used_url})")
                rep["rediscovered"] = used_url
            else:
                hint = f" — خلاصة بديلة مقترحة: {fixed[1]}" if fixed else ""
                msg = problem[1] + hint
                store.source_result(sid, False, msg)
                store.log_failure(sid, url, msg)
                stats.sources_failed += 1
                stats.errors.append(f"{name}: {msg}")
                stats.health.append(f"{name}: {msg}")
                rep["error"] = msg[:120]
                return
        elif problem:
            stats.health.append(f"{name}: {problem[1]}")
            rep["warning"] = problem[1][:100]
    # الأخبار الأقدم: صفحات إضافية من الخلاصة نفسها (وضع الاستكمال). تتوقف عند: صفحة بلا جديد، صفحة معروفة بالكامل (وصلنا لما جُلب
    # سابقاً)، أو صفحة أقدم من نقطة الاستدراك. فشل صفحة إضافية لا يُفشل المصدر لكنه يُبقي نقطة الاستدراك دون تقديم.
    for page in range(2, max(1, min(pages, MAX_PAGES)) + 1 if not via_pages else 1):
        nxt = feeds.paged_url(used_url, page)
        if not nxt:
            break
        try:
            more, _ = feeds.load_items(nxt, fetch)
        except feeds.FeedError as e:
            if catchup and "404" not in str(e):
                advance = False       # توقفنا بفشل لا بنهاية الأرشيف
            break
        if not more or {m.link for m in more} <= {i.link for i in items}:
            break
        if catchup and _all_known(store, seen_lookup, more):
            rep["catchup_stop"] = "وصلنا لأخبار جُلبت سابقاً"
            break
        items += more
        dated = [m.published for m in more if m.published]
        if catchup and since and dated and max(dated) < since - timedelta(days=1):
            rep["catchup_stop"] = "تجاوزنا نقطة آخر نجاح"
            break
    if backfill:
        items = [i for i in items if not i.published or i.published >= floor]

    stats.items_seen += len(items)
    rep["seen"] = len(items)
    candidates = []
    for it in items:
        # قبل المطابقة: ناشر الداخل الفلسطيني، وخبر بحث قديم جداً (مثل «ليالي ميلاد 2013»)
        block = None
        if matcher.is_non_local_publisher(it.link, it.publisher_url, it.publisher):
            block = matcher.R_NON_LOCAL
        elif origin == "search" and it.published and it.published < now - MAX_SEARCH_AGE:
            block = matcher.R_TOO_OLD
        if block:
            stats.items_rejected += 1
            rep["rejected"] += 1
            stats.reject_reasons[block] += 1
            continue
        m = matcher.evaluate(it.title, it.summary, keywords, it.text)
        norm = dedup.normalize_url(it.link)
        if m.matched and norm:
            candidates.append((it, m, norm))
        elif m.matched:
            stats.items_dropped_link += 1      # مطابق لكن رابطه ليس https صالحاً: لا يُخزَّن، لكن يُحصى ويُعرض في التقرير
            rep["dropped"] = rep.get("dropped", 0) + 1
            if len(stats.dropped_samples) < 8:
                stats.dropped_samples.append(f"{it.title[:70]} — {it.link[:70]} ({name})")
        elif not m.matched:
            stats.items_rejected += 1
            rep["rejected"] += 1
            stats.reject_reasons[m.reason or matcher.R_NO_MATCH] += 1
            if m.reason in (matcher.R_AMBIGUOUS, matcher.R_NAME_NO_CONTEXT) and len(stats.reject_samples) < MAX_REJECT_SAMPLES:
                stats.reject_samples.append(f"[{matcher.REASON_LABELS[m.reason]}] {it.title[:90]} ({name})")
    for it, _m, _n in candidates:
        stats.found[dedup.fingerprint(it.title)].add((engine, idx))
    if via_pages:
        # نتذكّر كل صفحة فُحصت (حتى غير المطابقة) كي لا تُجلب من جديد كل دورة
        mark = getattr(store, "mark_seen", None)
        if mark:
            mark([dedup.normalize_url(i.link) for i in items if dedup.normalize_url(i.link)], sid)
    note = getattr(store, "note_candidate", None)
    if note and candidates:
        # اكتشاف من داخل الأخبار: ناشر الخبر (عنصر <source>) والمواقع المذكورة في نصه. لا يُسجَّل شيء هنا؛ الفحص والتحقق في مهمة الاكتشاف.
        own = _host_of(used_url)
        for host in validate.candidate_domains([c[0] for c in candidates], own, discover.domain_ok):
            if host in stats.noted_domains or host in stats.source_hosts or len(stats.noted_domains) >= MAX_NOTED_DOMAINS:
                continue
            stats.noted_domains.add(host)
            via_publisher = any(c[0].publisher_url and _host_of(c[0].publisher_url) == host for c in candidates)
            try:
                note(host, "search" if via_publisher else "news", via_publisher)
                stats.from_news_domains += 1
            except StoreError:
                pass
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
            "category": classify.classify(it.title, excerpt),
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
    if advance:
        store.source_result(sid, True, None)
    else:
        store.source_result(sid, True, None, advance=False)   # نجح الجلب لكن الفجوة لم تُستدرك كلها: last_success_at يبقى


def backfill_source(store, source: dict, keywords: list[matcher.Keyword], fetch=feeds.http_get, now: datetime | None = None, sleep=None) -> dict:
    """أثر رجعي لمصدر اكتُشف للتو: أخبار آخر BACKFILL_DAYS يوماً مما يعرضه المصدر. المكرر يُتجاهل بنفس مسار الجلب (رابط/بصمة/تشابه)."""
    st = Stats()
    st.source_hosts = set()
    try:
        process_source(store, dict(source, last_success_at=None), keywords, st, fetch, sleep=sleep, now=now, backfill=True)
    except StoreError as e:
        return {"error": str(e)[:150], "seen": st.items_seen, "matched": st.items_matched, "inserted": st.items_inserted, "duplicate": st.items_duplicate}
    rep = st.sources[0] if st.sources else {}
    return {"error": rep.get("error"), "seen": st.items_seen, "matched": st.items_matched, "inserted": st.items_inserted, "duplicate": st.items_duplicate}


def run(store, trigger: str = "schedule", fetch=feeds.http_get, pages: int = 1, sleep=None, now: datetime | None = None, pause=None) -> Stats:
    stats = Stats()
    run_id = store.start_run(trigger)
    now = now or datetime.now(timezone.utc)

    def counted(u: str) -> bytes:
        stats.requests += 1
        return fetch(u)

    try:
        # استعلامات البحث الأساسية تُضمن كل دورة (المكرر يُتجاهل) فلا تنتظر مهمة الاكتشاف الأسبوعية
        ensure = getattr(store, "upsert_sources", None)
        if ensure:
            try:
                ensure(queries.search_source_rows() + queries.extended_source_rows())
            except StoreError as e:
                stats.errors.append(f"تسجيل استعلامات البحث: {e}")
        sources = store.active_sources()
        keywords = [matcher.Keyword(k["word"], k["kind"], k.get("region_id"), bool(k.get("needs_context"))) for k in store.active_keywords()]
        stats.sources_total = len(sources)
        stats.source_hosts = {_host_of(s["url"]) for s in sources if s.get("origin") != "search"}
        if not keywords:
            stats.errors.append("لا توجد كلمات مفتاحية مفعّلة")
        # الموسّعة: الأقدم فحصاً أولاً (لم تُفحص قط = الأقدم)، ويُسمح بدفعة محدودة فقط في الدورة
        def _age(s):
            d = feeds.parse_date(s.get("last_success_at"), now)
            return d or datetime.min.replace(tzinfo=timezone.utc)
        ext_due = sorted([s for s in sources if queries.is_extended(s.get("name"))
                          and not (trigger == "schedule" and _age(s) > now - EXT_INTERVAL)], key=_age)[:EXT_PER_RUN]
        ext_ok = {s["id"] for s in ext_due}
        stats.ext_total = sum(1 for s in sources if queries.is_extended(s.get("name")))
        # محرك بحث يمنع robots.txt جلب مساره: نتوقف عنده كله من البداية (لا نتحايل ولا نعدّ كل استعلام فشلاً)
        checked: dict = {}
        for s in sources:
            if s.get("origin") == "search":
                eng = queries.engine_of(s["url"])
                if eng not in checked:
                    try:
                        checked[eng] = feeds.robots_allows(s["url"], counted)
                    except Exception:  # noqa: BLE001
                        checked[eng] = True
                    if not checked[eng]:
                        stats.engines_down.add(eng)
                        stats.engines_blocked.append(eng)
                        stats.health.append(f"{eng}: robots.txt لا يسمح بجلب نتائج البحث الآلي، فأُوقف هذا المحرك بالكامل (لا نتحايل على الحجب)")
        for s in sources:
            engine = queries.engine_of(s["url"])
            last = feeds.parse_date(s.get("last_success_at"), now)
            if s.get("origin") == "search":
                if engine in stats.engines_down:
                    stats.search_skipped += 1          # المحرك رفض/حجب في هذه الدورة: لا نواصل الطرق عليه
                    continue
                if queries.is_extended(s.get("name")):
                    if s["id"] not in ext_ok:
                        stats.search_skipped += 1
                        continue
                elif trigger == "schedule" and last and now - last < SEARCH_INTERVAL:
                    stats.search_skipped += 1
                    continue
                if pause:
                    pause(SEARCH_PAUSE)
            try:
                before = stats.sources_failed
                process_source(store, s, keywords, stats, counted, pages, sleep, now)
                if s.get("origin") == "search" and stats.sources_failed > before:
                    err = stats.sources[-1].get("error") or ""
                    refusal = ("robots", "HTTP 403", "HTTP 429") if engine == queries.ENGINE_GOOGLE else ("robots", "HTTP 403", "HTTP 429", "لم يُعثر على خلاصة")
                    if any(x in err for x in refusal):
                        stats.engines_down.add(engine)   # قاطع دائرة: نتوقف عن بقية استعلامات هذا المحرك في الدورة
                        stats.health.append(f"{engine}: حجب/رفض ({err[:60]}) — أُوقفت بقية استعلاماته في هذه الدورة")
            except Exception as e:  # noqa: BLE001 - مصدر واحد معطوب لا يوقف بقية المصادر أبداً
                stats.sources_failed += 1
                msg = f"خطأ غير متوقع ({type(e).__name__}): {str(e)[:120]}"
                stats.errors.append(f"{s.get('name')}: {msg}")
                if stats.sources and stats.sources[-1].get("name") == s.get("name"):
                    stats.sources[-1]["error"] = msg[:120]
                try:
                    store.source_result(s["id"], False, msg)
                    store.log_failure(s["id"], s.get("url"), msg)
                except Exception:  # noqa: BLE001
                    pass
        stats.finalize()
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
    catch = [s["name"] for s in stats.sources if s.get("catchup")]
    if catch:
        lines.append("- استدراك بعد انقطاع (صفحات أكثر): " + "، ".join(catch))
    via = [s["name"] for s in stats.sources if s.get("via")]
    if via:
        lines.append("- مصادر جُلبت عبر sitemap/صفحات (بلا RSS): " + "، ".join(via))
    if stats.items_dropped_link:
        lines.append(f"- ⚠ ذات صلة لكن بلا رابط https صالح (لم تُخزَّن): {stats.items_dropped_link}")
        lines += [f"  - {x}" for x in stats.dropped_samples]
    if stats.search_skipped or stats.catchup_deferred:
        lines.append(f"- تخطّي استعلامات بحث (فاصل {int(SEARCH_INTERVAL.total_seconds() // 60)} دقيقة أو محرك محجوب): {stats.search_skipped} | استدراك مؤجَّل لنفاد الميزانية: {stats.catchup_deferred} | طلبات الشبكة: {stats.requests}")
    if stats.ext_total:
        done = sum(1 for s in stats.sources if queries.is_extended(s["name"]))
        lines.append(f"- استعلامات البحث الموسّعة: {stats.ext_total} مسجّلة، فُحص منها في هذه الدورة {done} (دفعة دوّارة، كل استعلام مرة يومياً)")
    if stats.from_news_domains:
        lines.append(f"- مواقع مرشحة اكتُشفت من داخل الأخبار (تُفحص في مهمة الاكتشاف): {stats.from_news_domains}")
    if stats.health:
        lines += ["", "**صحة الخلاصات:**"] + [f"- {h}" for h in stats.health]
    if stats.engines:
        lines += ["", "**مساهمة كل محرك/مصدر (الفريد = خبر لم يجده غير هذا المحرك في هذا التشغيل):**", "",
                  "| المحرك | استعلامات | عناصر خام | مقبولة | أخبار مميزة | **فريدة** | جديدة بالقاعدة | فاشلة |", "|---|---|---|---|---|---|---|---|"]
        for e, d in sorted(stats.engines.items()):
            lines.append(f"| {e} | {d['sources']} | {d['seen']} | {d['matched']} | {d['stories']} | **{d['unique']}** | {d['inserted']} | {d['failed']} |")
    srch = [v for v in stats.variants if v["seen"] or v["error"]]
    if srch:
        lines += ["", "**قياس صيغ البحث (ضجيج = نسبة المرفوض من الخام؛ فريد = لا يجده استعلام آخر):**", "",
                  "| الاستعلام | خام | مقبول | ضجيج | فريد | جديد |", "|---|---|---|---|---|---|"]
        for v in sorted(srch, key=lambda v: (-v["unique"], -v["matched"]))[:40]:
            nz = "—" if v["noise"] is None else f"{int(v['noise'] * 100)}%"
            lines.append(f"| {v['name'].split(': ', 1)[-1][:50]} | {v['seen']} | {v['matched']} | {nz} | {v['unique']} | {v['inserted']} |" if not v["error"]
                         else f"| {v['name'].split(': ', 1)[-1][:50]} | ✗ {v['error'][:40]} | | | | |")
        dead = [v["name"].split(": ", 1)[-1] for v in srch if v["matched"] and not v["unique"] and not v["error"]]
        if dead:
            lines.append("")
            lines.append("- استعلامات مقبولة لكن بلا أي إضافة فريدة (مرشحة للدمج إن تكرر ذلك عدة تشغيلات): " + "، ".join(dead[:12]))
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
        import time
        stats = run(SupabaseStore(url, key), os.environ.get("FETCH_TRIGGER", "schedule"), pages=pages, pause=time.sleep)
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
