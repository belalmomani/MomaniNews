"""المرحلة 4: صيغ الأسماء، التحقق الحقيقي من الروابط والخلاصات، الاكتشاف من داخل الأخبار، الأثر الرجعي، مساهمة المحركات، والاستدراك المحدود."""
import unittest
from datetime import datetime, timedelta, timezone

from momani_fetcher import discover, feeds, linkcheck, matcher, queries, run, validate, variants
from momani_fetcher.matcher import Keyword
from tests.test_fetcher import FakeStore
from tests.test_phase2 import Net, article

NOW = datetime(2026, 10, 22, 12, 0, tzinfo=timezone.utc)
KWS = [Keyword("عبين", "region", "abeen"), Keyword("عبلين", "region", "abelin"), Keyword("صخرة", "region", "sakhra", True),
       Keyword("مومني", "name"), Keyword("عجلون", "context"), Keyword("ajloun", "context"),
       Keyword("قبة الصخرة", "exclude")]
KWS += [Keyword(r["word"], r["kind"], r["region_id"], r["needs_context"]) for r in variants.keyword_rows()]
KWROWS = [{"word": k.word, "kind": k.kind, "region_id": k.region_id, "needs_context": k.needs_context} for k in KWS]


def rssd(items):
    """items: (title, link, summary, date|None, content_html, publisher_url)"""
    out = ""
    for t, l, d, dt, c, pu in items:
        out += f"<item><title>{t}</title><link>{l}</link><description>{d}</description>"
        if dt:
            out += f"<pubDate>{dt.strftime('%a, %d %b %Y %H:%M:%S +0000')}</pubDate>"
        if c:
            out += f"<content:encoded><![CDATA[{c}]]></content:encoded>"
        if pu:
            out += f"<source url='{pu}'>ناشر</source>"
        out += "</item>"
    return ('<?xml version="1.0"?><rss version="2.0" xmlns:content="http://purl.org/rss/1.0/modules/content/"><channel>' + out + "</channel></rss>").encode("utf-8")


def days(n):
    return NOW - timedelta(days=n)


class VariantTests(unittest.TestCase):
    def test_every_region_form_matches_in_context(self):
        for ent in ("abeen", "abelin"):
            for form in variants.forms(ent):
                m = matcher.evaluate(f"افتتاح مدرسة في {form} اليوم" if not form.isascii() else f"School opens in {form} village", "", KWS)
                self.assertTrue(m.matched, f"{ent}:{form}")

    def test_sakhra_forms_need_local_context(self):
        for form in variants.forms("sakhra"):
            ctx = "Ajloun" if form.isascii() else "عجلون"
            self.assertTrue(matcher.evaluate(f"{form} {ctx} حفل تخريج", "", KWS).matched, form)
        self.assertFalse(matcher.evaluate("Sakhra festival tonight", "", KWS).matched)          # ملتبس بلا سياق
        self.assertFalse(matcher.evaluate("زيارة قبة الصخرة المشرفة", "", KWS).matched)          # استبعاد

    def test_clan_forms_match_with_social_or_local_context(self):
        for form in variants.forms("momani"):
            title = f"ديوان عشيرة: وفاة من {form} في عجلون" if not form.isascii() else f"{form} family funeral in Ajloun"
            self.assertTrue(matcher.evaluate(title, "", KWS).matched, form)

    def test_clan_name_alone_is_noise_in_all_forms(self):
        for form in variants.forms("momani"):
            title = f"الوزير {form} يصرح للصحفيين" if not form.isascii() else f"Minister {form} told reporters"
            if form.startswith(("آل", "ال ", "عشيرة")):
                continue   # «آل/عشيرة» بحد ذاتها سياق عشائري صريح
            self.assertFalse(matcher.evaluate(title, "", KWS).matched, form)

    def test_latin_glued_form_needs_explicit_keyword(self):
        base = [k for k in KWS if k.word not in ("almomani", "almomany", "momany")]
        self.assertFalse(matcher.evaluate("Almomani family funeral in Ajloun", "", base).matched)   # الثغرة قبل القاموس
        self.assertTrue(matcher.evaluate("Almomani family funeral in Ajloun", "", KWS).matched)

    def test_search_forms_deduplicated_after_normalization(self):
        d = variants.distinct_search_forms("momani")
        self.assertNotIn("المومنيّ", d)
        self.assertIn("المومني", d)
        self.assertEqual(len(variants.keyword_rows()), len({r["word"] for r in variants.keyword_rows()}))

    def test_queries_include_dictionary_without_duplicates(self):
        q = queries.QUERIES
        self.assertEqual(len(q), len(set(q)))
        self.assertIn('"Almomani" Ajloun', q)
        self.assertIn('"آل المومني" عجلون', q)


def long_article():
    return article("في بلدة عبين افتتحت اليوم مدرسة جديدة بحضور أهالي المنطقة والمسؤولين المحليين. " * 4)


def probe(url, status=200, ctype="text/html; charset=utf-8", data=None, final=None):
    return validate.Probe(status, final or url, ctype, long_article() if data is None else data)


