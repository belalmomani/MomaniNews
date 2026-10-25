"""تشخيص المصادر المباشرة المتعطلة من بيئة GitHub Actions: لكل مصدر نرى ماذا يردّ الموقع فعلاً (الحالة، robots، روابط خلاصة معلنة،
sitemap، WordPress API) ثم نقترح إجراءً محدداً. للتشخيص فقط: لا يكتب في القاعدة. يحترم robots.txt ويستخدم اسم MomaniNewsBot."""
import os
import sys
from urllib.parse import urlparse

from . import feeds, pages, wpapi

WP_TEST = "/wp-json/wp/v2/posts?per_page=1"


def _try(url: str, fetch) -> dict:
    out = {"url": url}
    try:
        out["robots_allows"] = feeds.robots_allows(url, fetch)
    except Exception as e:  # noqa: BLE001
        out["robots_allows"] = None
        out["robots_error"] = type(e).__name__
    if out["robots_allows"] is False:
        return out
    try:
        data = fetch(url)
    except feeds.FeedError as e:
        out["error"] = str(e)[:120]
        return out
    out["bytes"] = len(data)
    out["html"] = feeds.looks_like_html(data)
    out["json"] = data.lstrip()[:1] in (b"[", b"{")
    try:
        out["feed_items"] = len(feeds.parse_feed(data))
    except feeds.FeedError as e:
        out["parse_error"] = str(e)[:80]
    out["_data"] = data
    return out


def diagnose(source: dict, fetch=feeds.http_get) -> dict:
    """يفحص الصفحة الرئيسية ثم الخلاصات المعلنة وsitemap وWP API. الناتج قابل للطباعة ويحمل «suggestion»."""
    p = urlparse(source["url"])
    root = f"{p.scheme}://{p.netloc}/"
    rep = {"name": source["name"], "url": source["url"], "last_error": source.get("last_error"), "home": None, "feeds": [], "sitemaps": [], "wp": None}
    home = _try(root, fetch)
    rep["home"] = {k: v for k, v in home.items() if k != "_data"}
    if home.get("_data"):
        for u in feeds.discover_feed_urls(home["_data"], root)[:4]:
            r = _try(u, fetch)
            rep["feeds"].append({k: v for k, v in r.items() if k != "_data"})
    for u in pages.discover_sitemaps(root, fetch)[:3]:
        r = _try(u, fetch)
        entries = 0
        if r.get("_data"):
            try:
                links, nested = pages.parse_sitemap(r["_data"])
                entries = len(links) + len(nested)
            except Exception:  # noqa: BLE001
                entries = 0
        rep["sitemaps"].append({k: v for k, v in r.items() if k != "_data"} | {"entries": entries})
    w = _try(root.rstrip("/") + WP_TEST, fetch)
    rep["wp"] = {k: v for k, v in w.items() if k != "_data"}
    rep["suggestion"] = suggest(rep)
    return rep


def suggest(rep: dict) -> str:
    home = rep["home"] or {}
    if home.get("robots_allows") is False:
        return "robots.txt يمنع البوت: لا نتحايل. ابحث عن ترخيص/خلاصة رسمية أو احذف المصدر."
    if "error" in home:
        return f"الموقع لا يستجيب لنا ({home['error']}). غالباً حجب للبوت أو تعطل؛ لا حل من جانبنا."
    good_feed = next((f for f in rep["feeds"] if f.get("feed_items")), None)
    if good_feed:
        return f"✅ خلاصة تعمل: {good_feed['url']} ({good_feed['feed_items']} عنصر). حدّث رابط المصدر إليها."
    wp = rep.get("wp") or {}
    if wp.get("json") and "error" not in wp and wp.get("robots_allows") is not False:
        return f"WordPress API متاح: اجعل المصدر kind=html برابط الموقع {urlparse(rep['url']).scheme}://{urlparse(rep['url']).netloc}/ (المحرك يكتشفه تلقائياً)."
    sm = next((s for s in rep["sitemaps"] if s.get("entries")), None)
    if sm:
        return f"✅ sitemap يعمل: {sm['url']} ({sm['entries']} مدخلاً). اجعل المصدر kind=html برابط الموقع ليُجلب عبر الصفحات."
    return "لا خلاصة ولا sitemap ولا WP API متاحة لنا. الموقع غير قابل للجلب الآلي حالياً."


def report(reps: list[dict]) -> str:
    L = ["## تشخيص المصادر المباشرة المتعطلة", ""]
    for r in reps:
        L.append(f"### {r['name']} — `{r['url'][:80]}`")
        L.append(f"- خطأ الجلب الأخير: {r.get('last_error') or '—'}")
        h = r["home"] or {}
        L.append(f"- الصفحة الرئيسية: robots={h.get('robots_allows')} حجم={h.get('bytes', '—')} خطأ={h.get('error', '—')}")
        for f in r["feeds"]:
            L.append(f"- خلاصة مرشّحة `{f['url'][:80]}`: عناصر={f.get('feed_items', '—')} خطأ={f.get('error') or f.get('parse_error') or '—'}")
        for s in r["sitemaps"]:
            L.append(f"- sitemap `{s['url'][:80]}`: مدخلات={s.get('entries')} خطأ={s.get('error') or '—'}")
        L.append(f"- **الاقتراح:** {r['suggestion']}")
        L.append("")
    return "\n".join(L)


def main() -> int:
    from .store import SupabaseStore
    url, key = os.environ.get("SUPABASE_URL", ""), os.environ.get("SUPABASE_SERVICE_ROLE_KEY", "")
    if not (url and key):
        print("ناقص SUPABASE_URL أو SUPABASE_SERVICE_ROLE_KEY")
        return 2
    srcs = SupabaseStore(url, key).failing_sources()
    reps = []
    for s in srcs:
        if not str(s.get("url", "")).startswith("https://"):
            continue
        reps.append(diagnose(s))
    text = report(reps) if reps else "لا مصادر مباشرة متعطلة حالياً."
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
