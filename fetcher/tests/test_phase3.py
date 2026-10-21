"""المرحلة 3: استعلامات البحث، واجهة WordPress، الاستدراك بعد الانقطاع، واكتشاف المصادر وسجل المرشحين."""
import json
import unittest
from datetime import datetime, timedelta, timezone
from urllib.parse import unquote

from momani_fetcher import discover, feeds, matcher, queries, run, wpapi
from tests.test_fetcher import KW, KWROWS, FakeStore
from tests.test_phase1 import rss
from tests.test_phase2 import SITEMAP, Net, article

NOW = datetime(2026, 10, 21, 12, 0, tzinfo=timezone.utc)


class QueryTests(unittest.TestCase):
    def test_variety_and_spellings(self):
        q = queries.QUERIES
        for must in ("عبين", "عبّين", "عبلين", "عبلّين", "صخرة عجلون", "عشيرة المومني", "آل المومني", "Abeen Ajloun"):
            self.assertIn(must, q)
        self.assertTrue(any("وفاة" in x and "تعزية" in x and "تهنئة" in x and "نجاح" in x for x in q))  # اجتماعي
        self.assertEqual(len(q), len(set(q)))

    def test_rows_valid(self):
        rows = queries.search_source_rows()
        self.assertEqual(len(rows), len(queries.QUERIES))
        self.assertEqual(len({r["url"] for r in rows}), len(rows))
        for r in rows:
            self.assertTrue(r["url"].startswith("https://news.google.com/rss/search?q="))
            self.assertLessEqual(len(r["name"]), 120)
            self.assertEqual((r["kind"], r["origin"]), ("rss", "search"))
        first = rows[0]["url"]
        self.assertIn("hl=ar&gl=JO", first)
        self.assertEqual(unquote(first.split("q=")[1].split("&")[0]), queries.QUERIES[0])


def wp(items):
    return json.dumps([{"link": l, "date_gmt": d, "title": {"rendered": t}, "excerpt": {"rendered": e}, "content": {"rendered": c}}
                       for (l, d, t, e, c) in items]).encode("utf-8")


class WpApiTests(unittest.TestCase):
    API = "https://w.example/wp-json/wp/v2/posts"

    def test_parse(self):
        its = wpapi.parse_posts(wp([("https://w.example/a", "2026-10-20T08:00:00", "افتتاح في &#1593;بين", "<p>م</p>", "<p>نص طويل</p>")]), NOW)
        self.assertEqual(its[0].published.isoformat(), "2026-10-20T08:00:00+00:00")
        self.assertEqual(its[0].summary, "م"); self.assertEqual(its[0].text, "نص طويل")

    def test_rejects_non_list(self):
        with self.assertRaises(feeds.FeedError): wpapi.parse_posts(b'{"code":"x"}')
        with self.assertRaises(feeds.FeedError): wpapi.parse_posts(b"<html>")

    def test_since_filter_paging_and_search(self):
        calls = []
        full = wp([(f"https://w.example/p{i}", "2026-10-20T08:00:00", f"خبر {i}", "", "") for i in range(wpapi.PER_PAGE)])
        def fetch(url):
            calls.append(url)
            if url.endswith("robots.txt"): raise feeds.FeedError("HTTP 404")
            if "search=" in url: return wp([("https://w.example/old", "2026-09-01T00:00:00", "افتتاح في عبين", "", "")])
            if "page=1" in url: return full
            if "page=2" in url: return wp([("https://w.example/last", "2026-10-20T09:00:00", "آخر", "", "")])
            raise feeds.FeedError("HTTP 400")
        feeds._ROBOTS_CACHE.clear()
        since = NOW - timedelta(hours=5)
        out = wpapi.collect(self.API, fetch, since, ["عبين", "عبلين"], max_pages=5, now=NOW)
        self.assertEqual(len(out), wpapi.PER_PAGE + 1 + 1)  # صفحتان + نتيجة بحث (والبحث الثاني مكرر)
        self.assertTrue(any("after=2026-10-21T07%3A00%3A00" in u for u in calls))
        self.assertEqual(sum("search=" in u for u in calls), 2)
        self.assertFalse(any("page=3" in u for u in calls))  # الصفحة الثانية أقل من 50 = النهاية

    def test_lookback_capped_at_30_days(self):
        calls = []
        def fetch(url):
            calls.append(url)
            if url.endswith("robots.txt"): raise feeds.FeedError("HTTP 404")
            return wp([])
        feeds._ROBOTS_CACHE.clear()
        wpapi.collect(self.API, fetch, NOW - timedelta(days=200), [], now=NOW)
        self.assertTrue(any("after=2026-09-21" in u for u in calls))

    def test_run_uses_wp_source_and_dedups(self):
        net = Net({"https://w.example/wp-json/wp/v2/posts?per_page=50&orderby=date&order=desc&_fields=link,date_gmt,title,excerpt,content": b""})
        posts = wp([("https://w.example/a/1", "2026-10-20T08:00:00", "افتتاح مدرسة في عبين", "", "تفاصيل"),
                    ("https://w.example/a/2", "2026-10-20T07:00:00", "أسعار النفط", "", "")])
        def fetch(url):
            if url.endswith("robots.txt"): raise feeds.FeedError("HTTP 404")
            return posts
        feeds._ROBOTS_CACHE.clear()
        store = FakeStore([{"id": 1, "name": "ووردبريس", "url": self_api(), "kind": "html"}], KWROWS)
        st = run.run(store, fetch=fetch, sleep=lambda s: None)
        self.assertEqual((st.sources_failed, st.items_inserted), (0, 1))
        st2 = run.run(store, fetch=fetch, sleep=lambda s: None)
        self.assertEqual(st2.items_inserted, 0)


