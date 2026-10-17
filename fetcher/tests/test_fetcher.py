import unittest
from datetime import datetime, timezone

from momani_fetcher import dedup, feeds, matcher, run
from momani_fetcher.normalize import find_phrase, normalize, tokens
from momani_fetcher.matcher import Keyword

KW = [
    Keyword("عبين", "region", "abeen"),
    Keyword("عبلين", "region", "abelin"),
    Keyword("صخرة", "region", "sakhra", True),
    Keyword("مومني", "name"),
    Keyword("عجلون", "context"), Keyword("كفرنجة", "context"), Keyword("بلدية", "context"), Keyword("قرية", "context"),
    Keyword("قبة الصخرة", "exclude"), Keyword("الصخرة المشرفة", "exclude"),
]
NOW = datetime(2026, 10, 17, 12, 0, tzinfo=timezone.utc)


class NormalizeTests(unittest.TestCase):
    def test_hamza_taa_yaa_tashkeel(self):
        self.assertEqual(normalize("أَخبارُ عَبّين"), normalize("اخبار عبين"))
        self.assertEqual(normalize("مدرسة"), normalize("مدرسه"))
        self.assertEqual(normalize("على"), normalize("علي"))
        self.assertEqual(normalize("إربد"), "اربد")

    def test_digits_punct_spaces(self):
        self.assertEqual(normalize("  عبين،   ٢٠٢٦!! "), "عبين 2026")
        self.assertEqual(normalize(""), "")

    def test_prefixes_allowed_no_substring(self):
        self.assertTrue(find_phrase(tokens("افتتاح مدرسة في عبين"), "عبين"))
        self.assertTrue(find_phrase(tokens("زار وفد بعبين"), "عبين"))
        self.assertTrue(find_phrase(tokens("بلدية المومني"), "مومني"))
        self.assertFalse(find_phrase(tokens("عبينات"), "عبين"))
        self.assertFalse(find_phrase(tokens("بعبين"), "ين"))

    def test_phrase_sequence(self):
        self.assertTrue(find_phrase(tokens("زوار قبة الصخرة المشرفة"), "قبة الصخرة"))
        self.assertFalse(find_phrase(tokens("قبة بيت الصخرة"), "قبة الصخرة"))


class MatcherTests(unittest.TestCase):
    def ev(self, title, summary=""):
        return matcher.evaluate(title, summary, KW)

    def test_region_matches(self):
        m = self.ev("افتتاح مشروع مياه في عبين")
        self.assertTrue(m.matched); self.assertEqual(m.region_id, "abeen"); self.assertIn("عبين", m.terms)
        self.assertEqual(self.ev("أخبار عبلين اليوم").region_id, "abelin")

    def test_ajloun_alone_is_not_enough(self):
        self.assertFalse(self.ev("حادث سير في عجلون").matched)
        self.assertFalse(self.ev("مهرجان عجلون السياحي").matched)

    def test_ambiguous_sakhra_needs_context(self):
        self.assertFalse(self.ev("نقل صخرة ضخمة إلى الموقع").matched)
        m = self.ev("بلدية صخرة تعلن عن مشروع")
        self.assertTrue(m.matched); self.assertEqual(m.region_id, "sakhra")
        self.assertTrue(self.ev("قرية صخرة في عجلون").matched)

    def test_exclude_overrides(self):
        self.assertFalse(self.ev("زوار قبة الصخرة المشرفة في القدس", "في بلدية القدس").matched)

    def test_momani_name_without_region(self):
        m = self.ev("تصريح للمومني حول الإعلام")
        self.assertTrue(m.matched); self.assertEqual(m.region_id, "momani")

    def test_region_wins_over_name(self):
        self.assertEqual(self.ev("المومني يزور عبين").region_id, "abeen")

    def test_summary_is_searched(self):
        self.assertTrue(self.ev("خبر محلي", "تفاصيل من بلدة عبلين").matched)

    def test_unrelated(self):
        self.assertFalse(self.ev("ارتفاع أسعار النفط عالمياً").matched)


