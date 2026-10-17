"""قرار صلة الخبر: كلمات المناطق والأسماء والبدائل والسياق والاستبعاد (كلها من جدول keywords، قابلة للإدارة دون تعديل الكود)."""
from dataclasses import dataclass, field

from .normalize import find_phrase, tokens

MOMANI_REGION = "momani"


@dataclass(frozen=True)
class Keyword:
    word: str
    kind: str  # region | name | alias | context | exclude
    region_id: str | None = None
    needs_context: bool = False


@dataclass
class Match:
    matched: bool
    region_id: str | None = None
    terms: list[str] = field(default_factory=list)
    note: str = ""


def evaluate(title: str, summary: str, keywords: list[Keyword]) -> Match:
    """
    القواعد:
    1) أي كلمة exclude في العنوان/الملخص ترفض الخبر (مثل «قبة الصخرة»).
    2) كلمة region/alias بمنطقة: تُقبل، إلا إن كانت needs_context فتتطلب وجود كلمة context أو منطقة أخرى غير ملتبسة.
    3) كلمة name (مثل مومني) بلا منطقة تعطي المنطقة «momani».
    4) كلمة context وحدها لا تكفي أبداً (لا يكفي أن يرد «عجلون»).
    """
    tt = tokens(title + " " + summary)
    excluded = [k.word for k in keywords if k.kind == "exclude" and find_phrase(tt, k.word)]
    if excluded:
        return Match(False, note="استبعاد: " + "، ".join(excluded))

    context_hits = [k.word for k in keywords if k.kind == "context" and find_phrase(tt, k.word)]
    area_hits = [k for k in keywords if k.kind in ("region", "alias") and k.region_id and find_phrase(tt, k.word)]
    strong = [k for k in area_hits if not k.needs_context]
    weak = [k for k in area_hits if k.needs_context]
    name_hits = [k for k in keywords if k.kind == "name" and find_phrase(tt, k.word)]

    accepted = list(strong)
    notes = []
    if weak:
        if context_hits or strong:
            accepted += weak
        else:
            notes.append("كلمة ملتبسة بلا سياق: " + "، ".join(k.word for k in weak))

    if accepted:
        region = accepted[0].region_id
        terms = [k.word for k in accepted] + [k.word for k in name_hits] + context_hits
        return Match(True, region, _uniq(terms), "؛ ".join(notes))
    if name_hits:
        return Match(True, MOMANI_REGION, _uniq([k.word for k in name_hits]), "؛ ".join(notes))
    return Match(False, note="؛ ".join(notes))


def _uniq(items: list[str]) -> list[str]:
    seen, out = set(), []
    for x in items:
        if x not in seen:
            seen.add(x)
            out.append(x)
    return out
