"""اكتشاف المصادر دورياً (مهمة مستقلة عن الجلب). كل نطاق مرشح يُفحص مرة ثم تُحفظ نتيجته في source_candidates،
فلا يُعاد فحصه قبل موعده. لا يُسجَّل مصدر إلا بعد: السماح بالجلب (robots) + خلاصة/واجهة/sitemap صالحة وحديثة + صلة بالمناطق أو العشيرة.
كل ما يُجلب بعد ذلك يبقى بانتظار اعتماد المدير."""
import json
import os
import sys
from datetime import datetime, timedelta, timezone
from urllib.parse import urlparse

from . import feeds, matcher, pages as pagemod, queries, wpapi
from .store import StoreError, SupabaseStore

MAX_PROBES = 40          # نطاقات تُفحص في التشغيل الواحد
MAX_NEW_SOURCES = 10     # مصادر جديدة تُسجَّل كحد أقصى في التشغيل الواحد (ضد الإغراق)
MIN_ITEMS = 3            # أقل عدد عناصر ليُعدّ المصدر حيّاً
MAX_AGE_DAYS = 120       # أحدث عنصر أقدم من هذا = مصدر ميت
STALE_AFTER_DAYS = 14    # مصدر آلي فاشل بلا نجاح منذ هذه المدة يُعطَّل
RECHECK_DAYS = {"rejected": 30, "blocked": 90, "stale": 30}
SKIP_DOMAINS = ("google.", "facebook.com", "twitter.com", "x.com", "instagram.com", "youtube.com", "t.me", "tiktok.com",
                "whatsapp.com", "linkedin.com", "wikipedia.org", "bing.com")


def domain_ok(domain: str) -> bool:
    if not domain or "." not in domain:
        return False
    for d in SKIP_DOMAINS:
        if d.endswith("."):
            if d in domain + ".":
                return False
        elif domain == d or domain.endswith("." + d):
            return False
    return True


def _alive(dates: list[datetime | None], now: datetime) -> bool:
    known = [d for d in dates if d]
    return not known or max(known) >= now - timedelta(days=MAX_AGE_DAYS)


def probe_domain(domain: str, fetch, keywords: list[matcher.Keyword], via_search: bool, now: datetime) -> dict:
    """يُرجع {status, reason, kind, url, hits}. status: registered(=صالح) | rejected | blocked."""
    root = f"https://{domain}/"
    try:
        if not feeds.robots_allows(root, fetch):
            return {"status": "blocked", "reason": "robots.txt يمنع الجلب الآلي"}
    except Exception:
        pass
    found: list[tuple[str, str, list[feeds.FeedItem]]] = []  # (kind, url, items)
    # 1) RSS/Atom (اكتشاف تلقائي من الصفحة الرئيسية ثم المسارات الشائعة)
    try:
        items, used = feeds.load_items(root, fetch)
        found.append(("rss", used, items))
    except feeds.FeedError as e:
        if "robots" in str(e):
            return {"status": "blocked", "reason": str(e)[:200]}
    # 2) واجهة WordPress العامة
    if not found:
        api = wpapi.posts_url(root)
        try:
            found.append(("html", api, wpapi.parse_posts(fetch(f"{api}?per_page=20&_fields={wpapi.FIELDS}"), now)))
        except feeds.FeedError:
            pass
    # 3) sitemap (يُتحقق من وجود روابط حديثة فقط)
    if not found:
        for sm in pagemod.discover_sitemaps(root, fetch):
            try:
                children, entries = pagemod.parse_sitemap(fetch(sm))
                if children:
                    children, entries = pagemod.parse_sitemap(fetch(children[0]))
                recent = [e for e in entries if e["lastmod"]]
                if len(entries) >= MIN_ITEMS:
                    cutoff = (now - timedelta(days=MAX_AGE_DAYS)).strftime("%Y-%m-%d")
                    if not recent or max(e["lastmod"] for e in recent)[:10] >= cutoff:
                        found.append(("html", sm, []))
                        break
            except feeds.FeedError:
                continue
    if not found:
        return {"status": "rejected", "reason": "لا خلاصة RSS ولا واجهة WordPress ولا sitemap صالح"}
    kind, url, items = found[0]
    if kind == "rss" or items:
        if len(items) < MIN_ITEMS:
            return {"status": "rejected", "reason": f"عناصر قليلة ({len(items)})"}
        if not _alive([i.published for i in items], now):
            return {"status": "rejected", "reason": f"لا نشر منذ {MAX_AGE_DAYS} يوماً"}
    hits = sum(1 for i in items if matcher.evaluate(i.title, i.summary, keywords, i.text).matched)
    if hits == 0 and not via_search:
        return {"status": "rejected", "reason": "صالح تقنياً لكن لا خبر فيه يخص المناطق أو العشيرة", "kind": kind, "url": url}
    return {"status": "registered", "reason": f"صالح؛ أخبار ذات صلة في العينة: {hits}" + ("؛ ظهر في نتائج البحث" if via_search else ""),
            "kind": kind, "url": url, "hits": hits}


