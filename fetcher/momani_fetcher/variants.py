"""قاموس صيغ كتابة الأسماء (المناطق والعشيرة): عربي بالتشكيل والبدائل، ولاتيني بتهجئاته الشائعة.
المطابقة تُطبّع التشكيل والهمزات (المومنيّ = المومني) لكن لا تُطبّع التهجئة اللاتينية الملتصقة (Almomani ≠ Al Momani)،
لذلك تُضاف الصيغ اللاتينية كلمات مفتاحية صريحة. القياس الحقيقي لكل صيغة يأتي من تقرير التشغيل (قسم «قياس صيغ البحث»)."""
from .normalize import normalize

# مفتاح الكيان: (المنطقة أو None، نوع الكلمة، needs_context، الصيغ)
ENTITIES = {
    "abeen": ("abeen", "alias", False, ["عبين", "عبّين", "عَبّين", "Abeen", "Abin", "Ebeen"]),
    "abelin": ("abelin", "alias", False, ["عبلين", "عبلّين", "Ablin", "Abilin", "Eblin"]),
    "sakhra": ("sakhra", "alias", True, ["صخرة عجلون", "Sakhra", "Sakhrah"]),   # «صخرة» وحدها ملتبسة (قبة الصخرة)
    "momani": (None, "name", False, ["المومني", "المومنيّ", "آل المومني", "ال المومني", "عشيرة المومني", "Momani", "Al-Momani", "Al Momani", "Almomani", "Al-Momany"]),
}

# صيغ لاتينية ملتصقة/بشرطة لا تلتقطها كلمة «momani» بعد التطبيع
EXTRA_KEYWORDS = [("almomani", None, "name", False), ("almomany", None, "name", False), ("momany", None, "name", False),
                  ("ibleen", "abelin", "alias", False), ("sakhrah", "sakhra", "alias", True)]


def forms(entity: str) -> list[str]:
    return list(ENTITIES[entity][3])


def distinct_search_forms(entity: str) -> list[str]:
    """صيغ البحث بعد حذف ما يتطابق بعد التطبيع (المومنيّ = المومني) فلا نُرسل للمحرك طلباً مكرراً."""
    seen, out = set(), []
    for f in forms(entity):
        k = normalize(f).replace(" ", "")
        if k not in seen:
            seen.add(k)
            out.append(f)
    return out


def keyword_rows() -> list[dict]:
    rows = [{"word": w.lower(), "region_id": r, "kind": k, "needs_context": nc} for (w, r, k, nc) in EXTRA_KEYWORDS]
    for ent, (region, kind, nc, fs) in ENTITIES.items():
        for f in fs:
            if f.isascii():
                rows.append({"word": f.lower().replace("-", " "), "region_id": region, "kind": kind, "needs_context": nc})
    seen, out = set(), []
    for r in rows:
        if r["word"] not in seen:
            seen.add(r["word"])
            out.append(r)
    return out


def extra_queries() -> list[str]:
    """استعلامات بحث إضافية من القاموس: صيغ لاتينية + صيغ «آل/ال» مع سياق محلي. لا تكرار بعد التطبيع."""
    qs = ['"Almomani" Ajloun', '"Al-Momani" Ajloun', '"Momani" Ajloun', "Abeen Jordan", "Sakhra Ajloun",
          '"آل المومني" عجلون', '"ال المومني" عجلون', "المومني عجلون وفاة", "المومني عجلون تهنئة", "عشيرة المومني عجلون"]
    return qs