class LinkValidationTests(unittest.TestCase):
    U = "https://s.example/a/1"
    T = "افتتاح مدرسة جديدة في عبين"

    def chk(self, p, title=None):
        return validate.check_article(self.U, self.T if title is None else title, p, NOW)[0]

    def test_good_page_ok(self):
        self.assertEqual(self.chk(probe(self.U)), validate.OK)

    def test_http_errors(self):
        self.assertEqual(self.chk(probe(self.U, 404)), validate.BROKEN)
        self.assertEqual(self.chk(probe(self.U, 410)), validate.BROKEN)
        self.assertEqual(self.chk(probe(self.U, 503)), validate.UNREACHABLE)
        self.assertEqual(self.chk(probe(self.U, 429)), validate.UNREACHABLE)
        self.assertEqual(self.chk(probe(self.U, 403)), validate.BROKEN)

    def test_200_but_redirected_to_homepage_is_broken(self):
        self.assertEqual(self.chk(probe(self.U, 200, final="https://s.example/")), validate.BROKEN)

    def test_200_but_other_host_is_changed(self):
        self.assertEqual(self.chk(probe(self.U, 200, final="https://other.example/a/1")), validate.CHANGED)

    def test_soft_404_with_200(self):
        page = '<html><head><title>الصفحة غير موجودة</title></head><body><p>' + "نص " * 100 + "</p></body></html>"
        self.assertEqual(self.chk(probe(self.U, data=page.encode())), validate.BROKEN)
        page2 = '<html><head><title>404 Not Found</title></head><body>' + "x " * 200 + "</body></html>"
        self.assertEqual(self.chk(probe(self.U, data=page2.encode())), validate.BROKEN)

    def test_non_html_and_empty(self):
        self.assertEqual(self.chk(probe(self.U, ctype="application/pdf", data=b"%PDF")), validate.BROKEN)
        self.assertEqual(self.chk(probe(self.U, data=b"   ")), validate.BROKEN)

    def test_thin_page_is_broken(self):
        page = "<html><head><title>افتتاح مدرسة جديدة في عبين</title></head><body><p>قصير</p></body></html>"
        self.assertEqual(self.chk(probe(self.U, data=page.encode())), validate.BROKEN)

    def test_page_content_changed_to_other_story(self):
        self.assertEqual(self.chk(probe(self.U), title="حريق كبير في مصنع للبلاستيك بالزرقاء"), validate.CHANGED)

    def test_robots_block_not_fetched(self):
        called = []
        robots = lambda u: b"User-agent: *\nDisallow: /\n"
        feeds._ROBOTS_CACHE.clear()
        st, _ = validate.check_link(self.U, self.T, lambda u: called.append(u), robots, NOW)
        self.assertEqual(st, validate.BLOCKED); self.assertEqual(called, [])

    def test_connection_failure_unreachable(self):
        feeds._ROBOTS_CACHE.clear()
        def boom(u): raise feeds.FeedError("connection failed: TimeoutError")
        st, _ = validate.check_link(self.U, self.T, boom, lambda u: b"", NOW)
        self.assertEqual(st, validate.UNREACHABLE)


class FeedHealthTests(unittest.TestCase):
    def items(self, data): return feeds.parse_feed(data, NOW)

    def test_cases(self):
        self.assertEqual(validate.check_feed_items([], NOW)[0], "empty")
        self.assertEqual(validate.check_feed_items(self.items(rssd([("أ", "https://s.example/x", "d", None, "", ""), ("ب", "https://s.example/x", "d", None, "", "")])), NOW)[0], "same_link")
        self.assertEqual(validate.check_feed_items(self.items(rssd([("أ", "https://s.example/", "d", None, "", ""), ("ب", "https://s.example", "d", None, "", "")])), NOW)[0], "root_links")
        self.assertEqual(validate.check_feed_items(self.items(rssd([("أ", "https://s.example/1", "d", days(500), "", "")])), NOW)[0], "stale")
        self.assertIsNone(validate.check_feed_items(self.items(rssd([("أ", "https://s.example/1", "d", days(1), "", "")])), NOW))

    def test_candidate_domains_from_publisher_and_links(self):
        data = rssd([("خبر عبين", "https://news.google.com/a", "d", None, '<a href="https://local-news.example/x">مصدر</a> <a href="http://insecure.example/">x</a>', "https://pub.example")])
        out = validate.candidate_domains(self.items(data), "news.google.com", discover.domain_ok)
        self.assertEqual(out, ["pub.example", "local-news.example"])


class Rec(FakeStore):
    def __init__(self, *a, **k):
        super().__init__(*a, **k); self.noted, self.url_updates, self.known_seen = [], [], set()
    def note_candidate(self, d, via="search", relevant=False): self.noted.append((d, via, relevant))
    def update_source_url(self, sid, url): self.url_updates.append((sid, url))


class DiscoveryFromNewsTests(unittest.TestCase):
    def setUp(self): feeds._ROBOTS_CACHE.clear()

    def test_publisher_and_body_links_become_candidates(self):
        data = rssd([("مدرسة في عبين", "https://site.example/1", "d", days(0), '<a href="https://other-local.example/n">مرجع</a> <a href="https://facebook.com/x">fb</a>', "https://pub.example")])
        st = Rec([{"id": 1, "name": "موقع", "url": "https://site.example/feed", "kind": "rss"}], KWROWS)
        stats = run.run(st, fetch=lambda u: data, now=NOW)
        self.assertIn(("pub.example", "search", True), st.noted)
        self.assertIn(("other-local.example", "news", False), st.noted)
        self.assertNotIn("facebook.com", [n[0] for n in st.noted])
        self.assertNotIn("site.example", [n[0] for n in st.noted])
        self.assertEqual(stats.from_news_domains, 2)

    def test_unmatched_items_do_not_create_candidates(self):
        data = rssd([("خبر رياضي", "https://site.example/1", "d", days(0), '<a href="https://x.example/">x</a>', "https://pub.example")])
        st = Rec([{"id": 1, "name": "موقع", "url": "https://site.example/feed", "kind": "rss"}], KWROWS)
        run.run(st, fetch=lambda u: data, now=NOW)
        self.assertEqual(st.noted, [])