def self_api(): return "https://w.example/wp-json/wp/v2/posts"


class CatchupTests(unittest.TestCase):
    def test_gap_triggers_catchup_pages(self):
        data1 = rss([("افتتاح في عبين", "https://a.example/n/1", "d", "", "")])
        data2 = rss([("مدرسة في عبلين", "https://a.example/n/2", "d", "", "")])
        pages = {"https://a.example/feed": data1, "https://a.example/feed?paged=2": data2}
        def get(u):
            if u not in pages: raise feeds.FeedError("HTTP 404")
            return pages[u]
        def fresh(last):
            return [{"id": 1, "name": "أ", "url": "https://a.example/feed", "kind": "rss", "last_success_at": last}]
        recent = (datetime.now(timezone.utc) - timedelta(minutes=30)).isoformat()
        old = (datetime.now(timezone.utc) - timedelta(hours=10)).isoformat()
        s1 = FakeStore(fresh(recent), KWROWS); st1 = run.run(s1, fetch=get)
        self.assertEqual(st1.items_inserted, 1); self.assertFalse(st1.sources[0].get("catchup"))
        s2 = FakeStore(fresh(old), KWROWS); st2 = run.run(s2, fetch=get)
        self.assertEqual(st2.items_inserted, 2); self.assertTrue(st2.sources[0]["catchup"])
        self.assertIn("استدراك", run.report(st2))

    def test_never_succeeded_is_not_catchup(self):
        data = rss([("افتتاح في عبين", "https://a.example/n/1", "d", "", "")])
        st = run.run(FakeStore([{"id": 1, "name": "أ", "url": "https://a.example/feed", "kind": "rss", "last_success_at": None}], KWROWS), fetch=lambda u: data)
        self.assertFalse(st.sources[0].get("catchup"))


class CandidateNotingTests(unittest.TestCase):
    def test_publisher_domains_noted_once_and_google_skipped(self):
        data = ("""<?xml version="1.0"?><rss version="2.0"><channel>
        <item><title>افتتاح في عبين - الرأي</title><link>https://news.google.com/rss/articles/1</link><description>d</description><source url="https://www.alrai.example">الرأي</source></item>
        <item><title>مدرسة في عبلين - الرأي</title><link>https://news.google.com/rss/articles/2</link><description>d</description><source url="https://alrai.example/">الرأي</source></item>
        <item><title>خبر في عبين - غوغل</title><link>https://news.google.com/rss/articles/3</link><description>d</description><source url="https://news.google.com">غوغل</source></item>
        </channel></rss>""").encode("utf-8")
        noted = []
        class S(FakeStore):
            def note_candidate(self, d, via="search", relevant=False): noted.append((d, via, relevant))
        run.run(S([{"id": 1, "name": "بحث", "url": "https://news.google.com/rss/search?q=x", "kind": "rss"}], KWROWS), fetch=lambda u: data if "search" in u else b"")
        self.assertEqual(noted, [("alrai.example", "search", True)])


