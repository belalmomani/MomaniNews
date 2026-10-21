"""مصادر بلا RSS: sitemap وصفحات الأخبار. كل الطلبات https فقط، وتحترم robots.txt، وبحد أعلى لعدد الصفحات في الدورة.
لا يُخزَّن من الصفحة إلا العنوان والمقتطف والرابط وتاريخ النشر (النص الكامل للمطابقة فقط)."""
import gzip
import re
import time
from datetime import datetime, timezone
from urllib.parse import urljoin, urlparse
from xml.etree import ElementTree as ET

from . import dedup
from .feeds import FeedError, FeedItem, MAX_TEXT, looks_like_html, parse_date, robots_allows, strip_html

MAX_PAGES_PER_SOURCE = 25      # صفحات مقالات تُجلب لكل مصدر في الدورة الواحدة (الأحدث أولاً)
MAX_CHILD_SITEMAPS = 3
MAX_SITEMAP_URLS = 500
MAX_LISTING_LINKS = 40
MAX_GZ_BYTES = 10 * 1024 * 1024
PAUSE_SECONDS = 0.5            # مهلة بين صفحات الموقع الواحد (تخفيف الحمل)

_META = re.compile(r"<meta\b[^>]*>", re.I)
_ATTR = re.compile(r"""([a-zA-Z:_-]+)\s*=\s*("([^"]*)"|'([^']*)')""")
_TITLE = re.compile(r"<title[^>]*>(.*?)</title>", re.I | re.S)
_TIME = re.compile(r"<time\b[^>]*\bdatetime\s*=\s*[\"']([^\"']+)[\"']", re.I)
_PARA = re.compile(r"<p\b[^>]*>(.*?)</p>", re.I | re.S)
_DROP = re.compile(r"<(script|style|noscript|nav|footer|header|aside)\b.*?</\1>", re.I | re.S)
_HREF = re.compile(r"""<a\b[^>]*\bhref\s*=\s*("([^"]*)"|'([^']*)')""", re.I)
_CHARSET = re.compile(rb"charset\s*=\s*[\"']?([A-Za-z0-9_-]+)", re.I)
_SKIP_EXT = (".jpg", ".jpeg", ".png", ".gif", ".webp", ".pdf", ".mp4", ".css", ".js", ".xml", ".zip")


def is_sitemap_url(url: str) -> bool:
    p = urlparse(url).path.lower()
    return p.endswith((".xml", ".xml.gz")) or "sitemap" in p


def decode(data: bytes) -> str:
    m = _CHARSET.search(data[:4096])
    enc = (m.group(1).decode("ascii", "ignore").lower() if m else "utf-8")
    if enc in ("windows-1256", "cp1256"):
        enc = "cp1256"
    try:
        return data.decode(enc, "replace")
    except LookupError:
        return data.decode("utf-8", "replace")


def _meta_map(html: str) -> dict[str, str]:
    out: dict[str, str] = {}
    for tag in _META.findall(html[:200_000]):
        a = {m.group(1).lower(): (m.group(3) if m.group(3) is not None else m.group(4)) for m in _ATTR.finditer(tag)}
        key = (a.get("property") or a.get("name") or "").lower()
        if key and "content" in a and key not in out:
            out[key] = a["content"]
    return out


def extract_page(data: bytes, url: str, now: datetime | None = None) -> FeedItem | None:
    """عنوان + مقتطف + تاريخ + نص من صفحة مقال. None إن لم يوجد عنوان."""
    if not looks_like_html(data):
        return None
    html = decode(data)
    meta = _meta_map(html)
    title = strip_html(meta.get("og:title", ""))
    if not title:
        m = _TITLE.search(html)
        title = strip_html(m.group(1)) if m else ""
    if not title:
        return None
    desc = strip_html(meta.get("og:description") or meta.get("description") or "", 600)
    when = meta.get("article:published_time") or meta.get("og:article:published_time") or meta.get("date") or meta.get("pubdate")
    if not when:
        t = _TIME.search(html)
        when = t.group(1) if t else None
    body = _DROP.sub(" ", html)
    paras = [strip_html(p) for p in _PARA.findall(body)]
    text = " ".join(x for x in paras if len(x) > 30)[:MAX_TEXT]
    if not desc and text:
        desc = text[:600]
    return FeedItem(title[:300], url, desc, parse_date(when, now), text)


