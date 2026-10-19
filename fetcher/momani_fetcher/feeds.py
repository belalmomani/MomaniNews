"""جلب وتحليل RSS/Atom بأمان: https فقط، حد للحجم والوقت، رفض DOCTYPE/ENTITY (هجمات توسيع الكيانات)، وتجريد HTML."""
import html
import re
import urllib.request
import urllib.error
from dataclasses import dataclass
from datetime import datetime, timedelta, timezone
from email.utils import parsedate_to_datetime
from xml.etree import ElementTree as ET

MAX_BYTES = 2 * 1024 * 1024
TIMEOUT = 20
USER_AGENT = "MomaniNewsBot/1.0 (news aggregator; headline+link only)"
_TAGS = re.compile(r"<[^>]+>")
_SPACES = re.compile(r"\s+")


@dataclass
class FeedItem:
    title: str
    link: str
    summary: str
    published: datetime | None


class FeedError(Exception):
    pass


def strip_html(text: str, limit: int | None = None) -> str:
    t = html.unescape(_TAGS.sub(" ", text or ""))
    t = _SPACES.sub(" ", t).strip()
    if limit and len(t) > limit:
        t = t[: limit - 1].rstrip() + "…"
    return t


class _HttpsOnlyRedirect(urllib.request.HTTPRedirectHandler):
    def redirect_request(self, req, fp, code, msg, headers, newurl):
        if not newurl.lower().startswith("https://"):
            raise FeedError("redirect to non-https refused")
        return super().redirect_request(req, fp, code, msg, headers, newurl)


def http_get(url: str) -> bytes:
    if not url.lower().startswith("https://"):
        raise FeedError("only https sources are allowed")
    opener = urllib.request.build_opener(_HttpsOnlyRedirect)
    req = urllib.request.Request(url, headers={"User-Agent": USER_AGENT, "Accept": "application/rss+xml, application/atom+xml, application/xml, text/xml;q=0.9"})
    try:
        with opener.open(req, timeout=TIMEOUT) as r:
            data = r.read(MAX_BYTES + 1)
    except urllib.error.HTTPError as e:
        raise FeedError(f"HTTP {e.code}") from None
    except (urllib.error.URLError, TimeoutError, OSError) as e:
        raise FeedError(f"connection failed: {type(e).__name__}") from None
    if len(data) > MAX_BYTES:
        raise FeedError("response too large")
    return data


def parse_date(value: str | None, now: datetime | None = None) -> datetime | None:
    """يقبل RFC822 وISO8601. يتجاهل التواريخ المستقبلية البعيدة (> يوم) لأنها خاطئة غالباً."""
    if not value:
        return None
    value = value.strip()
    dt = None
    try:
        dt = parsedate_to_datetime(value)
    except (TypeError, ValueError, IndexError):
        try:
            dt = datetime.fromisoformat(value.replace("Z", "+00:00"))
        except ValueError:
            return None
    if dt is None:
        return None
    if dt.tzinfo is None:
        dt = dt.replace(tzinfo=timezone.utc)
    now = now or datetime.now(timezone.utc)
    if dt > now + timedelta(days=1):
        return None
    return dt.astimezone(timezone.utc)


def _local(tag: str) -> str:
    return tag.rsplit("}", 1)[-1]


def _text(el: ET.Element | None) -> str:
    return (el.text or "").strip() if el is not None else ""


def parse_feed(data: bytes, now: datetime | None = None) -> list[FeedItem]:
    head = data[:4096].lower()
    if b"<!doctype" in head or b"<!entity" in data[:65536].lower():
        raise FeedError("DOCTYPE/ENTITY not allowed")
    try:
        root = ET.fromstring(data)
    except ET.ParseError:
        raise FeedError("invalid XML") from None
    kind = _local(root.tag)
    items: list[FeedItem] = []
    if kind == "rss" or kind == "RDF":
        nodes = [e for e in root.iter() if _local(e.tag) == "item"]
        for n in nodes:
            f = {_local(c.tag): c for c in n}
            link = _text(f.get("link")) or _text(f.get("guid"))
            summary = _text(f.get("description")) or _text(f.get("encoded"))
            items.append(FeedItem(strip_html(_text(f.get("title"))), link.strip(), strip_html(summary, 600),
                                  parse_date(_text(f.get("pubDate")) or _text(f.get("date")), now)))
    elif kind == "feed":
        for n in [e for e in root if _local(e.tag) == "entry"]:
            link = ""
            for c in n:
                if _local(c.tag) == "link" and c.get("rel", "alternate") == "alternate":
                    link = c.get("href", "")
                    break
            f = {_local(c.tag): c for c in n}
            summary = _text(f.get("summary")) or _text(f.get("content"))
            items.append(FeedItem(strip_html(_text(f.get("title"))), link.strip(), strip_html(summary, 600),
                                  parse_date(_text(f.get("published")) or _text(f.get("updated")), now)))
    else:
        raise FeedError("not an RSS/Atom feed")
    return [i for i in items if i.title and i.link]