class DiscoStore:
    def __init__(self, cands=(), have=(), news=(), stale=()):
        self.cands = {c["domain"]: dict(c, status=c.get("status", "new")) for c in cands}
        self.have, self.news, self._stale = set(have), set(news), list(stale)
        self.sources_added, self.upserts, self.disabled = [], [], []
    def active_keywords(self): return KWROWS
    def upsert_sources(self, rows): self.upserts.append(rows)
    def news_domains(self): return self.news
    def source_domains(self): return self.have
    def note_candidate(self, d, via="search", relevant=False): self.cands.setdefault(d, {"domain": d, "via": via, "relevant": relevant, "status": "new"})
    def candidates_due(self, limit=40): return [c for c in self.cands.values() if c["status"] in ("new",)][:limit]
    def update_candidate(self, d, f): self.cands.setdefault(d, {"domain": d}).update(f)
    def add_source(self, row): self.sources_added.append(row); return 500 + len(self.sources_added)
    def stale_auto_sources(self, cutoff): return self._stale
    def set_source_active(self, i, a): self.disabled.append(i)


GOOD_FEED = rss([("افتتاح مدرسة في عبين", "https://good.example/1", "d", "", ""), ("خبر ٢", "https://good.example/2", "d", "", ""), ("خبر ٣", "https://good.example/3", "d", "", "")])
OFFTOPIC = rss([(f"خبر عام {i}", f"https://off.example/{i}", "d", "", "") for i in range(4)])