class DedupTests(unittest.TestCase):
    def test_url_normalization(self):
        a = dedup.normalize_url("https://WWW.Example.com/a/b/?utm_source=x&id=5&fbclid=1#frag")
        b = dedup.normalize_url("https://example.com/a/b?id=5")
        self.assertEqual(a, b)
        self.assertIsNone(dedup.normalize_url("http://example.com/a"))
        self.assertIsNone(dedup.normalize_url("javascript:alert(1)"))
        self.assertIsNone(dedup.normalize_url("not a url"))

    def test_fingerprint_stable_and_hex64(self):
        f1 = dedup.fingerprint("افتتاح مدرسة جديدة في عبين!")
        f2 = dedup.fingerprint("افتتاح  مدرسه جديده في عبين")
        self.assertEqual(f1, f2)
        self.assertRegex(f1, r"^[0-9a-f]{64}$")
        self.assertNotEqual(f1, dedup.fingerprint("افتتاح مدرسة جديدة في عبلين"))

    def test_similarity_threshold(self):
        existing = [(1, "افتتاح مدرسة جديدة في بلدة عبين اليوم")]
        self.assertEqual(dedup.find_similar("افتتاح مدرسة جديدة في بلدة عبين", existing), 1)
        self.assertIsNone(dedup.find_similar("حريق في مزرعة قرب عبين صباح اليوم", existing))

    def test_short_titles_not_fuzzy_matched(self):
        self.assertIsNone(dedup.find_similar("حادث في عبين", [(1, "حادث في عبلين")]))


RSS = """<?xml version="1.0" encoding="UTF-8"?>
<rss version="2.0"><channel><title>موقع</title>
<item><title>افتتاح مدرسة جديدة في عبين</title><link>https://site.example/n/1?utm_source=fb</link>
<description>&lt;p&gt;تفاصيل &lt;b&gt;الافتتاح&lt;/b&gt;&lt;/p&gt;</description><pubDate>Fri, 16 Oct 2026 08:00:00 +0000</pubDate></item>
<item><title>ارتفاع أسعار النفط</title><link>https://site.example/n/2</link><description>x</description></item>
<item><title>مهرجان عجلون</title><link>https://site.example/n/3</link><description>y</description></item>
<item><title>بلدية عبلين تطلق حملة نظافة</title><link>http://site.example/n/4</link><description>z</description></item>
<item><title>زوار قبة الصخرة</title><link>https://site.example/n/5</link><description>بلدية</description></item>
</channel></rss>""".encode("utf-8")

ATOM = """<?xml version="1.0"?><feed xmlns="http://www.w3.org/2005/Atom"><title>t</title>
<entry><title>أخبار عبلين</title><link rel="alternate" href="https://a.example/p/9"/><summary>ملخص</summary><published>2026-10-16T10:00:00Z</published></entry></feed>""".encode("utf-8")


class FeedTests(unittest.TestCase):
    def test_rss_parse_and_html_stripped(self):
        items = feeds.parse_feed(RSS, NOW)
        self.assertEqual(len(items), 5)
        self.assertEqual(items[0].summary, "تفاصيل الافتتاح")
        self.assertEqual(items[0].published, datetime(2026, 10, 16, 8, 0, tzinfo=timezone.utc))
        self.assertIsNone(items[1].published)

    def test_atom(self):
        items = feeds.parse_feed(ATOM, NOW)
        self.assertEqual(items[0].link, "https://a.example/p/9")
        self.assertEqual(items[0].published.hour, 10)

    def test_future_date_dropped(self):
        self.assertIsNone(feeds.parse_date("Fri, 16 Oct 2030 08:00:00 +0000", NOW))

    def test_rejects_entities_and_garbage(self):
        bomb = b'<?xml version="1.0"?><!DOCTYPE lolz [<!ENTITY a "aaaa">]><rss><channel><item><title>&a;</title><link>https://x.y/1</link></item></channel></rss>'
        with self.assertRaises(feeds.FeedError): feeds.parse_feed(bomb)
        with self.assertRaises(feeds.FeedError): feeds.parse_feed(b"<html><body>not a feed</body></html>")
        with self.assertRaises(feeds.FeedError): feeds.parse_feed(b"\x00\x01 not xml")

    def test_http_get_refuses_http(self):
        with self.assertRaises(feeds.FeedError): feeds.http_get("http://insecure.example/feed")


class FakeStore:
    def __init__(self, sources, keywords, news=None):
        self._sources, self._keywords = sources, keywords
        self.news = news or []  # dicts مع id,title,original_url,fingerprint
        self.refs, self.results, self.failures, self.runs = [], {}, [], []
        self._next = 100

    def active_sources(self): return self._sources
    def active_keywords(self): return self._keywords
    def known_original_urls(self, urls):
        have = {n["original_url"] for n in self.news} | {r[2] for r in self.refs}
        return {u for u in urls if u in have}
    def known_fingerprints(self, fps): return {n["fingerprint"]: n["id"] for n in self.news if n["fingerprint"] in fps}
    def recent_titles(self, days=7): return [(n["id"], n["title"]) for n in self.news]
    def insert_news(self, row):
        if any(n["original_url"] == row["original_url"] or n["fingerprint"] == row["fingerprint"] for n in self.news): return None
        self._next += 1
        self.news.append(dict(row, id=self._next))
        return self._next
    def add_ref(self, news_id, source_name, url): self.refs.append((news_id, source_name, url))
    def source_result(self, sid, ok, error): self.results[sid] = (ok, error)
    def log_failure(self, sid, url, error): self.failures.append((sid, url, error))
    def start_run(self, trigger): self.runs.append({"trigger": trigger}); return len(self.runs)
    def finish_run(self, run_id, stats): self.runs[run_id - 1].update(stats)


