"""المرحلة 1: مطابقة النص الكامل، أسباب الرفض، التقرير، صفحات أقدم، ناشر الخلاصة، ومرونة finish_run."""
import unittest

from momani_fetcher import feeds, matcher, run
from momani_fetcher.store import StoreError, SupabaseStore
from tests.test_fetcher import KW, KWROWS, FakeStore, NOW


def rss(items):
    body = "".join(
        f"<item><title>{t}</title><link>{l}</link><description>{d}</description>"
        + (f"<content:encoded>{c}</content:encoded>" if c else "") + (f"<source url='https://pub.example'>{src}</source>" if src else "") + "</item>"
        for (t, l, d, c, src) in items)
    return ('<?xml version="1.0"?><rss version="2.0" xmlns:content="http://purl.org/rss/1.0/modules/content/"><channel>'
            + body + "</channel></rss>").encode("utf-8")


class BodyMatchTests(unittest.TestCase):
    def test_region_only_in_full_text_is_accepted_with_note(self):
        long_summary = "ن" * 700
        data = rss([("مشروع جديد", "https://s.example/1", "ملخص قصير", long_summary + " في بلدة عبين الجميلة", "")])
        item = feeds.parse_feed(data, NOW)[0]
        self.assertIn("عبين", item.text)
        self.assertNotIn("عبين", item.summary)  # المقتطف مقصوص 600 حرف
        m = matcher.evaluate(item.title, item.summary, KW, item.text)
        self.assertTrue(m.matched)
        self.assertIn("نص الخبر فقط", m.note)

    def test_lead_match_has_no_body_note(self):
        m = matcher.evaluate("افتتاح في عبين", "", KW, "نص")
        self.assertTrue(m.matched); self.assertNotIn("نص الخبر فقط", m.note)

    def test_exclude_in_body_still_rejects(self):
        m = matcher.evaluate("خبر", "", KW, "زيارة قبة الصخرة في بلدة عبين")
        self.assertFalse(m.matched); self.assertEqual(m.reason, matcher.R_EXCLUDED)

    def test_reasons(self):
        self.assertEqual(matcher.evaluate("أسعار النفط", "", KW).reason, matcher.R_NO_MATCH)
        self.assertEqual(matcher.evaluate("حجر صخرة كبيرة", "", KW).reason, matcher.R_AMBIGUOUS)


class RunReportTests(unittest.TestCase):
    def source(self):
        return [{"id": 1, "name": "موقع", "url": "https://site.example/feed", "kind": "rss"}]

    def test_counts_rejections_and_reasons(self):
        data = rss([
            ("افتتاح في عبين", "https://s.example/1", "d", "", ""),
            ("أسعار النفط", "https://s.example/2", "d", "", ""),
            ("تصريح للمومني", "https://s.example/3", "d", "", ""),
            ("حجر صخرة", "https://s.example/4", "d", "", ""),
        ])
        store = FakeStore(self.source(), KWROWS)
        st = run.run(store, fetch=lambda u: data)
        self.assertEqual((st.items_seen, st.items_matched, st.items_inserted, st.items_rejected), (4, 1, 1, 3))
        self.assertEqual(st.reject_reasons[matcher.R_NO_MATCH], 1)
        self.assertEqual(st.reject_reasons[matcher.R_NAME_NO_CONTEXT], 1)
        self.assertEqual(st.reject_reasons[matcher.R_AMBIGUOUS], 1)
        self.assertEqual(len(st.reject_samples), 2)
        row = store.runs[0]
        self.assertEqual(row["items_rejected"], 3)
        self.assertEqual(row["details"][0]["inserted"], 1)

    def test_report_distinguishes_success_from_no_news(self):
        data = rss([("أسعار النفط", "https://s.example/2", "d", "", "")])
        st = run.run(FakeStore(self.source(), KWROWS), fetch=lambda u: data)
        self.assertIn("نجح تقنياً", run.report(st))
        self.assertIn("لم تُكتشف أخبار جديدة", run.report(st))
        data2 = rss([("افتتاح في عبين", "https://s.example/9", "d", "", "")])
        st2 = run.run(FakeStore(self.source(), KWROWS), fetch=lambda u: data2)
        self.assertIn("أُضيفت 1 أخبار", run.report(st2))

    def test_report_all_failed(self):
        def boom(u): raise feeds.FeedError("HTTP 403")
        st = run.run(FakeStore(self.source(), KWROWS), fetch=boom)
        txt = run.report(st)
        self.assertIn("فشلت كل المصادر", txt); self.assertIn("HTTP 403", txt)

    def test_publisher_becomes_source_name(self):
        data = rss([("افتتاح في عبين", "https://s.example/1", "d", "", "جريدة الرأي")])
        store = FakeStore(self.source(), KWROWS)
        run.run(store, fetch=lambda u: data)
        self.assertEqual(store.news[0]["source_name"], "جريدة الرأي")