GOOD = rssd([("مدرسة في عبين", "https://s.example/n/1", "d", days(0), "", "")])
HOME = b'<html><head><link rel="alternate" type="application/rss+xml" href="https://s.example/new-feed"></head><body></body></html>'


class FeedStructureChangeTests(unittest.TestCase):
    def setUp(self): feeds._ROBOTS_CACHE.clear()

    def test_empty_feed_rediscovers_new_feed_and_updates_url(self):
        net = Net({"https://s.example/feed": rssd([]), "https://s.example/": HOME, "https://s.example/new-feed": GOOD})
        st = Rec([{"id": 7, "name": "موقع", "url": "https://s.example/feed", "kind": "rss", "origin": "auto"}], KWROWS)
        stats = run.run(st, fetch=net, now=NOW)
        self.assertEqual(st.url_updates, [(7, "https://s.example/new-feed")])
        self.assertEqual(stats.items_inserted, 1)
        self.assertEqual(st.results[7], (True, None))
        self.assertTrue(any("خلاصة بديلة" in h for h in stats.health))

    def test_manual_source_not_rewritten_but_reported_with_suggestion(self):
        net = Net({"https://s.example/feed": rssd([]), "https://s.example/": HOME, "https://s.example/new-feed": GOOD})
        st = Rec([{"id": 7, "name": "موقع", "url": "https://s.example/feed", "kind": "rss", "origin": "manual"}], KWROWS)
        stats = run.run(st, fetch=net, now=NOW)
        self.assertEqual(st.url_updates, [])
        self.assertFalse(st.results[7][0]); self.assertIn("https://s.example/new-feed", st.results[7][1])
        self.assertEqual(stats.sources_failed, 1)

    def test_empty_feed_without_alternative_is_failure_not_success(self):
        net = Net({"https://s.example/feed": rssd([])})
        st = Rec([{"id": 7, "name": "موقع", "url": "https://s.example/feed", "kind": "rss", "origin": "auto"}], KWROWS)
        stats = run.run(st, fetch=net, now=NOW)
        self.assertFalse(st.results[7][0]); self.assertEqual(stats.sources_failed, 1)

    def test_empty_search_result_is_normal(self):
        st = Rec([{"id": 3, "name": "بحث", "url": "https://news.google.com/rss/search?q=x", "kind": "rss", "origin": "search"}], KWROWS)
        stats = run.run(st, fetch=lambda u: rssd([]), now=NOW)
        self.assertEqual(stats.sources_failed, 0); self.assertEqual(st.results[3], (True, None))


def src(i, url, origin="search", last=None, name=None):
    return {"id": i, "name": name or f"s{i}", "url": url, "kind": "rss", "origin": origin, "last_success_at": last}


G1, G2, B1 = "https://news.google.com/rss/search?q=a", "https://news.google.com/rss/search?q=b", "https://www.bing.com/news/search?q=a&format=rss"


class EngineYieldTests(unittest.TestCase):
    def setUp(self): feeds._ROBOTS_CACHE.clear()

    def test_unique_counts_not_raw_counts(self):
        shared = ("مدرسة جديدة في عبين تفتتح", "https://pub1.example/1")
        only_g = ("وفاة الحاج في عبلين", "https://pub2.example/2")
        only_b = ("تكريم طلاب في عبين", "https://pub3.example/3")
        feeds_by = {
            G1: rssd([(shared[0], shared[1], "d", days(0), "", ""), (only_g[0], only_g[1], "d", days(0), "", "")]),
            G2: rssd([(shared[0], shared[1], "d", days(0), "", "")]),   # نتيجة خام مكررة داخل المحرك نفسه
            B1: rssd([(shared[0], "https://bing-redirect.example/z", "d", days(0), "", ""), (only_b[0], only_b[1], "d", days(0), "", "")]),
        }
        st = Rec([src(1, G1), src(2, G2), src(3, B1)], KWROWS)
        stats = run.run(st, fetch=lambda u: feeds_by[u], now=NOW)
        g, b = stats.engines[queries.ENGINE_GOOGLE], stats.engines[queries.ENGINE_BING]
        self.assertEqual((g["matched"], b["matched"]), (3, 2))        # الخام
        self.assertEqual((g["stories"], b["stories"]), (2, 2))        # أخبار مميزة
        self.assertEqual((g["unique"], b["unique"]), (1, 1))          # الفريد: only_g / only_b؛ المشترك لا يُحسب لأحد
        by_name = {v["name"]: v for v in stats.variants}
        self.assertEqual(by_name["s1"]["unique"], 1)                   # only_g وحده
        self.assertEqual(by_name["s2"]["unique"], 0)
        self.assertEqual(stats.items_inserted, 3)
        self.assertIn("**فريدة**", run.report(stats))
        self.assertIn("engines", st.runs[0]["details"])

    def test_noise_measured_per_query(self):
        feeds_by = {G1: rssd([("حريق في الزرقاء", "https://p.example/1", "d", days(0), "", ""), ("مدرسة عبين", "https://p.example/2", "d", days(0), "", "")])}
        st = Rec([src(1, G1)], KWROWS)
        stats = run.run(st, fetch=lambda u: feeds_by[u], now=NOW)
        self.assertEqual(stats.variants[0]["noise"], 0.5)


