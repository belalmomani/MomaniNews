"""منع التكرار: تطبيع الرابط، بصمة العنوان، وتشابه العناوين (دون حذف خبرين مختلفين لمجرد تقارب عنوانيهما)."""
import hashlib
from urllib.parse import parse_qsl, urlencode, urlsplit, urlunsplit

from .normalize import normalize

_TRACKING = {"fbclid", "gclid", "igshid", "mc_cid", "mc_eid", "ref", "ref_src", "spm", "yclid", "_ga"}


def normalize_url(url: str) -> str | None:
    """رابط https مطبَّع، أو None إن لم يكن https صالحاً. يزيل المعلمات التتبعية والـ fragment والشرطة الأخيرة وwww."""
    try:
        p = urlsplit(url.strip())
    except ValueError:
        return None
    if p.scheme.lower() != "https" or not p.hostname:
        return None
    host = p.hostname.lower().removeprefix("www.")
    port = f":{p.port}" if p.port and p.port != 443 else ""
    query = sorted((k, v) for k, v in parse_qsl(p.query, keep_blank_values=True)
                   if not (k.lower().startswith("utm_") or k.lower() in _TRACKING))
    path = p.path.rstrip("/") or "/"
    return urlunsplit(("https", host + port, path, urlencode(query), ""))


def fingerprint(title: str) -> str:
    """SHA-256 للعنوان المطبَّع (يطابق قيد القاعدة ^[0-9a-f]{64}$)."""
    return hashlib.sha256(normalize(title).encode("utf-8")).hexdigest()


def _token_set(title: str) -> set[str]:
    return {t for t in normalize(title).split(" ") if len(t) > 1}


def similarity(a: str, b: str) -> float:
    """تشابه Jaccard على كلمات العنوان المطبَّعة (0..1)."""
    sa, sb = _token_set(a), _token_set(b)
    if not sa or not sb:
        return 0.0
    return len(sa & sb) / len(sa | sb)


def find_similar(title: str, existing: list[tuple[int, str]], threshold: float = 0.8, min_tokens: int = 4) -> int | None:
    """معرّف أقرب خبر موجود بتشابه ≥ threshold. العناوين القصيرة جداً (< min_tokens كلمات) لا تُقارَن بالتشابه، فقط بالبصمة."""
    if len(_token_set(title)) < min_tokens:
        return None
    best, best_id = 0.0, None
    for nid, other in existing:
        s = similarity(title, other)
        if s >= threshold and s > best:
            best, best_id = s, nid
    return best_id
