"""استعلامات البحث (Google News RSS) المتنوعة لكل منطقة والعشيرة: تهجئات، تشكيل، صيغ اجتماعية (وفاة/تعزية/تهنئة/نجاح/تكريم)."""
from urllib.parse import quote

from . import variants

SOCIAL = "وفاة OR تعزية OR تهنئة OR نجاح OR تكريم OR حفل"
PLACES = {
    "abeen": ["عبين", "عبّين"],
    "abelin": ["عبلين", "عبلّين"],
    "sakhra": ["صخرة عجلون"],
}

# (نص البحث، وصف قصير). نص البحث يُرسل كما هو؛ OR مدعومة في Google News.
QUERIES: list[str] = [
    # مناطق: الاسم وحده + مع عجلون + البلدة/البلدية + التهجئات
    "عبين", "عبين عجلون", "بلدة عبين", "عبّين",
    "عبلين", "عبلين عجلون", "بلدة عبلين", "عبلّين",
    "صخرة عجلون", "بلدة صخرة عجلون", "بلدية صخرة عجلون",
    # العشيرة
    "عشيرة المومني", "آل المومني", "ديوان عشيرة المومني", "المومني عجلون",
    "المومني عبين", "المومني عبلين", "المومني صخرة",
    # اجتماعي: وفيات وتهاني ونجاحات ومناسبات
    f'"عبين" {SOCIAL}', f'"عبلين" {SOCIAL}', f'"صخرة عجلون" {SOCIAL}', f'"عشيرة المومني" {SOCIAL}', f'"آل المومني" {SOCIAL}',
    # لاتيني
    "Abeen Ajloun", "Ablin Ajloun",
]


# إضافات القاموس (صيغ لاتينية وآل/ال) بلا تكرار مع الاستعلامات أعلاه
QUERIES += [q for q in variants.extra_queries() if q not in QUERIES]

ENGINE_GOOGLE, ENGINE_BING, ENGINE_DIRECT = "Google News", "Bing News", "مصادر مباشرة"
# محركات أخرى متاحة بصيغة RSS رسمية. الفحص يحترم robots.txt؛ إن حجبها محرك سقط بصمت في التقرير (يُسجَّل «محجوب») ولا نتحايل.
ENGINES = (ENGINE_GOOGLE, ENGINE_BING)
BING_QUERIES = QUERIES[:12]   # عدد أقل: لا نُغرق محركاً ثانوياً؛ القياس يقرّر التوسع


def google_news_url(q: str) -> str:
    return "https://news.google.com/rss/search?q=" + quote(q) + "&hl=ar&gl=JO&ceid=JO:ar"


def bing_news_url(q: str) -> str:
    return "https://www.bing.com/news/search?q=" + quote(q) + "&format=rss&setmkt=ar-JO"


def engine_of(url: str) -> str:
    u = (url or "").lower()
    if "news.google." in u:
        return ENGINE_GOOGLE
    if "bing.com/news" in u:
        return ENGINE_BING
    return ENGINE_DIRECT


def search_source_rows() -> list[dict]:
    rows = []
    for q in QUERIES:
        label = q if len(q) <= 60 else q[:57] + "…"
        rows.append({"name": f"بحث Google News: {label}"[:120], "url": google_news_url(q), "kind": "rss", "origin": "search"})
        if q in BING_QUERIES:
            rows.append({"name": f"بحث Bing News: {label}"[:120], "url": bing_news_url(q), "kind": "rss", "origin": "search"})
    return rows