class SearchPoliteTests(unittest.TestCase):
    def setUp(self): feeds._ROBOTS_CACHE.clear()

    def test_recent_search_sources_skipped_on_schedule_not_manual(self):
        recent = (NOW - timedelta(minutes=30)).isoformat()
        calls = []
        def f(u):
            if not u.endswith('robots.txt'): calls.append(u)
            return rssd([])
        st = Rec([src(1, G1, last=recent)], KWROWS)
        stats = run.run(st, "schedule", fetch=f, now=NOW)
        self.assertEqual((calls, stats.search_skipped), ([], 1))
        st2 = Rec([src(1, G1, last=recent)], KWROWS)
        run.run(st2, "manual", fetch=f, now=NOW)
        self.assertEqual(calls, [G1])

    def test_blocked_engine_stops_after_first_refusal(self):
        calls = []
        def f(u):
            if not u.endswith("robots.txt"): calls.append(u)
            raise feeds.FeedError("HTTP 429")
        bing = ["https://www.bing.com/news/search?q=%d&format=rss" % i for i in range(5)]
        st = Rec([src(i, u) for i, u in enumerate(bing, 1)], KWROWS)
        stats = run.run(st, fetch=f, now=NOW)
        self.assertEqual(len(calls), 1)
        self.assertEqual(stats.search_skipped, 4)
        self.assertTrue(any("حجب" in h for h in stats.health))


def paged_feed(prefix, page, n=3, start_day=0):
    return rssd([(f"مدرسة في عبين {prefix}{page}-{i}", f"https://s.example/{prefix}/{page}/{i}", "d", days(start_day + page * 3 + i), "", "") for i in range(n)])


class CatchupTests(unittest.TestCase):
    def setUp(self): feeds._ROBOTS_CACHE.clear()

    def feed_net(self, last_page=9):
        def f(u):
            if "paged=" in u:
                p = int(u.split("paged=")[1].split("&")[0])
                if p > last_page:
                    raise feeds.FeedError("HTTP 404")
                return paged_feed("a", p)
            return paged_feed("a", 1)
        return f

    def test_catchup_stops_at_last_success_date(self):
        calls = []
        base = self.feed_net()
        def f(u): calls.append(u); return base(u)
        last = (NOW - timedelta(days=5)).isoformat()   # الصفحات: p تغطي ~3p أيام قبل الآن
        st = Rec([src(1, "https://s.example/feed", "manual", last)], KWROWS)
        stats = run.run(st, fetch=f, now=NOW)
        pages_hit = [u for u in calls if "paged=" in u]
        self.assertLess(len(pages_hit), 8)                       # لا مسح للأرشيف كله
        self.assertTrue(st.advance[1])
        self.assertIn("تجاوزنا نقطة آخر نجاح", stats.sources[0].get("catchup_stop", ""))

    def test_lookback_capped_to_30_days(self):
        calls = []
        base = self.feed_net(last_page=50)
        def f(u): calls.append(u); return base(u)
        last = (NOW - timedelta(days=200)).isoformat()
        st = Rec([src(1, "https://s.example/feed", "manual", last)], KWROWS)
        run.run(st, fetch=f, now=NOW)
        self.assertLessEqual(len([u for u in calls if "paged=" in u]), run.MAX_PAGES)   # حد الصفحات للخلاصة

    def test_stops_when_page_already_known(self):
        calls = []
        base = self.feed_net()
        def f(u): calls.append(u); return base(u)
        known = [dict(title=f"x{i}", original_url=f"https://s.example/a/2/{i}", fingerprint=f"fp{i}", id=i) for i in range(3)]
        st = Rec([src(1, "https://s.example/feed", "manual", (NOW - timedelta(days=20)).isoformat())], KWROWS, news=known)
        stats = run.run(st, fetch=f, now=NOW)
        self.assertIn("جُلبت سابقاً", stats.sources[0].get("catchup_stop", ""))
        self.assertEqual(len([u for u in calls if "paged=" in u]), 1)

    def test_budget_exhausted_defers_without_advancing_last_success(self):
        old = (NOW - timedelta(days=2)).isoformat()
        st = Rec([src(1, "https://s.example/feed", "manual", old)], KWROWS)
        orig = run.CATCHUP_BUDGET
        run.CATCHUP_BUDGET = -1
        try:
            stats = run.run(st, fetch=self.feed_net(), now=NOW)
        finally:
            run.CATCHUP_BUDGET = orig
        self.assertEqual(stats.catchup_deferred, 1)
        self.assertFalse(st.advance[1])                          # نقطة الاستدراك محفوظة للدورة التالية
        self.assertTrue(st.results[1][0])                        # لكن الأخبار الأحدث جُلبت

    def test_failed_older_page_keeps_catchup_point(self):
        base = self.feed_net()
        def f(u):
            if "paged=3" in u: raise feeds.FeedError("HTTP 500")
            return base(u)
        st = Rec([src(1, "https://s.example/feed", "manual", (NOW - timedelta(days=20)).isoformat())], KWROWS)
        run.run(st, fetch=f, now=NOW)
        self.assertFalse(st.advance[1])

    def test_end_of_archive_404_advances(self):
        st = Rec([src(1, "https://s.example/feed", "manual", (NOW - timedelta(days=20)).isoformat())], KWROWS)
        run.run(st, fetch=self.feed_net(last_page=3), now=NOW)
        self.assertTrue(st.advance[1])

    def test_no_catchup_when_recent(self):
        calls = []
        base = self.feed_net()
        def f(u): calls.append(u); return base(u)
        st = Rec([src(1, "https://s.example/feed", "manual", (NOW - timedelta(minutes=30)).isoformat())], KWROWS)
        run.run(st, fetch=f, now=NOW)
        self.assertEqual([u for u in calls if "paged=" in u], [])


