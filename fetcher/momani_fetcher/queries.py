"""استعلامات البحث (Google News RSS) المتنوعة لكل منطقة والعشيرة: تهجئات، تشكيل، صيغ اجتماعية (وفاة/تعزية/تهنئة/نجاح/تكريم)."""
from urllib.parse import quote

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


def google_news_url(q: str) -> str:
    return "https://news.google.com/rss/search?q=" + quote(q) + "&hl=ar&gl=JO&ceid=JO:ar"


def search_source_rows() -> list[dict]:
    rows = []
    for q in QUERIES:
        label = q if len(q) <= 60 else q[:57] + "…"
        rows.append({"name": f"بحث Google News: {label}"[:120], "url": google_news_url(q), "kind": "rss", "origin": "search"})
    return rows