class DiscoveryTests(unittest.TestCase):
    def setUp(self): feeds._ROBOTS_CACHE.clear()

    def test_registers_valid_relevant_feed_and_persists_result(self):
        st = DiscoStore([{"domain": "good.example", "via": "search", "relevant": False}])
        rep = discover.run_discovery(st, fetch=Net({"https://good.example/": GOOD_FEED}), now=NOW)
        self.assertEqual(len(st.sources_added), 1)
        row = st.sources_added[0]
        self.assertEqual((row["kind"], row["origin"], row["relevance_hits"]), ("rss", "auto", 1))
        self.assertEqual(st.cands["good.example"]["status"], "registered")
        self.assertEqual(st.cands["good.example"]["source_id"], 501)
        self.assertEqual(len(st.upserts[0]), len(queries.QUERIES))
        self.assertIn("good.example", discover.report(rep))

    def test_offtopic_rejected_with_recheck_date(self):
        st = DiscoStore([{"domain": "off.example"}])
        discover.run_discovery(st, fetch=Net({"https://off.example/": OFFTOPIC}), now=NOW)
        c = st.cands["off.example"]
        self.assertEqual(c["status"], "rejected"); self.assertIn("لا خبر", c["reason"])
        self.assertEqual(c["next_check_at"], (NOW + timedelta(days=30)).isoformat())
        self.assertEqual(st.sources_added, [])

    def test_search_relevance_overrides_empty_sample(self):
        st = DiscoStore([{"domain": "off.example", "relevant": True}])
        discover.run_discovery(st, fetch=Net({"https://off.example/": OFFTOPIC}), now=NOW)
        self.assertEqual(len(st.sources_added), 1)

    def test_robots_blocked_never_fetches_feed(self):
        net = Net({"https://b.example/robots.txt": b"User-agent: *\nDisallow: /\n", "https://b.example/": GOOD_FEED})
        st = DiscoStore([{"domain": "b.example"}])
        discover.run_discovery(st, fetch=net, now=NOW)
        self.assertEqual(st.cands["b.example"]["status"], "blocked")
        self.assertNotIn("https://b.example/", net.calls)
        self.assertEqual(st.cands["b.example"]["next_check_at"], (NOW + timedelta(days=90)).isoformat())

    def test_dead_feed_rejected(self):
        old = rss([(f"عبين {i}", f"https://d.example/{i}", "d", "", "") for i in range(4)]).replace(b"</item>", b"<pubDate>Mon, 01 Jan 2024 08:00:00 +0000</pubDate></item>")
        st = DiscoStore([{"domain": "d.example"}])
        discover.run_discovery(st, fetch=Net({"https://d.example/": old}), now=NOW)
        self.assertEqual(st.cands["d.example"]["status"], "rejected"); self.assertIn("لا نشر", st.cands["d.example"]["reason"])

    def test_wordpress_api_fallback(self):
        posts = wp([(f"https://w.example/{i}", "2026-10-20T08:00:00", "افتتاح في عبين" if i == 0 else f"خبر {i}", "", "") for i in range(4)])
        home = b"<!doctype html><html><head></head><body></body></html>"
        net = Net({"https://w.example/": home, f"https://w.example/wp-json/wp/v2/posts?per_page=20&_fields={wpapi.FIELDS}": posts})
        st = DiscoStore([{"domain": "w.example"}])
        discover.run_discovery(st, fetch=net, now=NOW)
        self.assertEqual(st.sources_added[0]["url"], "https://w.example/wp-json/wp/v2/posts")
        self.assertEqual(st.sources_added[0]["kind"], "html")

    def test_sitemap_fallback_requires_recent_entries(self):
        home = b"<!doctype html><html><head></head><body></body></html>"
        recent = SITEMAP.replace(b"2026-10-20", b"2026-10-19")
        st = DiscoStore([{"domain": "s.example", "relevant": True}])
        discover.run_discovery(st, fetch=Net({"https://s.example/": home, "https://s.example/sitemap.xml": recent}), now=NOW)
        self.assertEqual(st.sources_added[0]["url"], "https://s.example/sitemap.xml")
        old = SITEMAP.replace(b"2026", b"2019")
        st2 = DiscoStore([{"domain": "t.example", "relevant": True}])
        discover.run_discovery(st2, fetch=Net({"https://t.example/": home, "https://t.example/sitemap.xml": old}), now=NOW)
        self.assertEqual(st2.sources_added, []); self.assertEqual(st2.cands["t.example"]["status"], "rejected")

    def test_cap_on_new_sources_per_run(self):
        doms = [f"g{i}.example" for i in range(15)]
        table = {f"https://{d}/": GOOD_FEED for d in doms}
        st = DiscoStore([{"domain": d} for d in doms])
        discover.run_discovery(st, fetch=Net(table), now=NOW)
        self.assertEqual(len(st.sources_added), discover.MAX_NEW_SOURCES)
        self.assertEqual(sum(1 for c in st.cands.values() if c["status"] == "new"), 5)  # يُفحصون لاحقاً

    def test_known_and_skipped_domains_not_probed(self):
        net = Net({})
        st = DiscoStore([{"domain": "have.example"}, {"domain": "news.google.com"}, {"domain": "facebook.com"}], have={"have.example"})
        rep = discover.run_discovery(st, fetch=net, now=NOW)
        self.assertEqual(rep["probed"], 0); self.assertEqual(net.calls, [])
        self.assertEqual(st.cands["have.example"]["status"], "registered")

    def test_news_domains_become_candidates_but_skip_social(self):
        st = DiscoStore(news={"site.example", "facebook.com", "news.google.com"})
        discover.run_discovery(st, fetch=Net({}), now=NOW)
        self.assertIn("site.example", st.cands); self.assertNotIn("facebook.com", st.cands); self.assertNotIn("news.google.com", st.cands)

    def test_stale_auto_sources_disabled(self):
        st = DiscoStore(stale=[{"id": 7, "url": "https://dead.example/feed", "name": "ميت"}])
        rep = discover.run_discovery(st, fetch=Net({}), now=NOW)
        self.assertEqual(st.disabled, [7]); self.assertEqual(rep["disabled"], ["ميت"])
        self.assertEqual(st.cands["dead.example"]["status"], "stale")


class MatcherRefinementTests(unittest.TestCase):
    def test_generic_context_not_enough_for_name(self):
        m = matcher.evaluate("المومني يفتتح بلدية جديدة في قرية", "", KW)
        self.assertFalse(m.matched); self.assertEqual(m.reason, matcher.R_NAME_NO_CONTEXT)
        self.assertTrue(matcher.evaluate("المومني في عجلون", "", KW).matched)


if __name__ == "__main__":
    unittest.main()