class BackfillTests(unittest.TestCase):
    def setUp(self): feeds._ROBOTS_CACHE.clear()

    def feed(self):
        return rssd([("خبر قديم جداً عن عبين", "https://n.example/old", "d", days(90), "", ""),
                     ("مدرسة في عبين تفتتح", "https://n.example/1", "d", days(10), "", ""),
                     ("وفاة الحاج في عبلين", "https://n.example/2", "d", days(2), "", ""),
                     ("خبر رياضي", "https://n.example/3", "d", days(1), "", "")])

    def test_backfill_records_recent_matches_and_skips_beyond_window(self):
        st = Rec([], KWROWS)
        kws = [matcher.Keyword(k["word"], k["kind"], k["region_id"], k["needs_context"]) for k in KWROWS]
        res = run.backfill_source(st, {"id": 9, "name": "n.example", "url": "https://n.example/feed", "kind": "rss", "origin": "auto"}, kws, lambda u: self.feed(), NOW)
        self.assertEqual(res["inserted"], 2)
        self.assertNotIn("https://n.example/old", [n["original_url"] for n in st.news])
        self.assertEqual(st.results[9], (True, None))

    def test_backfill_does_not_duplicate_existing(self):
        kws = [matcher.Keyword(k["word"], k["kind"], k["region_id"], k["needs_context"]) for k in KWROWS]
        existing = [dict(id=1, title="مدرسة في عبين تفتتح", original_url="https://n.example/1", fingerprint=__import__("momani_fetcher.dedup", fromlist=["x"]).fingerprint("مدرسة في عبين تفتتح"))]
        st = Rec([], KWROWS, news=existing)
        res = run.backfill_source(st, {"id": 9, "name": "n", "url": "https://n.example/feed", "kind": "rss", "origin": "auto"}, kws, lambda u: self.feed(), NOW)
        self.assertEqual((res["inserted"], res["duplicate"]), (1, 1))
        self.assertEqual(len(st.news), 2)

    def test_discovery_triggers_backfill_for_new_source_only(self):
        from tests.test_phase3 import DiscoStore, GOOD_FEED
        st = DiscoStore([{"domain": "good.example", "via": "search", "relevant": False}])
        calls = []
        def bf(store, source, kws, fetch, now): calls.append(source); return {"seen": 3, "matched": 1, "inserted": 1, "duplicate": 0, "error": None}
        rep = discover.run_discovery(st, fetch=Net({"https://good.example/": GOOD_FEED}), now=NOW, backfill=bf)
        self.assertEqual([c["url"] for c in calls], ["https://good.example/"])
        self.assertEqual(calls[0]["id"], 501)
        self.assertIn("جديد 1", rep["backfilled"][0])
        self.assertIn("أثر رجعي", discover.report(rep))
        st2 = DiscoStore([{"domain": "off.example"}])
        calls.clear()
        from tests.test_phase3 import OFFTOPIC
        discover.run_discovery(st2, fetch=Net({"https://off.example/": OFFTOPIC}), now=NOW, backfill=bf)
        self.assertEqual(calls, [])


class LinkStore:
    def __init__(self, news, checks=None): self.news, self.checks, self.saved = news, checks or {}, []
    def recent_news_links(self, days=21, limit=300): return self.news
    def link_checks_for(self, ids): return {i: self.checks[i] for i in ids if i in self.checks}
    def save_link_check(self, row): self.saved.append(row)


