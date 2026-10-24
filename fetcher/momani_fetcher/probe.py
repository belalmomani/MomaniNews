"""تشخيص محركات البحث من نفس بيئة GitHub Actions: ماذا يردّ Google/Bing على MomaniNewsBot فعلاً؟ (للتشخيص فقط؛ لا يكتب في القاعدة)."""
import os
import sys

from . import feeds, matcher, queries, validate

SAMPLES = ["عبين", "عبلين عجلون", "عشيرة المومني"]


def probe_url(url: str, keywords=(), probe_fn=validate.http_probe, robots_fetch=feeds.http_get) -> dict:
    out = {"url": url, "engine": queries.engine_of(url)}
    try:
        out["robots_allows"] = feeds.robots_allows(url, robots_fetch)
    except Exception as e:  # noqa: BLE001
        out["robots_allows"] = f"تعذّر: {type(e).__name__}"
    try:
        p = probe_fn(url)
    except feeds.FeedError as e:
        out["error"] = str(e)
        return out
    out.update(status=p.status, final_url=p.final_url, content_type=p.content_type, bytes=len(p.data), head=p.data[:200].decode("utf-8", "replace").replace("\n", " "))
    try:
        items = feeds.parse_feed(p.data)
        out["items"] = len(items)
        out["matched"] = sum(1 for i in items if matcher.evaluate(i.title, i.summary, list(keywords), i.text).matched)
        out["titles"] = [i.title[:80] for i in items[:3]]
    except feeds.FeedError as e:
        out["parse_error"] = str(e)
    return out


def report(rows: list[dict]) -> str:
    L = ["## تشخيص محركات البحث", ""]
    for r in rows:
        L.append(f"### {r['engine']}: `{r['url'][:90]}`")
        for k, label in (("robots_allows", "robots.txt يسمح"), ("status", "حالة HTTP"), ("final_url", "الرابط النهائي"), ("content_type", "نوع المحتوى"),
                         ("bytes", "الحجم (بايت)"), ("items", "عناصر الخلاصة"), ("matched", "منها مقبولة"), ("error", "خطأ اتصال"), ("parse_error", "خطأ تحليل")):
            if k in r:
                L.append(f"- {label}: {r[k]}")
        if r.get("titles"):
            L.append("- عيّنة عناوين: " + " | ".join(r["titles"]))
        if r.get("items", 1) == 0 or "parse_error" in r:
            L.append(f"- بداية الاستجابة: `{r.get('head', '')}`")
        L.append("")
    return "\n".join(L)


def main() -> int:
    from .store import SupabaseStore
    kws = []
    url, key = os.environ.get("SUPABASE_URL", ""), os.environ.get("SUPABASE_SERVICE_ROLE_KEY", "")
    if url and key:
        try:
            kws = [matcher.Keyword(k["word"], k["kind"], k.get("region_id"), bool(k.get("needs_context"))) for k in SupabaseStore(url, key).active_keywords()]
        except Exception:  # noqa: BLE001
            pass
    rows = []
    for q in SAMPLES:
        rows.append(probe_url(queries.google_news_url(q), kws))
    rows.append(probe_url(queries.bing_news_url(SAMPLES[0]), kws))
    text = report(rows)
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
