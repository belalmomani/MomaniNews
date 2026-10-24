"""تطبيع النص العربي للمطابقة ومنع التكرار (لا يُستخدم للعرض؛ العنوان الأصلي يُحفظ كما هو)."""
import re
import unicodedata

_TASHKEEL = re.compile("[ؐ-ًؚ-ٰٟۖ-ۭ]")
_PUNCT = re.compile(r"[^\w\s]", re.UNICODE)
_SPACES = re.compile(r"\s+")
_ALEF = str.maketrans({"أ": "ا", "إ": "ا", "آ": "ا", "ٱ": "ا", "ى": "ي", "ة": "ه", "ؤ": "و", "ئ": "ي", "ـ": ""})
# أرقام عربية-هندية وفارسية → لاتينية
_DIGITS = str.maketrans("٠١٢٣٤٥٦٧٨٩۰۱۲۳۴۵۶۷۸۹", "01234567890123456789")


def normalize(text: str) -> str:
    """يزيل التشكيل والتطويل ويوحّد الهمزات والياء/الألف المقصورة والتاء المربوطة والأرقام، ويزيل الترقيم والمسافات الزائدة."""
    if not text:
        return ""
    t = unicodedata.normalize("NFKC", text)
    t = _TASHKEEL.sub("", t)
    t = t.translate(_ALEF).translate(_DIGITS).lower()
    t = _PUNCT.sub(" ", t.replace("_", " "))
    return _SPACES.sub(" ", t).strip()


def tokens(text: str) -> list[str]:
    n = normalize(text)
    return n.split(" ") if n else []


# سوابق شائعة تلتصق بالكلمة: و ف ب ل ك + ال (وللبلدية، بعبين، والمومني...)
_PREFIXES = ("", "و", "ف", "ب", "ل", "ك", "ال", "وال", "فال", "بال", "لل", "كال", "ولل")


def token_matches(token: str, keyword_token: str) -> bool:
    """token يساوي الكلمة مع سابقة عربية شائعة اختيارية. لا مطابقة جزئية داخل الكلمة (عبين ≠ عبينات)."""
    for p in _PREFIXES:
        if token == p + keyword_token:
            return True
    return False


def find_phrase(text_tokens: list[str], phrase: str) -> bool:
    """هل وردت العبارة (كلمة أو أكثر) متتابعة؟ السابقة مسموحة على أول كلمة فقط."""
    pt = tokens(phrase)
    if not pt:
        return False
    n = len(pt)
    for i in range(len(text_tokens) - n + 1):
        if not token_matches(text_tokens[i], pt[0]):
            continue
        if all(text_tokens[i + j] == pt[j] for j in range(1, n)):
            return True
    return False
