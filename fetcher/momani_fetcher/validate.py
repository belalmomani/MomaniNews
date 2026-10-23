"""التحقق الحقيقي من الروابط والخلاصات: نجاح استجابة الخادم (HTTP 200) لا يعني أن الرابط سليم.
نكشف: إعادة التوجيه إلى الصفحة الرئيسية، صفحات «غير موجود» بحالة 200 (soft-404)، محتوى ليس HTML،
صفحة فارغة/قصيرة، صفحة تغيّر محتواها عن الخبر المسجَّل، وخلاصات فارغة أو متوقفة أو بروابط مكسورة البنية."""
import urllib.error
import urllib.request
from dataclasses import dataclass
from datetime import datetime, timedelta, timezone
from urllib.parse import urlparse

from . import feeds, normalize, pages

OK, BROKEN, CHANGED, UNREACHABLE, BLOCKED = "ok", "broken", "changed", "unreachable", "blocked"
MIN_BODY_CHARS = 200          # نص أقل من هذا في صفحة خبر = صفحة فارغة/هيكل بلا محتوى
MIN_TITLE_OVERLAP = 0.4       # أقل تشابه بين عنوان الخبر المسجَّل وعنوان الصفحة الحالية
STALE_FEED_DAYS = 365

# عبارات صفحات الخطأ التي تُرسَل بحالة 200 (يُفحص العنوان ومطلع الصفحة فقط، لا كل النص)
SOFT_404 = ("404", "not found", "page not found", "الصفحة غير موجودة", "الصفحه غير موجوده", "الصفحة المطلوبة غير موجودة",
            "لم يتم العثور", "لا يمكن العثور", "المحتوى غير متوفر", "تم حذف", "هذه الصفحة غير متاحة", "error 404", "oops")


@dataclass
class Probe:
    status: int
    final_url: str
    content_type: str
    data: bytes


def http_probe(url: str) -> Probe:
    """طلب GET واحد (https فقط) يُرجع الحالة والرابط النهائي بعد التحويلات ونوع المحتوى. أخطاء HTTP تُرجع كـ Probe لا كاستثناء."""
    if not url.lower().startswith("https://"):
        raise feeds.FeedError("only https")
    opener = urllib.request.build_opener(feeds._HttpsOnlyRedirect)
    req = urllib.request.Request(feeds.safe_url(url), headers={"User-Agent": feeds.USER_AGENT, "Accept": "text/html,application/xhtml+xml,*/*;q=0.5"})
    try:
        with opener.open(req, timeout=feeds.TIMEOUT) as r:
            return Probe(r.status, r.geturl(), r.headers.get("Content-Type", ""), r.read(feeds.MAX_BYTES))
    except urllib.error.HTTPError as e:
        return Probe(e.code, url, e.headers.get("Content-Type", "") if e.headers else "", b"")
    except (urllib.error.URLError, TimeoutError, OSError, ValueError) as e:
        raise feeds.FeedError(f"connection failed: {type(e).__name__}") from None


def _host(u: str) -> str:
    return (urlparse(u).hostname or "").lower().removeprefix("www.")


def _is_root(u: str) -> bool:
    p = urlparse(u)
    return p.path in ("", "/") and not p.query


def title_overlap(a: str, b: str) -> float:
    """نسبة كلمات العنوان المسجَّل (أطول من حرفين) الموجودة في عنوان الصفحة/نصها."""
    ta = [t for t in normalize.tokens(a) if len(t) > 2]
    if not ta:
        return 1.0
    tb = set(normalize.tokens(b))
    return sum(1 for t in ta if t in tb) / len(ta)