class SearchFeedTests(unittest.TestCase):
    def test_publisher_suffix_removed_from_title(self):
        data = rss([("افتتاح مدرسة في عبين - جريدة الرأي", "https://news.example/a", "d", "", "جريدة الرأي")])
        it = feeds.parse_feed(data, NOW)[0]
        self.assertEqual(it.title, "افتتاح مدرسة في عبين")
        self.assertEqual(it.publisher, "جريدة الرأي")


class PagingTests(unittest.TestCase):
    def test_paged_url(self):
        self.assertEqual(feeds.paged_url("https://a.example/feed/", 1), "https://a.example/feed/")
        self.assertEqual(feeds.paged_url("https://a.example/feed/", 3), "https://a.example/feed/?paged=3")
        self.assertEqual(feeds.paged_url("https://a.example/feed?x=1&paged=2", 4), "https://a.example/feed?x=1&paged=4")
        self.assertIsNone(feeds.paged_url("https://a.example/rss.php", 2))

    def test_older_pages_fetched_only_when_requested(self):
        pages = {
            "https://a.example/feed": rss([("افتتاح في عبين", "https://a.example/n/1", "d", "", "")]),
            "https://a.example/feed?paged=2": rss([("مدرسة في عبلين", "https://a.example/n/2", "d", "", "")]),
            "https://a.example/feed?paged=3": rss([("مدرسة في عبلين", "https://a.example/n/2", "d", "", "")]),  # تكرار = نهاية
        }
        src = [{"id": 1, "name": "أ", "url": "https://a.example/feed", "kind": "rss"}]
        s1 = FakeStore(src, KWROWS)
        self.assertEqual(run.run(s1, fetch=lambda u: pages[u]).items_inserted, 1)
        s2 = FakeStore(src, KWROWS)
        self.assertEqual(run.run(s2, fetch=lambda u: pages[u], pages=3).items_inserted, 2)

    def test_failed_extra_page_does_not_fail_source(self):
        first = rss([("افتتاح في عبين", "https://a.example/n/1", "d", "", "")])
        def fetch(u):
            if "paged" in u: raise feeds.FeedError("HTTP 404")
            return first
        src = [{"id": 1, "name": "أ", "url": "https://a.example/feed", "kind": "rss"}]
        st = run.run(FakeStore(src, KWROWS), fetch=fetch, pages=2)
        self.assertEqual((st.sources_failed, st.items_inserted), (0, 1))


class FinishRunFallbackTests(unittest.TestCase):
    def test_falls_back_to_legacy_columns_on_400(self):
        calls = []
        store = SupabaseStore("https://x.example", "k")
        def fake(method, path, body=None, prefer=None):
            calls.append(dict(body))
            if "details" in body: raise StoreError("HTTP 400", 400)
        store._call = fake
        store.finish_run(1, {"items_seen": 3, "items_rejected": 2, "reject_reasons": {}, "details": []})
        self.assertEqual(len(calls), 2)
        self.assertNotIn("details", calls[1]); self.assertIn("items_seen", calls[1])

    def test_other_errors_propagate(self):
        store = SupabaseStore("https://x.example", "k")
        def fake(*a, **k): raise StoreError("HTTP 500", 500)
        store._call = fake
        with self.assertRaises(StoreError):
            store.finish_run(1, {"details": []})


if __name__ == "__main__":
    unittest.main()