def run_discovery(store, fetch=feeds.http_get, now: datetime | None = None, backfill=None) -> dict:
    now = now or datetime.now(timezone.utc)
    rep = {"probed": 0, "registered": [], "rejected": [], "blocked": [], "disabled": [], "search_sources": 0, "backfilled": []}
    keywords = [matcher.Keyword(k["word"], k["kind"], k.get("region_id"), bool(k.get("needs_context"))) for k in store.active_keywords()]
    # 1) استعلامات البحث (تُسجَّل مرة؛ المكرر يُتجاهل)
    store.upsert_sources(queries.search_source_rows())
    rep["search_sources"] = len(queries.QUERIES)
    # 2) نطاقات مرشحة من الأخبار المخزّنة
    for d in sorted(store.news_domains()):
        if domain_ok(d):
            store.note_candidate(d, "news", False)
    have = store.source_domains()
    new_sources = 0
    for c in store.candidates_due(MAX_PROBES):
        d = c["domain"]
        if d in have or not domain_ok(d):
            store.update_candidate(d, {"status": "registered" if d in have else "rejected", "reason": "مسجَّل أصلاً" if d in have else "نطاق مستبعد",
                                      "checked_at": now.isoformat(), "next_check_at": (now + timedelta(days=RECHECK_DAYS["rejected"])).isoformat()})
            continue
        res = probe_domain(d, fetch, keywords, bool(c.get("relevant")), now)
        rep["probed"] += 1
        status, fields = res["status"], {"checked_at": now.isoformat(), "reason": res["reason"][:300]}
        if status == "registered":
            if new_sources >= MAX_NEW_SOURCES:
                continue  # يبقى «جديداً» فيُفحص في التشغيل القادم (لا نتجاوز سقف الإغراق)
            sid = store.add_source({"name": d[:120], "url": res["url"], "kind": res["kind"], "origin": "auto",
                                    "discovered_at": now.isoformat(), "relevance_hits": res.get("hits", 0)})
            new_sources += 1
            have.add(d)
            rep["registered"].append(f"{d} ({res['kind']})")
            if backfill and sid:
                # أثر رجعي: لا نبدأ من اللحظة فقط؛ نسجّل أخبار آخر 30 يوماً مما يعرضه المصدر (المكرر يُتجاهل)
                bf = backfill(store, {"id": sid, "name": d[:120], "url": res["url"], "kind": res["kind"], "origin": "auto"}, keywords, fetch, now)
                rep["backfilled"].append(f"{d}: فُحص {bf['seen']}، ذو صلة {bf['matched']}، جديد {bf['inserted']}، مكرر {bf['duplicate']}" + (f" ⚠ {bf['error']}" if bf.get("error") else ""))
            fields.update(status="registered", source_id=sid, next_check_at=(now + timedelta(days=3650)).isoformat())
        else:
            rep[status].append(f"{d}: {res['reason']}")
            fields.update(status=status, next_check_at=(now + timedelta(days=RECHECK_DAYS[status])).isoformat())
        store.update_candidate(d, fields)
    # 3) مراجعة دورية: تعطيل المصادر الآلية الميتة (اليدوية لا تُمس)
    cutoff = (now - timedelta(days=STALE_AFTER_DAYS)).isoformat()
    for s in store.stale_auto_sources(cutoff):
        store.set_source_active(s["id"], False)
        host = (urlparse(s["url"]).hostname or "").lower().removeprefix("www.")
        try:
            store.update_candidate(host, {"status": "stale", "reason": f"لا نجاح منذ {STALE_AFTER_DAYS} يوماً", "next_check_at": (now + timedelta(days=RECHECK_DAYS["stale"])).isoformat()})
        except StoreError:
            pass
        rep["disabled"].append(s.get("name") or s["url"])
    return rep


def report(rep: dict) -> str:
    L = ["## تقرير اكتشاف المصادر", ""]
    L.append(f"- استعلامات بحث مسجّلة (Google News): {rep['search_sources']}")
    L.append(f"- نطاقات فُحصت: {rep['probed']} | سُجّل: {len(rep['registered'])} | رُفض: {len(rep['rejected'])} | محجوب: {len(rep['blocked'])} | عُطّل لموته: {len(rep['disabled'])}")
    for title, key in (("مصادر جديدة مسجَّلة", "registered"), ("أثر رجعي للمصادر الجديدة", "backfilled"), ("مرفوضة (تُعاد بعد 30 يوماً)", "rejected"), ("محجوبة (robots)", "blocked"), ("عُطّلت", "disabled")):
        if rep[key]:
            L += ["", f"**{title}:**"] + [f"- {x}" for x in rep[key]]
    if not rep["registered"]:
        L.append("- ✅ التشغيل نجح؛ لم تُسجَّل مصادر جديدة في هذه الدورة")
    return "\n".join(L) + "\n"


def main() -> int:
    url, key = os.environ.get("SUPABASE_URL", ""), os.environ.get("SUPABASE_SERVICE_ROLE_KEY", "")
    if not url or not key:
        print("SUPABASE_URL / SUPABASE_SERVICE_ROLE_KEY غير مضبوطين.", file=sys.stderr)
        return 2
    try:
        from . import run as runmod
        rep = run_discovery(SupabaseStore(url, key), backfill=runmod.backfill_source)
    except StoreError as e:
        print(f"فشل: {e}. هل طُبّق الترحيل 20261021120000_source_discovery.sql؟", file=sys.stderr)
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
