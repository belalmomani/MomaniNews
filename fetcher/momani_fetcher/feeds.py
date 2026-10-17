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