class LinkCheckJobTests(unittest.TestCase):
    def setUp(self): feeds._ROBOTS_CACHE.clear()

    def test_statuses_schedule_and_google_links_skipped(self):
        news = [{"id": 1, "title": "افتتاح مدرسة جديدة في عبين", "original_url": "https://s.example/ok"},
                {"id": 2, "title": "افتتاح مدرسة جديدة في عبين", "original_url": "https://s.example/gone"},
                {"id": 3, "title": "افتتاح مدرسة جديدة في عبين", "original_url": "https://s.example/soft"},
                {"id": 4, "title": "x", "original_url": "https://news.google.com/rss/articles/abc"}]
        soft = '<html><head><title>Page not found</title></head><body>' + "x " * 300 + "</body></html>"
        table = {"https://s.example/ok": probe("https://s.example/ok"), "https://s.example/gone": probe("https://s.example/gone", 404),
                 "https://s.example/soft": probe("https://s.example/soft", data=soft.encode())}
        st = LinkStore(news)
        rep = linkcheck.run_linkcheck(st, lambda u: table[u], lambda u: b"", NOW, sleep=lambda s: None)
        by = {r["news_id"]: r for r in st.saved}
        self.assertEqual([by[i]["status"] for i in (1, 2, 3)], ["ok", "broken", "broken"])
        self.assertNotIn(4, by)
        self.assertEqual(by[1]["next_check_at"], (NOW + timedelta(days=7)).isoformat())
        self.assertEqual(len(rep["problems"]), 2)
        self.assertIn("soft-404", by[3]["reason"])
        self.assertIn("معطلة", linkcheck.report(rep))

    def test_not_due_skipped_and_unreachable_escalates(self):
        news = [{"id": 1, "title": "افتتاح مدرسة جديدة في عبين", "original_url": "https://s.example/a"},
                {"id": 2, "title": "افتتاح مدرسة جديدة في عبين", "original_url": "https://s.example/b"}]
        checks = {1: {"status": "ok", "attempts": 0, "next_check_at": (NOW + timedelta(days=3)).isoformat()},
                  2: {"status": "unreachable", "attempts": 2, "next_check_at": (NOW - timedelta(hours=1)).isoformat()}}
        st = LinkStore(news, checks)
        linkcheck.run_linkcheck(st, lambda u: probe(u, 503), lambda u: b"", NOW, sleep=lambda s: None)
        self.assertEqual([r["news_id"] for r in st.saved], [2])
        self.assertEqual(st.saved[0]["status"], "broken")
        self.assertIn("3 محاولات", st.saved[0]["reason"])

    def test_limit_per_run(self):
        news = [{"id": i, "title": "افتتاح مدرسة جديدة في عبين", "original_url": f"https://s.example/{i}"} for i in range(1, 8)]
        st = LinkStore(news)
        rep = linkcheck.run_linkcheck(st, lambda u: probe(u), lambda u: b"", NOW, sleep=lambda s: None, limit=3)
        self.assertEqual((rep["checked"], rep["remaining"]), (3, 4))


if __name__ == "__main__":
    unittest.main()


class ExtendedSearchTests(unittest.TestCase):
    def setUp(self): feeds._ROBOTS_CACHE.clear()

    def test_extended_deduplicated_against_core_and_itself(self):
        ext = queries.EXTENDED
        self.assertEqual(len(ext), len(set(ext)))
        from momani_fetcher.search_terms import _key
        keys = [_key(q) for q in ext]
        self.assertEqual(len(keys), len(set(keys)))
        core = {_key(q) for q in queries.QUERIES}
        self.assertFalse(core & set(keys))
        self.assertNotIn("صخره عجلون", ext)          # = «صخرة عجلون» الأساسية بعد التطبيع
        self.assertNotIn("عَبين", ext)
        self.assertIn("أفراح المومني", ext)
        self.assertIn("بلدية الجنيد", ext)
        self.assertIn('"المومني" "عبين"', ext)
        self.assertGreater(len(ext), 300)

    def test_extended_rows_valid_and_unique(self):
        rows = queries.extended_source_rows()
        self.assertEqual(len({r["url"] for r in rows}), len(rows))
        for r in rows:
            self.assertTrue(queries.is_extended(r["name"]))
            self.assertLessEqual(len(r["name"]), 120)
            self.assertEqual((r["kind"], r["origin"]), ("rss", "search"))

    def ext_sources(self, n=40, last=None):
        return [src(i, f"https://news.google.com/rss/search?q=e{i}", name=queries.EXT_PREFIX + f"e{i}", last=last(i) if last else None) for i in range(1, n + 1)]

    def test_only_a_batch_per_run_oldest_first(self):
        calls = []
        def f(u):
            if not u.endswith("robots.txt"): calls.append(u)
            return rssd([])
        st = Rec(self.ext_sources(40, last=lambda i: (NOW - timedelta(days=2, hours=i)).isoformat() if i > 20 else None), KWROWS)
        stats = run.run(st, "schedule", fetch=f, now=NOW)
        self.assertEqual(len(calls), run.EXT_PER_RUN)
        self.assertEqual(stats.search_skipped, 40 - run.EXT_PER_RUN)
        self.assertTrue(all(any(f"q=e{i}" in c for c in calls) for i in range(1, 16)))   # لم تُفحص قط = الأولى

    def test_checked_within_24h_not_repeated_on_schedule(self):
        calls = []
        def f(u):
            if not u.endswith("robots.txt"): calls.append(u)
            return rssd([])
        recent = (NOW - timedelta(hours=5)).isoformat()
        st = Rec(self.ext_sources(10, last=lambda i: recent), KWROWS)
        run.run(st, "schedule", fetch=f, now=NOW)
        self.assertEqual(calls, [])

    def test_pause_between_search_requests(self):
        pauses = []
        st = Rec(self.ext_sources(3), KWROWS)
        run.run(st, "schedule", fetch=lambda u: rssd([]), now=NOW, pause=pauses.append)
        self.assertEqual(pauses, [run.SEARCH_PAUSE] * 3)

    def test_google_429_stops_all_search(self):
        calls = []
        def f(u):
            if not u.endswith("robots.txt"): calls.append(u)
            raise feeds.FeedError("HTTP 429")
        st = Rec(self.ext_sources(10), KWROWS)
        stats = run.run(st, "schedule", fetch=f, now=NOW)
        self.assertEqual(len(calls), 1)
        self.assertTrue(any("حجب" in h for h in stats.health))

    def test_new_keywords_junaid_and_plural(self):
        self.assertTrue(matcher.evaluate("تعزية المومنيين في الجنيد", "", KWS).matched)
        self.assertFalse(matcher.evaluate("بلدية الجنيد تعلن مناقصة", "", KWS).matched)   # سياق وحده لا يكفي
        self.assertFalse(matcher.evaluate("الوزير المومنيين يصرح", "", KWS).matched)   # الاسم وحده بلا سياق


