"""قرار صلة الخبر: كلمات المناطق والأسماء والبدائل والسياق والاستبعاد (من جدول keywords، قابلة للإدارة دون تعديل الكود)."""
from dataclasses import dataclass, field

from .normalize import find_phrase, tokens

MOMANI_REGION = "momani"

# أسباب الرفض (تُحصى في تقرير كل تشغيل)
R_NO_MATCH = "no_match"                  # لا كلمة ذات صلة
R_EXCLUDED = "excluded"                  # كلمة استبعاد (مثل قبة الصخرة)
R_AMBIGUOUS = "ambiguous_no_context"     # اسم منطقة ملتبس (صخرة) بلا سياق
R_NAME_NO_CONTEXT = "name_no_context"    # اسم العائلة وحده بلا سياق محلي/اجتماعي (وزير/نائب بنفس الاسم...)
REASON_LABELS = {
    R_NO_MATCH: "لا صلة بالمناطق أو العشيرة",
    R_EXCLUDED: "كلمة استبعاد",
    R_AMBIGUOUS: "اسم منطقة ملتبس بلا سياق",
    R_NAME_NO_CONTEXT: "اسم المومني وحده بلا سياق محلي",
}

# سياق اجتماعي/عشائري يكفي مع اسم «المومني» (ثابت في الكود لأنه قاعدة قرار لا بيانات تُدار؛ السياق المحلي يأتي من جدول keywords).
CLAN_CONTEXT = (
    "عشيرة", "آل", "عائلة", "ديوان", "تعزية", "ينعى", "ينعي", "فقيد", "وفاة", "المرحوم", "المرحومة",
    "رحمه الله", "رحمة الله", "تهنئة", "يهنئ", "تكريم", "الحاج", "مأدبة", "عطوة", "جاهة",
)


# كلمات سياق عامة لا تكفي مع اسم «المومني» (قد تخص أي بلدية أو قرية في الأردن)
GENERIC_CONTEXT = {"بلدية", "قرية", "بلدة", "لواء", "محافظة", "مجلس محلي"}


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
    reason: str = ""  # سبب الرفض عند matched=False


def _evaluate(tt: list[str], keywords: list[Keyword]) -> Match:
    excluded = [k.word for k in keywords if k.kind == "exclude" and find_phrase(tt, k.word)]
    if excluded:
        return Match(False, note="استبعاد: " + "، ".join(excluded), reason=R_EXCLUDED)

    context_hits = [k.word for k in keywords if k.kind == "context" and find_phrase(tt, k.word)]
    area_hits = [k for k in keywords if k.kind in ("region", "alias") and k.region_id and find_phrase(tt, k.word)]
    strong = [k for k in area_hits if not k.needs_context]
    weak = [k for k in area_hits if k.needs_context]
    name_hits = [k for k in keywords if k.kind == "name" and find_phrase(tt, k.word)]

    accepted = list(strong)
    notes = []
    reason = R_NO_MATCH
    if weak:
        if context_hits or strong:
            accepted += weak
        else:
            notes.append("كلمة ملتبسة بلا سياق: " + "، ".join(k.word for k in weak))
            reason = R_AMBIGUOUS

    if accepted:
        region = accepted[0].region_id
        terms = [k.word for k in accepted] + [k.word for k in name_hits] + context_hits
        return Match(True, region, _uniq(terms), "؛ ".join(notes))
    if name_hits:
        clan_ctx = [c for c in CLAN_CONTEXT if find_phrase(tt, c)]
        local_ctx = [c for c in context_hits if c not in GENERIC_CONTEXT]
        if local_ctx or clan_ctx:
            notes.append("اسم المومني بسياق (" + "، ".join(_uniq(local_ctx + clan_ctx)[:4]) + ") — تأكد أنه من العشيرة المقصودة")
            return Match(True, MOMANI_REGION, _uniq([k.word for k in name_hits] + context_hits), "؛ ".join(notes))
        return Match(False, note="؛ ".join(notes), reason=R_NAME_NO_CONTEXT)
    return Match(False, note="؛ ".join(notes), reason=reason)


def evaluate(title: str, summary: str, keywords: list[Keyword], body: str = "") -> Match:
    """
    القواعد:
    1) كلمة exclude (مثل «قبة الصخرة») ترفض الخبر.
    2) كلمة region/alias بمنطقة تُقبل؛ إن كانت needs_context (صخرة) فتحتاج كلمة context أو منطقة أخرى غير ملتبسة.
    3) اسم «المومني» وحده لا يكفي (وزراء ونواب وغيرهم بنفس الاسم): يلزمه سياق محلي (عجلون...) أو عشائري/اجتماعي (عشيرة، تعزية...).
    4) كلمة context وحدها لا تكفي أبداً.
    5) المطابقة تبدأ من العنوان والمقتطف؛ إن لم تنجح تُجرَّب على النص الكامل المتاح في الخلاصة، ويُقبل الخبر بملاحظة
       «ورد في نص الخبر فقط» لأن الصلة أضعف (يراجعها المدير).
    """
    lead = _evaluate(tokens(title + " " + summary), keywords)
    if lead.matched or lead.reason == R_EXCLUDED or not body:
        return lead
    full = _evaluate(tokens(title + " " + summary + " " + body), keywords)
    if full.matched:
        note = "ورد الاسم في نص الخبر فقط (ليس في العنوان أو المقتطف)"
        full.note = "؛ ".join(x for x in (full.note, note) if x)
        return full
    if full.reason == R_EXCLUDED:
        return full
    return lead if lead.reason != R_NO_MATCH else full


def _uniq(items: list[str]) -> list[str]:
    seen, out = set(), []
    for x in items:
        if x not in seen:
            seen.add(x)
            out.append(x)
    return out