# ---- اكتشاف رابط الخلاصة من الصفحة الرئيسية (للمصدر الذي أُدخل رابط موقعه بدل رابط الخلاصة)
import re as _re
from urllib.parse import urljoin as _urljoin, urlparse as _urlparse

COMMON_FEED_PATHS = ("/feed", "/feed/", "/rss", "/rss.xml", "/feed.xml", "/rss/", "/feeds")
_LINK_TAG = _re.compile(r"<link\b[^>]*>", _re.I)
_ATTR = _re.compile(r"""([a-zA-Z:-]+)\s*=\s*("([^"]*)"|'([^']*)')""")
MAX_DISCOVERY_TRIES = 6


def looks_like_html(data: bytes) -> bool:
    head = data[:4096].lower()
    return b"<!doctype html" in head or b"<html" in head


def discover_feed_urls(html: bytes, base_url: str) -> list[str]:
    """روابط الخلاصات المعلنة في <link rel=alternate> ثم المسارات الشائعة؛ https ونفس الموقع فقط."""
    text = html[:300_000].decode("utf-8", "ignore")
    base = _urlparse(base_url)
    found: list[str] = []

    def add(u: str) -> None:
        full = _urljoin(base_url, u.strip())
        p = _urlparse(full)
        if p.scheme != "https" or not p.hostname:
            return
        host, bhost = p.hostname.lower(), (base.hostname or "").lower()
        if not (host == bhost or host.endswith("." + bhost) or bhost.endswith("." + host)):
            return
        if full not in found and full.rstrip("/") != base_url.rstrip("/"):
            found.append(full)

    for tag in _LINK_TAG.findall(text):
        attrs = {m.group(1).lower(): (m.group(3) if m.group(3) is not None else m.group(4)) for m in _ATTR.finditer(tag)}
        if "alternate" in (attrs.get("rel") or "").lower() and (attrs.get("type") or "").lower() in (
            "application/rss+xml", "application/atom+xml", "application/xml", "text/xml"
        ) and attrs.get("href"):
            add(attrs["href"])
    for path in COMMON_FEED_PATHS:
        add(path)
    return found[:MAX_DISCOVERY_TRIES]


_ROBOTS_CACHE: dict = {}
BOT_NAME = "MomaniNewsBot"


def robots_allows(url: str, fetch) -> bool:
    """يحترم robots.txt للموقع. تعذّر قراءته = مسموح (الأصل في الخلاصات أنها للقراءة الآلية)."""
    import urllib.robotparser as rp
    p = _urlparse(url)
    key = f"{p.scheme}://{p.netloc}"
    if key not in _ROBOTS_CACHE:
        parser = rp.RobotFileParser()
        try:
            parser.parse(fetch(key + "/robots.txt").decode("utf-8", "ignore").splitlines())
            _ROBOTS_CACHE[key] = parser
        except Exception:
            _ROBOTS_CACHE[key] = None
    parser = _ROBOTS_CACHE[key]
    return True if parser is None else parser.can_fetch(BOT_NAME, url)


def _guarded(fetch, url: str) -> bytes:
    if not robots_allows(url, fetch):
        raise FeedError("robots.txt يمنع الجلب الآلي من هذا الموقع")
    return fetch(url)


def load_items(url: str, fetch=None, now: datetime | None = None):
    """يُرجع (العناصر، الرابط المستخدم). إن كان الرابط صفحة HTML يبحث عن خلاصتها تلقائياً."""
    fetch = fetch or http_get
    data = _guarded(fetch, url)
    try:
        return parse_feed(data, now), url
    except FeedError:
        if not looks_like_html(data):
            raise
        for cand in discover_feed_urls(data, url):
            try:
                return parse_feed(_guarded(fetch, cand), now), cand
            except FeedError:
                continue
        raise FeedError("لم يُعثر على خلاصة RSS في هذه الصفحة (أدخل رابط الخلاصة مباشرة)") from None