KWROWS = [{"word": k.word, "kind": k.kind, "region_id": k.region_id, "needs_context": k.needs_context} for k in KW]


class RunTests(unittest.TestCase):
    def test_full_cycle_inserts_only_matches_pending(self):
        store = FakeStore([{"id": 1, "name": "موقع", "url": "https://site.example/feed", "kind": "rss"}], KWROWS)
        stats = run.run(store, fetch=lambda url: RSS)
        self.assertEqual(stats.items_seen, 5)
        self.assertEqual(stats.items_inserted, 1)  # مدرسة عبين فقط؛ رابط http لعبلين مرفوض؛ قبة الصخرة مستبعدة
        n = store.news[0]
        self.assertEqual(n["status"], "pending")
        self.assertEqual(n["region_id"], "abeen")
        self.assertEqual(n["original_url"], "https://site.example/n/1")
        self.assertEqual(n["excerpt"], "تفاصيل الافتتاح")
        self.assertNotIn("image_url", n)
        self.assertEqual(store.results[1], (True, None))
        self.assertEqual(store.runs[0]["items_inserted"], 1)

    def test_second_run_adds_nothing(self):
        store = FakeStore([{"id": 1, "name": "موقع", "url": "https://site.example/feed", "kind": "rss"}], KWROWS)
        run.run(store, fetch=lambda url: RSS)
        stats = run.run(store, fetch=lambda url: RSS)
        self.assertEqual(stats.items_inserted, 0)
        self.assertEqual(len(store.news), 1)
        self.assertEqual(stats.items_duplicate, 1)

    def test_same_news_other_source_becomes_ref(self):
        store = FakeStore([{"id": 1, "name": "أ", "url": "https://a.example/f", "kind": "rss"},
                           {"id": 2, "name": "ب", "url": "https://b.example/f", "kind": "rss"}], KWROWS)
        other = RSS.replace(b"https://site.example/n/1?utm_source=fb", b"https://other.example/x/77")
        feeds_by_url = {"https://a.example/f": RSS, "https://b.example/f": other}
        stats = run.run(store, fetch=lambda url: feeds_by_url[url])
        self.assertEqual(len(store.news), 1)
        self.assertEqual(store.refs, [(store.news[0]["id"], "ب", "https://other.example/x/77")])
        self.assertEqual(stats.items_duplicate, 1)

    def test_failed_source_logged_and_others_continue(self):
        store = FakeStore([{"id": 1, "name": "سيئ", "url": "https://bad.example/f", "kind": "rss"},
                           {"id": 2, "name": "جيد", "url": "https://site.example/feed", "kind": "rss"}], KWROWS)
        def fetch(url):
            if "bad" in url: raise feeds.FeedError("HTTP 500")
            return RSS
        stats = run.run(store, fetch=fetch)
        self.assertEqual(stats.sources_failed, 1)
        self.assertEqual(store.results[1], (False, "HTTP 500"))
        self.assertEqual(store.results[2], (True, None))
        self.assertEqual(len(store.failures), 1)
        self.assertEqual(stats.items_inserted, 1)
        self.assertIn("سيئ", store.runs[0]["error_summary"])

    def test_html_sources_skipped_not_scraped(self):
        called = []
        store = FakeStore([{"id": 1, "name": "ويب", "url": "https://w.example/", "kind": "html"}], KWROWS)
        stats = run.run(store, fetch=lambda url: called.append(url) or RSS)
        self.assertEqual(called, [])
        self.assertEqual(stats.sources_failed, 1)
        self.assertFalse(store.results[1][0])

    def test_no_keywords_reported(self):
        store = FakeStore([{"id": 1, "name": "م", "url": "https://s.example/f", "kind": "rss"}], [])
        stats = run.run(store, fetch=lambda url: RSS)
        self.assertEqual(stats.items_inserted, 0)
        self.assertIn("لا توجد كلمات", store.runs[0]["error_summary"])

    def test_title_only_one_copy_when_fingerprint_equal(self):
        dup = RSS.replace(b"https://site.example/n/3", b"https://site.example/n/1b").replace("مهرجان عجلون".encode(), "افتتاح مدرسة جديدة في عبين".encode())
        store = FakeStore([{"id": 1, "name": "م", "url": "https://s.example/f", "kind": "rss"}], KWROWS)
        run.run(store, fetch=lambda url: dup)
        self.assertEqual(len(store.news), 1)
        self.assertEqual(len(store.refs), 1)


if __name__ == "__main__":
    unittest.main()
