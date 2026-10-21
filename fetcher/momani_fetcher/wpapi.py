"""واجهة WordPress REST العامة (/wp-json/wp/v2/posts): ترقيم الصفحات وفلتر التاريخ (after) وبحث الموقع نفسه بالكلمات.
هذه واجهة معلنة للقراءة العامة؛ نقرأ منها العنوان والمقتطف والرابط والتاريخ ونص الخبر للمطابقة فقط. https وrobots محترمان."""
import json
from datetime import datetime, timedelta, timezone
from urllib.parse import quote, urlparse

from .feeds import FeedError, FeedItem, MAX_TEXT, parse_date, robots_allows, strip_html

PER_PAGE = 50
FIELDS = "link,date_gmt,title,excerpt,content"
MAX_SEARCH_TERMS = 8
MAX_LOOKBACK_DAYS = 30


def is_wp_api(url: str) -> bool:
    return "/wp-json/" in urlparse(url).path.lower()


def posts_url(root: str) -> str:
    p = urlparse(root)
    return f"{p.scheme}://{p.netloc}/wp-json/wp/v2/posts"


def parse_posts(data: bytes, now: datetime | None = None) -> list[FeedItem]:
    try:
        arr = json.loads(data.decode("utf-8", "replace"))
    except ValueError:
        raise FeedError("invalid JSON") from None
    if not isinstance(arr, list):
        raise FeedError("not a WordPress posts list")
    out: list[FeedItem] = []
    for p in arr:
        if not isinstance(p, dict):
            continue
        link = str(p.get("link") or "").strip()
        title = strip_html(str((p.get("title") or {}).get("rendered", "")))
        if not link or not title:
            continue
        excerpt = strip_html(str((p.get("excerpt") or {}).get("rendered", "")), 600)
        content = strip_html(str((p.get("content") or {}).get("rendered", "")), MAX_TEXT)
        when = p.get("date_gmt")
        out.append(FeedItem(title[:300], link, excerpt or content[:600], parse_date(f"{when}+00:00" if when else None, now), content))
    return out


def _page(fetch, url: str, now) -> list[FeedItem]:
    return parse_posts(fetch(url), now)


def collect(api_url: str, fetch, since: datetime | None, search_terms: list[str], max_pages: int = 3,
            now: datetime | None = None) -> list[FeedItem]:
    """كل المنشورات منذ [since] (أو آخر 30 يوماً كحد أقصى) بالترقيم، ثم نتائج بحث الموقع لكل كلمة (أقدم ومتخصصة)."""
    if not robots_allows(api_url, fetch):
        raise FeedError("robots.txt يمنع الجلب الآلي من هذا الموقع")
    now = now or datetime.now(timezone.utc)
    floor = now - timedelta(days=MAX_LOOKBACK_DAYS)
    after = max(since, floor) if since else floor
    base = f"{api_url}?per_page={PER_PAGE}&orderby=date&order=desc&_fields={FIELDS}"
    items: dict[str, FeedItem] = {}
    for page in range(1, max(1, max_pages) + 1):
        url = f"{base}&after={quote(after.strftime('%Y-%m-%dT%H:%M:%S'))}&page={page}"
        try:
            batch = _page(fetch, url, now)
        except FeedError:
            if page == 1:
                raise
            break  # تجاوز آخر صفحة (WordPress يرد 400)
        for it in batch:
            items.setdefault(it.link, it)
        if len(batch) < PER_PAGE:
            break
    for term in search_terms[:MAX_SEARCH_TERMS]:
        try:
            for it in _page(fetch, f"{base}&search={quote(term)}", now):
                items.setdefault(it.link, it)
        except FeedError:
            continue  # فشل بحث كلمة واحدة لا يُفشل المصدر
    return list(items.values())
