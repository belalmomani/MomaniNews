"""اختبار صفحة المشاركة العامة بمحاكاة Supabase (بلا شبكة). تشغيل: python3 web/tests/news_test.py"""
import json, pathlib, sys
from playwright.sync_api import sync_playwright

PAGE = pathlib.Path(__file__).resolve().parents[1] / "news.html"
ROWS = {
  "1": {"id":1,"title":"افتتاح مدرسة في عبين","excerpt":"مقتطف","content":"نص كامل\n\nفقرة ثانية","source_name":"موقع","published_at":"2026-10-16T08:00:00Z","original_url":"https://site.example/n/1","image_url":None,"regions":{"name_ar":"عبين"}},
  "2": {"id":2,"title":"<img src=x onerror=window.PWNED=1>","excerpt":"","content":"فقرة أولى\n\nفقرة ثانية","source_name":"المشرف","published_at":"2026-10-16T08:00:00Z","original_url":None,"image_url":None,"regions":{"name_ar":"عبلين"}},
  "3": {"id":3,"title":"رابط خطر","excerpt":"م","content":"","source_name":"س","published_at":None,"original_url":"javascript:alert(1)","image_url":"http://insecure/x.png","regions":None},
}
ok = fails = 0
def check(name, cond):
    global ok, fails
    if cond: ok += 1
    else: fails += 1; print("FAIL:", name)

def handler(route):
    url = route.request.url
    if "status=eq.approved" not in url: return route.fulfill(status=200, body="[]")
    id_ = url.split("id=eq.")[1].split("&")[0]
    if id_ == "500": return route.fulfill(status=500, body="{}")
    route.fulfill(status=200, content_type="application/json", body=json.dumps([ROWS[id_]] if id_ in ROWS else []))

with sync_playwright() as p:
    b = p.chromium.launch(); pg = b.new_page()
    pg.route("**/rest/v1/**", handler)
    def open_(q):
        pg.goto(PAGE.as_uri() + q); pg.wait_for_timeout(300); return pg.inner_text("main")
    t = open_("?id=1")
    check("عنوان", "افتتاح مدرسة في عبين" in t); check("منطقة", "عبين" in t)
    check("المنقول: مقتطف فقط", "مقتطف" in t and "نص كامل" not in t)
    check("رابط المصدر", pg.locator("a.btn").get_attribute("href") == "https://site.example/n/1")
    check("rel آمن", "noopener" in pg.locator("a.btn").get_attribute("rel"))
    check("عنوان الصفحة", pg.title().startswith("افتتاح مدرسة"))
    t = open_("?id=2")
    check("خبر المشرف: المحتوى كاملاً", "فقرة ثانية" in t and pg.locator("p").count() >= 2)
    check("لا حقن HTML", pg.evaluate("window.PWNED") is None and pg.locator("main img").count() == 0)
    check("لا رابط مصدر", pg.locator("a.btn").count() == 0)
    t = open_("?id=3")
    check("javascript: مرفوض", pg.locator("a.btn").count() == 0)
    check("صورة http مرفوضة", pg.locator("main img").count() == 0)
    check("خبر غير موجود", "غير موجود" in open_("?id=99"))
    check("رقم غير صالح", "غير صالح" in open_("?id=abc"))
    check("بلا رقم", "غير صالح" in open_(""))
    check("خطأ خادم", "تعذر" in open_("?id=500"))
    b.close()
print(f"{ok} passed, {fails} failed"); sys.exit(1 if fails else 0)
