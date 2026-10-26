"""استخراج رابط صورة/فيديو الخبر من المصدر (يُخزَّن الرابط فقط؛ لا تُنسخ الملفات).
القواعد: https فقط، حد للطول، تجاهل بكسلات التتبع والشعارات والأيقونات وصور gif/svg، والفيديو MP4 فقط (ما يشغّله التطبيق)."""
import html
import re
from urllib.parse import urlparse

MAX_URL = 1000
_IMG = re.compile(r"<img\b[^>]*>", re.I)
_ATTR = re.compile(r"""([a-zA-Z:_-]+)\s*=\s*(?:"([^"]*)"|'([^']*)')""")
_BAD = re.compile(r"(pixel|1x1|spacer|blank\.|tracking|emoji|avatar|gravatar|logo|sprite|/ads?/|doubleclick|favicon|\.gif(\?|$)|\.svg(\?|$))", re.I)
_IMG_EXT = (".jpg", ".jpeg", ".png", ".webp")


def clean_url(u) -> str | None:
    u = html.unescape(str(u or "")).strip()
    if not u.lower().startswith("https://") or len(u) > MAX_URL or re.search(r"\s", u):
        return None
    return u if urlparse(u).netloc else None


def _path(u: str) -> str:
    return urlparse(u).path.lower()


def is_video_url(u: str, mtype: str = "") -> bool:
    return mtype.lower() == "video/mp4" or _path(u).endswith(".mp4")


def is_image_url(u: str, mtype: str = "", medium: str = "") -> bool:
    return mtype.lower().startswith("image/") and not mtype.lower().endswith(("gif", "svg+xml")) or medium.lower() == "image" or _path(u).endswith(_IMG_EXT)


def good_image(u) -> str | None:
    c = clean_url(u)
    return c if c and not _BAD.search(c) else None


def first_image(raw: str) -> str | None:
    """أول <img> صالحة في HTML خام (تتجاوز الصور الصغيرة جداً وبكسلات التتبع)."""
    for tag in _IMG.findall(html.unescape(raw or "")):
        a = {m.group(1).lower(): (m.group(2) if m.group(2) is not None else m.group(3)) for m in _ATTR.finditer(tag)}
        try:
            if any(int(re.sub(r"\D", "", a.get(k, "") or "") or 99) <= 2 for k in ("width", "height")):
                continue
        except ValueError:
            pass
        src = good_image(a.get("data-src") or a.get("data-lazy-src") or a.get("src"))
        if src:
            return src
    return None


def _local(tag: str) -> str:
    return tag.rsplit("}", 1)[-1].lower() if isinstance(tag, str) else ""


def from_element(node) -> tuple[str | None, str | None]:
    """(صورة، فيديو) من عنصر RSS/Atom: enclosure وmedia:content وmedia:thumbnail وروابط rel=enclosure."""
    image = video = None
    for c in node.iter():
        name = _local(c.tag)
        if name not in ("content", "thumbnail", "enclosure", "link"):
            continue
        url = c.get("url") or (c.get("href") if name == "link" and (c.get("rel") or "") == "enclosure" else None)
        u = clean_url(url)
        if not u:
            continue
        mtype, medium = c.get("type") or "", c.get("medium") or ""
        if is_video_url(u, mtype) or medium.lower() == "video":
            if not video and is_video_url(u, mtype):
                video = u
        elif not image and (name == "thumbnail" or is_image_url(u, mtype, medium)):
            image = good_image(u)
    return image, video