def check_article(requested_url: str, expected_title: str, probe: Probe, now: datetime | None = None) -> tuple[str, str]:
    """يُرجع (الحالة، السبب). الحالة: ok | broken | changed | unreachable."""
    now = now or datetime.now(timezone.utc)
    if probe.status in (404, 410):
        return BROKEN, f"HTTP {probe.status}: الرابط محذوف"
    if probe.status >= 500 or probe.status in (408, 429):
        return UNREACHABLE, f"HTTP {probe.status}: مشكلة مؤقتة في الخادم"
    if probe.status >= 400:
        return BROKEN, f"HTTP {probe.status}"
    # نجاح من الخادم، لكن هل الصفحة هي الخبر فعلاً؟
    if _is_root(probe.final_url) and not _is_root(requested_url):
        return BROKEN, "حُوِّل إلى الصفحة الرئيسية (الخبر محذوف أو انتقل)"
    if _host(probe.final_url) != _host(requested_url):
        return CHANGED, f"حُوِّل إلى موقع آخر ({_host(probe.final_url)})"
    ctype = probe.content_type.lower()
    if ctype and "html" not in ctype and "xml" not in ctype:
        return BROKEN, f"المحتوى ليس صفحة ويب ({ctype.split(';')[0]})"
    if not probe.data.strip():
        return BROKEN, "استجابة فارغة"
    try:
        page = pages.extract_page(probe.data, probe.final_url, now)
    except Exception:
        return BROKEN, "تعذّر تحليل الصفحة"
    if page is None:
        return BROKEN, "الصفحة بلا عنوان أو ليست HTML"
    head = normalize.normalize(page.title)
    for marker in SOFT_404:
        m = normalize.normalize(marker)
        if head == m or head.startswith(m + " ") or (len(m) > 6 and m in head):
            return BROKEN, "صفحة «غير موجود» بحالة نجاح (soft-404)"
    if len(page.text) < MIN_BODY_CHARS and len(page.summary) < 60:
        return BROKEN, "صفحة فارغة أو بلا محتوى خبري"
    if expected_title and title_overlap(expected_title, page.title + " " + page.text[:400]) < MIN_TITLE_OVERLAP:
        return CHANGED, "محتوى الصفحة لا يطابق الخبر المسجَّل (تغيّر المحتوى أو أُعيد استخدام الرابط)"
    return OK, "سليم"


def check_link(requested_url: str, expected_title: str, probe_fn=http_probe, robots_fetch=feeds.http_get, now: datetime | None = None) -> tuple[str, str]:
    try:
        if not feeds.robots_allows(requested_url, robots_fetch):
            return BLOCKED, "robots.txt يمنع الفحص الآلي (الرابط لم يُفحص)"
    except Exception:
        pass
    try:
        probe = probe_fn(requested_url)
    except feeds.FeedError as e:
        return UNREACHABLE, str(e)[:150]
    return check_article(requested_url, expected_title, probe, now)


def check_feed_items(items: list, now: datetime, source_url: str = "") -> tuple[str, str] | None:
    """يفحص خلاصة حُلِّلت بنجاح. None = سليمة؛ وإلا (رمز، سبب). صحة XML وحدها لا تكفي."""
    if not items:
        return "empty", "الخلاصة صالحة تقنياً لكنها بلا عناصر (غالباً تغيّرت بنية الموقع أو أُوقفت الخلاصة)"
    links = [i.link for i in items]
    if len(set(links)) == 1 and len(links) > 1:
        return "same_link", "كل العناصر بالرابط نفسه (خلاصة معطوبة)"
    if all(_is_root(l) for l in links):
        return "root_links", "كل الروابط تشير إلى الصفحة الرئيسية (خلاصة معطوبة)"
    dates = [i.published for i in items if i.published]
    if dates and max(dates) < now - timedelta(days=STALE_FEED_DAYS):
        return "stale", f"آخر عنصر أقدم من {STALE_FEED_DAYS} يوماً (الخلاصة متوقفة)"
    return None


def candidate_domains(items: list, own_host: str, skip) -> list[str]:
    """نطاقات الناشرين والمواقع المذكورة داخل أخبار مطابقة (ناشر <source> أولاً ثم روابط النص). لا يُسجَّل أي مصدر هنا؛ الفحص في مهمة الاكتشاف."""
    out: list[str] = []
    for it in items:
        urls = ([it.publisher_url] if it.publisher_url else []) + list(it.links)
        for u in urls:
            h = _host(u)
            if h and h != own_host and "." in h and h not in out and skip(h):
                out.append(h)
    return out