def parse_sitemap(data: bytes) -> tuple[list[str], list[dict]]:
    """(روابط خرائط فرعية، مداخل {loc, lastmod, title}) من sitemap أو sitemapindex أو news-sitemap."""
    if data[:2] == b"\x1f\x8b":
        try:
            data = gzip.decompress(data)[:MAX_GZ_BYTES]
        except OSError:
            raise FeedError("sitemap gzip غير صالح") from None
    if b"<!doctype" in data[:4096].lower() or b"<!entity" in data[:65536].lower():
        raise FeedError("DOCTYPE/ENTITY not allowed")
    try:
        root = ET.fromstring(data)
    except ET.ParseError:
        raise FeedError("invalid sitemap XML") from None
    kind = root.tag.rsplit("}", 1)[-1]
    if kind not in ("urlset", "sitemapindex"):
        raise FeedError("not a sitemap")
    children: list[str] = []
    entries: list[dict] = []
    for node in root:
        f = {c.tag.rsplit("}", 1)[-1]: c for c in node.iter()}
        loc = (f["loc"].text or "").strip() if "loc" in f else ""
        if not loc:
            continue
        lastmod = (f["lastmod"].text or "").strip() if "lastmod" in f else ""
        if not lastmod and "publication_date" in f:
            lastmod = (f["publication_date"].text or "").strip()
        if kind == "sitemapindex":
            children.append((loc, lastmod))
        else:
            entries.append({"loc": loc, "lastmod": lastmod, "title": (f["title"].text or "").strip() if "title" in f else ""})
    if kind == "sitemapindex":
        children.sort(key=lambda c: c[1], reverse=True)
        return [c[0] for c in children], []
    entries.sort(key=lambda e: e["lastmod"], reverse=True)
    return [], entries[:MAX_SITEMAP_URLS]


def same_site(url: str, base: str) -> bool:
    h, b = (urlparse(url).hostname or "").lower(), (urlparse(base).hostname or "").lower()
    return bool(h) and (h == b or h.endswith("." + b) or b.endswith("." + h))


def discover_sitemaps(base_url: str, fetch) -> list[str]:
    """روابط sitemap المعلنة في robots.txt ثم المسارات الشائعة (https ونفس الموقع فقط)."""
    p = urlparse(base_url)
    root = f"{p.scheme}://{p.netloc}"
    found: list[str] = []
    try:
        for line in fetch(root + "/robots.txt").decode("utf-8", "ignore").splitlines():
            if line.lower().startswith("sitemap:"):
                u = line.split(":", 1)[1].strip()
                if u.startswith("https://") and same_site(u, base_url) and u not in found:
                    found.append(u)
    except Exception:
        pass
    for path in ("/news-sitemap.xml", "/sitemap.xml", "/sitemap_index.xml", "/wp-sitemap.xml"):
        u = root + path
        if u not in found:
            found.append(u)
    return found[:6]


def extract_links(data: bytes, base_url: str) -> list[str]:
    """روابط المقالات المحتملة من صفحة قائمة: نفس الموقع، https، مسار بعمق ≥ 2 أو برقم، ليست ملفات."""
    html = decode(data)
    out: list[str] = []
    for m in _HREF.finditer(html[:600_000]):
        href = (m.group(2) if m.group(2) is not None else m.group(3)).strip()
        full = urljoin(base_url, href).split("#")[0]
        p = urlparse(full)
        if p.scheme != "https" or not same_site(full, base_url) or p.path.lower().endswith(_SKIP_EXT):
            continue
        segs = [s for s in p.path.split("/") if s]
        if (len(segs) >= 2 or any(ch.isdigit() for ch in p.path)) and full.rstrip("/") != base_url.rstrip("/") and full not in out:
            out.append(full)
    return out[:MAX_LISTING_LINKS]


def _fetch_entries(sitemap_url: str, fetch, depth: int = 0) -> list[dict]:
    if not robots_allows(sitemap_url, fetch):
        raise FeedError("robots.txt يمنع الجلب الآلي من هذا الموقع")
    children, entries = parse_sitemap(fetch(sitemap_url))
    if children and depth == 0:
        merged: list[dict] = []
        for child in children[:MAX_CHILD_SITEMAPS]:
            if same_site(child, sitemap_url):
                try:
                    merged += _fetch_entries(child, fetch, 1)
                except FeedError:
                    continue
        merged.sort(key=lambda e: e["lastmod"], reverse=True)
        return merged[:MAX_SITEMAP_URLS]
    return entries


def collect(store, source_url: str, fetch, now: datetime | None = None, sleep=time.sleep,
            known_seen=lambda urls: set()) -> tuple[list[FeedItem], str]:
    """يجلب عناصر من sitemap أو صفحة قائمة. يتجاوز الروابط المعروفة (في الأخبار أو مرئية سابقاً) ويحدّ عدد الصفحات."""
    if is_sitemap_url(source_url):
        entries = _fetch_entries(source_url, fetch)
        urls = [e["loc"] for e in entries if e["loc"].startswith("https://") and same_site(e["loc"], source_url)]
        mode = "sitemap"
    else:
        if not robots_allows(source_url, fetch):
            raise FeedError("robots.txt يمنع الجلب الآلي من هذا الموقع")
        urls = extract_links(fetch(source_url), source_url)
        mode = "page"
    if not urls:
        raise FeedError("لم يُعثر على روابط أخبار في المصدر")
    norm = {u: dedup.normalize_url(u) for u in urls}
    known = store.known_original_urls([n for n in norm.values() if n]) | known_seen([n for n in norm.values() if n])
    fresh = [u for u in urls if norm[u] and norm[u] not in known][:MAX_PAGES_PER_SOURCE]
    items: list[FeedItem] = []
    for i, u in enumerate(fresh):
        if i:
            sleep(PAUSE_SECONDS)
        try:
            if not robots_allows(u, fetch):
                continue
            it = extract_page(fetch(u), u, now)
        except FeedError:
            continue
        if it:
            items.append(it)
    return items, mode
