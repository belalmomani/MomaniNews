import unittest
from datetime import datetime, timedelta, timezone

from momani_fetcher import classify, feeds, run, watchdog
from momani_fetcher.store import StoreError, SupabaseStore

from tests.test_phase4 import KWROWS, NOW, Rec, days, rssd, src


class ClassifyTests(unittest.TestCase):
    def test_categories(self):
        c = classify.classify
        self.assertEqual(c("ينعى أهالي عبين المرحوم أحمد المومني"), "death")
        self.assertEqual(c("انتقل إلى رحمة الله والد رئيس بلدية عبلين"), "death")
        self.assertEqual(c("تهنئة بمناسبة زفاف ابن عبين"), "congrats")
        self.assertEqual(c("تكريم الطلبة المتفوقين في صخرة"), "achievement")
        self.assertEqual(c("تساقط للثلوج في منطقة عبين بعجلون"), "weather")
        self.assertEqual(c("تصادم على طريق صخرة"), "incident")
        self.assertEqual(c("نادي عبين يفوز بالدوري"), "sports")
        self.assertEqual(c("أهالي عبين يطالبون بخلطات إسفلتية للشوارع"), "services")

    def test_death_wins_over_other_words(self):
        self.assertEqual(classify.classify("وفاة طالب متفوق بحادث تصادم"), "death")

    def test_unknown_is_none_not_a_guess(self):
        self.assertIsNone(classify.classify("لقاء مع وجهاء المنطقة"))
        self.assertIsNone(classify.classify(""))

    def test_falls_back_to_summary(self):
        self.assertEqual(classify.classify("خبر من عبين", "تهنئة للعريس"), "congrats")

    def test_no_partial_word_match(self):
        self.assertIsNone(classify.classify("المسافة طويلة"))   # «مسافة» لا تحتوي كلمة «مياه»/«نادي»...

    def test_every_rule_category_has_label(self):
        for cat, _ in classify.RULES:
            self.assertIn(cat, classify.CATEGORY_LABELS)


class InsertCategoryTests(unittest.TestCase):
    def setUp(self): feeds._ROBOTS_CACHE.clear()

    def test_run_stores_category(self):
        data = rssd([("ينعى أهالي عبين المرحوم أبو أحمد", "https://site.example/1", "وفاة", days(0), "", "")])
        st = Rec([{"id": 1, "name": "موقع", "url": "https://site.example/feed", "kind": "rss", "origin": "manual"}], KWROWS)
        run.run(st, fetch=lambda u: data, now=NOW)
        self.assertEqual(st.news[0]["category"], "death")

    def test_store_retries_without_category_when_column_missing(self):
        calls = []

        class S(SupabaseStore):
            def __init__(self): pass
            def _call(self, method, path, body=None, prefer=None):
                calls.append(dict(body))
                if "category" in body:
                    raise StoreError('HTTP 400: column "category" of relation "news" does not exist', 400)
                return [{"id": 7}]

        s = S()
        self.assertEqual(s.insert_news({"title": "x", "category": "death"}), 7)
        self.assertFalse(s.category_supported)
        self.assertEqual(s.insert_news({"title": "y", "category": "sports"}), 7)
        self.assertEqual(len(calls), 3)            # الأولى فشلت، ثم إعادة بلا عمود، ثم الثانية مباشرة بلا عمود
        self.assertNotIn("category", calls[1]); self.assertNotIn("category", calls[2])


def runrow(minutes_ago, total=10, failed=0, seen=100, err=None):
    return {"started_at": (NOW - timedelta(minutes=minutes_ago)).isoformat(), "sources_total": total,
            "sources_failed": failed, "items_seen": seen, "error_summary": err}


class WatchdogTests(unittest.TestCase):
    def test_healthy(self):
        self.assertEqual(watchdog.evaluate([runrow(60), runrow(240), runrow(420)], NOW), [])

    def test_no_news_is_not_a_failure(self):
        self.assertEqual(watchdog.evaluate([runrow(60, seen=50), runrow(240), runrow(420)], NOW), [])

    def test_silence_over_8_hours(self):
        p = watchdog.evaluate([runrow(9 * 60), runrow(12 * 60)], NOW)
        self.assertTrue(any("آخر تشغيل جلب قبل 9 ساعة" in x for x in p))

    def test_three_bad_runs_in_a_row(self):
        p = watchdog.evaluate([runrow(60, err="boom"), runrow(240, failed=10), runrow(420, seen=0)], NOW)
        self.assertEqual(len(p), 1)
        self.assertIn("آخر 3 تشغيلات سيئة", p[0])

    def test_two_bad_runs_not_enough(self):
        self.assertEqual(watchdog.evaluate([runrow(60, err="boom"), runrow(240, failed=10), runrow(420)], NOW), [])

    def test_no_runs_at_all(self):
        self.assertEqual(watchdog.evaluate([], NOW), ["لا يوجد أي تشغيل جلب مسجّل"])

    def test_unreadable_date(self):
        self.assertTrue(watchdog.evaluate([{"started_at": "???"}], NOW))


if __name__ == "__main__":
    unittest.main()