class ProbeTests(unittest.TestCase):
    def setUp(self): feeds._ROBOTS_CACHE.clear()

    def test_probe_reports_empty_feed_and_html_response(self):
        from momani_fetcher import probe
        empty = validate.Probe(200, "https://news.google.com/rss/search?q=x", "application/xml", rssd([]))
        r = probe.probe_url("https://news.google.com/rss/search?q=x", KWS, lambda u: empty, lambda u: b"")
        self.assertEqual((r["status"], r["items"], r["engine"]), (200, 0, queries.ENGINE_GOOGLE))
        self.assertIn("بداية الاستجابة", probe.report([r]))
        html = validate.Probe(200, "https://consent.google.com/x", "text/html", b"<html>consent</html>")
        r2 = probe.probe_url("https://news.google.com/rss/search?q=x", KWS, lambda u: html, lambda u: b"")
        self.assertIn("parse_error", r2)
        self.assertEqual(r2["final_url"], "https://consent.google.com/x")

    def test_probe_counts_matches(self):
        from momani_fetcher import probe
        ok = validate.Probe(200, "u", "application/xml", rssd([("مدرسة في عبين", "https://p.example/1", "d", None, "", ""), ("خبر رياضي", "https://p.example/2", "d", None, "", "")]))
        r = probe.probe_url("https://news.google.com/rss/search?q=x", KWS, lambda u: ok, lambda u: b"")
        self.assertEqual((r["items"], r["matched"]), (2, 1))

    def test_probe_connection_error(self):
        from momani_fetcher import probe
        def boom(u): raise feeds.FeedError("connection failed: URLError")
        self.assertIn("error", probe.probe_url("https://www.bing.com/news/search?q=x", KWS, boom, lambda u: b""))


class CrashIsolationTests(unittest.TestCase):
    """أصل العطل الحقيقي: استثناء غير متوقع في مصدر واحد كان يُسقط الدورة كلها فلا تُجلب بقية المصادر ولا البحث."""
    def setUp(self): feeds._ROBOTS_CACHE.clear()

    def test_truncated_gzip_sitemap_is_a_feed_error_not_a_crash(self):
        from momani_fetcher import pages
        import gzip
        data = gzip.compress(b"<urlset></urlset>" * 50)[:-6]
        with self.assertRaises(feeds.FeedError):
            pages.parse_sitemap(data)

    def test_unexpected_exception_in_sitemap_fallback_does_not_kill_run(self):
        good = rssd([("مدرسة في عبين", "https://good.example/1", "d", days(0), "", "")])
        def f(u):
            if u == "https://bad.example/feed": return b"<html><body>no feed</body></html>"
            if u.endswith("/sitemap.xml") and "bad.example" in u: raise RuntimeError("boom")
            if "bad.example" in u: raise feeds.FeedError("HTTP 404")
            return good
        st = Rec([{"id": 1, "name": "سيئ", "url": "https://bad.example/feed", "kind": "rss", "origin": "manual"},
                  {"id": 2, "name": "جيد", "url": "https://good.example/feed", "kind": "rss", "origin": "manual"}], KWROWS)
        stats = run.run(st, fetch=f, now=NOW)
        self.assertEqual(stats.items_inserted, 1)             # المصدر الثاني عولج
        self.assertEqual(st.results[2], (True, None))
        self.assertFalse(st.results[1][0])

    def test_any_exception_escaping_process_source_is_isolated_and_logged(self):
        good = rssd([("مدرسة في عبين", "https://good.example/1", "d", days(0), "", "")])
        class Boom(Rec):
            def known_original_urls(self, urls):
                if any("good.example" in u for u in urls): return super().known_original_urls(urls)
                raise ValueError("weird")
        def f(u): return good if "good.example" in u else rssd([("عبين خبر", "https://other.example/1", "d", days(0), "", "")])
        st = Boom([{"id": 1, "name": "غريب", "url": "https://weird.example/feed", "kind": "rss", "origin": "manual"},
                   {"id": 2, "name": "جيد", "url": "https://good.example/feed", "kind": "rss", "origin": "manual"}], KWROWS)
        stats = run.run(st, fetch=f, now=NOW)
        self.assertEqual(stats.sources_failed, 1)
        self.assertEqual(stats.items_inserted, 1)
        self.assertIn("ValueError", st.results[1][1])
        self.assertEqual(len(st.failures), 1)
        self.assertIn("finished", "finished" if "sources_total" in st.runs[0] else "")   # finish_run نُفِّذ


class ArabicUrlTests(unittest.TestCase):
    """أصل العطل الفعلي: رابط عربي غير مرمَّز في sitemap يرمي UnicodeEncodeError داخل http.client فيُسقط الدورة."""
    def test_safe_url_encodes_arabic_and_keeps_encoded_parts(self):
        u = feeds.safe_url("https://sosnews.example/خبر/عبين?q=مدرسة&page=2")
        self.assertTrue(u.isascii())
        self.assertIn("%D8%AE%D8%A8%D8%B1", u)
        self.assertIn("page=2", u)
        again = feeds.safe_url(u)
        self.assertEqual(again, u)                       # لا ترميز مزدوج
        self.assertEqual(feeds.safe_url("https://a.example/x/1?y=2"), "https://a.example/x/1?y=2")

    def test_http_get_sends_ascii_request_for_arabic_url(self):
        from unittest import mock
        seen = []
        class R:
            def __enter__(s): return s
            def __exit__(s, *a): return False
            def read(s, n): return b"ok"
        def fake_open(self, req, timeout=None):
            seen.append(req.full_url); return R()
        with mock.patch("urllib.request.OpenerDirector.open", fake_open):
            self.assertEqual(feeds.http_get("https://s.example/خبر-عبين"), b"ok")
        self.assertTrue(seen[0].isascii())

    def test_unicode_error_becomes_feed_error_not_crash(self):
        from unittest import mock
        def boom(self, req, timeout=None): raise UnicodeEncodeError("ascii", "خ", 0, 1, "bad")
        with mock.patch("urllib.request.OpenerDirector.open", boom):
            with self.assertRaises(feeds.FeedError):
                feeds.http_get("https://s.example/x")


BING_RSS = ('<?xml version="1.0" encoding="utf-8" ?><rss version="2.0" xmlns:News="https://www.bing.com/news/search?q=x&amp;format=rss"><channel>'
            '<item><title>افتتاح مدرسة في عبين</title>'
            '<link>http://www.bing.com/news/apiclick.aspx?ref=FexRss&amp;aid=&amp;tid=ABC123&amp;url=https%3a%2f%2flocal-news.example%2fa%2f1&amp;c=555&amp;mkt=ar-jo</link>'
            '<description>تفاصيل</description><pubDate>Thu, 22 Oct 2026 08:00:00 GMT</pubDate><News:Source>صحيفة محلية</News:Source></item>'
            '<item><title>وفاة الحاج في عبلين</title>'
            '<link>http://www.bing.com/news/apiclick.aspx?ref=FexRss&amp;tid=DEF&amp;url=http%3a%2f%2fold-site.example%2fx%2f2</link>'
            '<description>d</description><News:Source>موقع قديم</News:Source></item></channel></rss>').encode("utf-8")


class BingLinkTests(unittest.TestCase):
    """أصل فقدان أخبار Bing: روابطه http تحويلية فكانت تُحذف بصمت بعد المطابقة (لا مقبولة ولا مرفوضة)."""
    def setUp(self): feeds._ROBOTS_CACHE.clear()

    def test_resolve_link_extracts_publisher_url(self):
        self.assertEqual(feeds.resolve_link("http://www.bing.com/news/apiclick.aspx?ref=FexRss&tid=A&url=https%3a%2f%2fsite.example%2fa%2f1&c=1"), "https://site.example/a/1")
        self.assertEqual(feeds.resolve_link("https://site.example/a/1"), "https://site.example/a/1")
        self.assertEqual(feeds.resolve_link("https://news.google.com/rss/articles/x?url=https%3a%2f%2fe.example"), "https://news.google.com/rss/articles/x?url=https%3a%2f%2fe.example")

    def test_bing_item_parsed_with_real_link_and_publisher(self):
        items = feeds.parse_feed(BING_RSS, NOW)
        self.assertEqual(items[0].link, "https://local-news.example/a/1")
        self.assertEqual(items[0].publisher, "صحيفة محلية")

    def test_bing_news_inserted_and_http_publisher_counted_not_silently_lost(self):
        st = Rec([src(1, B1)], KWROWS)
        stats = run.run(st, fetch=lambda u: BING_RSS, now=NOW)
        self.assertEqual(stats.items_inserted, 1)
        self.assertEqual(st.news[0]["original_url"], "https://local-news.example/a/1")
        self.assertEqual(st.news[0]["source_name"], "صحيفة محلية")
        self.assertEqual(stats.items_dropped_link, 1)                   # الناشر http: يُحصى ويظهر في التقرير
        rep = run.report(stats)
        self.assertIn("بلا رابط https صالح", rep)
        self.assertIn("old-site.example", rep)


class RobotsBlockedEngineTests(unittest.TestCase):
    def setUp(self): feeds._ROBOTS_CACHE.clear()

    def test_engine_blocked_by_robots_is_stopped_not_counted_as_failures(self):
        calls = []
        def f(u):
            if u.endswith("/robots.txt"):
                return b"User-agent: *\nDisallow: /rss/search\n" if "google" in u else b""
            calls.append(u)
            return rssd([])
        st = Rec([src(i, f"https://news.google.com/rss/search?q=x{i}") for i in range(1, 6)] + [src(9, B1)], KWROWS)
        stats = run.run(st, fetch=f, now=NOW)
        self.assertFalse(any("news.google" in c for c in calls))        # لم يُطرق Google إطلاقاً
        self.assertEqual(stats.sources_failed, 0)
        self.assertEqual(stats.engines_blocked, [queries.ENGINE_GOOGLE])
        self.assertTrue(any("robots.txt لا يسمح" in h for h in stats.health))
        self.assertEqual(st.results[9], (True, None))                   # Bing عولج
