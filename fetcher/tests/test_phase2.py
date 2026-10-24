"""المرحلة 2: مصادر بلا RSS (sitemap وصفحات المقالات)، احترام robots، الذاكرة، والرجوع التلقائي من RSS مفقود."""
import gzip
import unittest
from datetime import datetime, timezone

from momani_fetcher import feeds, pages, run
from tests.test_fetcher import KWROWS, FakeStore, NOW

ARTICLE = """<!doctype html><html><head><meta charset="utf-8">
<meta property="og:title" content="افتتاح مدرسة جديدة في عبين">
<meta property="og:description" content="خبر قصير">
<meta property="article:published_time" content="2026-10-20T08:00:00Z">
<title>موقع</title></head><body><nav><p>قائمة</p></nav>
<p>%s</p></body></html>"""


def article(text="في بلدة عبين افتتحت اليوم مدرسة جديدة بحضور أهالي المنطقة والمسؤولين المحليين"):
    return (ARTICLE % text).encode("utf-8")


SITEMAP = """<?xml version="1.0"?><urlset xmlns="http://www.sitemaps.org/schemas/sitemap/0.9" xmlns:news="http://www.google.com/schemas/sitemap-news/0.9">
<url><loc>https://s.example/a/1</loc><lastmod>2026-10-20T08:00:00Z</lastmod></url>
<url><loc>https://s.example/a/2</loc><lastmod>2026-10-19T08:00:00Z</lastmod><news:news><news:title>عنوان</news:title></news:news></url>
<url><loc>http://s.example/a/3</loc><lastmod>2026-10-18T08:00:00Z</lastmod></url>
</urlset>""".encode("utf-8")


class Net:
    """شبكة وهمية: dict url->bytes؛ غير الموجود = 404. تسجّل الطلبات."""
    def __init__(self, table): self.table, self.calls = table, []
    def __call__(self, url):
        self.calls.append(url)
        if url not in self.table:
            raise feeds.FeedError("HTTP 404")
        return self.table[url]


class ExtractTests(unittest.TestCase):
    def test_extract_page_fields(self):
        it = pages.extract_page(article(), "https://s.example/a/1", datetime(2026, 10, 21, tzinfo=timezone.utc))
        self.assertEqual(it.title, "افتتاح مدرسة جديدة في عبين")
        self.assertEqual(it.summary, "خبر قصير")
        self.assertEqual(it.published.isoformat(), "2026-10-20T08:00:00+00:00")
        self.assertIn("عبين", it.text); self.assertNotIn("قائمة", it.text)

    def test_cp1256_page(self):
        html = '<html><head><meta charset="windows-1256"><meta property="og:title" content="عبين"></head><body></body></html>'
        it = pages.extract_page(html.encode("cp1256"), "https://s.example/x", NOW)
        self.assertEqual(it.title, "عبين")

    def test_non_html_ignored(self):
        self.assertIsNone(pages.extract_page(b"%PDF-1.4 ...", "https://s.example/x", NOW))

    def test_links_filtering(self):
        html = b'<a href="/news/2026/post-1">a</a><a href="https://other.example/x/y">b</a><a href="/img/a.jpg">c</a><a href="/">h</a><a href="http://s.example/a/b">i</a><a href="/post-77">d</a>'
        links = pages.extract_links(html, "https://s.example/")
        self.assertEqual(links, ["https://s.example/news/2026/post-1", "https://s.example/post-77"])


class SitemapTests(unittest.TestCase):
    def test_urlset_sorted_newest_first(self):
        _, entries = pages.parse_sitemap(SITEMAP)
        self.assertEqual([e["loc"] for e in entries][:2], ["https://s.example/a/1", "https://s.example/a/2"])
        self.assertEqual(entries[1]["title"], "عنوان")

    def test_index_and_gzip(self):
        idx = b'<sitemapindex xmlns="http://www.sitemaps.org/schemas/sitemap/0.9"><sitemap><loc>https://s.example/old.xml</loc><lastmod>2020-01-01</lastmod></sitemap><sitemap><loc>https://s.example/new.xml</loc><lastmod>2026-10-01</lastmod></sitemap></sitemapindex>'
        children, entries = pages.parse_sitemap(idx)
        self.assertEqual(children[0], "https://s.example/new.xml"); self.assertEqual(entries, [])
        _, e2 = pages.parse_sitemap(gzip.compress(SITEMAP))
        self.assertEqual(len(e2), 3)

    def test_rejects_entities_and_garbage(self):
        with self.assertRaises(feeds.FeedError): pages.parse_sitemap(b'<!DOCTYPE x [<!ENTITY a "b">]><urlset/>')
        with self.assertRaises(feeds.FeedError): pages.parse_sitemap(b"<html></html>")

    def test_discover_from_robots_and_common_paths(self):
        net = Net({"https://s.example/robots.txt": b"User-agent: *\nSitemap: https://s.example/map.xml\nSitemap: https://evil.example/x.xml\n"})
        found = pages.discover_sitemaps("https://s.example/", net)
        self.assertEqual(found[0], "https://s.example/map.xml")
        self.assertNotIn("https://evil.example/x.xml", found)
        self.assertIn("https://s.example/news-sitemap.xml", found)


def src(kind="html", url="https://s.example/sitemap.xml"):
    return [{"id": 1, "name": "موقع", "url": url, "kind": kind}]


class RunSitemapTests(unittest.TestCase):
    def table(self):
        return {"https://s.example/sitemap.xml": SITEMAP,
                "https://s.example/a/1": article(),
                "https://s.example/a/2": article("نص عن أسعار النفط العالمية والاقتصاد بلا أي صلة بالمناطق المقصودة هنا أبداً"),
                }

    def test_sitemap_source_inserts_only_relevant_pending(self):
        t = self.table(); t["https://s.example/a/2"] = article("نص عن أسعار النفط العالمية والاقتصاد بلا أي صلة بالمناطق المقصودة هنا أبداً").replace("افتتاح مدرسة جديدة في عبين".encode(), "أسعار النفط".encode())
        store = FakeStore(src(), KWROWS)
        st = run.run(store, fetch=Net(t), sleep=lambda s: None)
        self.assertEqual(st.items_inserted, 1)
        self.assertEqual(store.news[0]["status"], "pending")
        self.assertEqual(store.news[0]["original_url"], "https://s.example/a/1")
        self.assertEqual(store.news[0]["region_id"], "abeen")
        self.assertEqual(store.news[0]["source_published_at"], "2026-10-20T08:00:00+00:00")
        self.assertEqual(st.sources[0]["via"], "sitemap/صفحات")
        self.assertNotIn("http://s.example/a/3", [n["original_url"] for n in store.news])  # http مرفوض

    def test_seen_pages_not_refetched(self):
        t = self.table()
        seen: set = set()
        class Mem(FakeStore):
            def known_seen_urls(self, urls): return {u for u in urls if u in seen}
            def mark_seen(self, urls, sid=None): seen.update(urls)
        store = Mem(src(), KWROWS)
        net1 = Net(t); run.run(store, fetch=net1, sleep=lambda s: None)
        self.assertIn("https://s.example/a/2", net1.calls)
        net2 = Net(t); st2 = run.run(store, fetch=net2, sleep=lambda s: None)
        self.assertNotIn("https://s.example/a/1", net2.calls); self.assertNotIn("https://s.example/a/2", net2.calls)
        self.assertEqual(st2.items_inserted, 0)

    def test_page_cap_and_pause(self):
        many = "".join(f"<url><loc>https://s.example/p/{i}</loc><lastmod>2026-10-{(i % 28) + 1:02d}</lastmod></url>" for i in range(60))
        t = {"https://s.example/sitemap.xml": f'<urlset xmlns="http://www.sitemaps.org/schemas/sitemap/0.9">{many}</urlset>'.encode()}
        net = Net(t); pauses = []
        run.run(FakeStore(src(), KWROWS), fetch=net, sleep=pauses.append)
        fetched = [u for u in net.calls if u.startswith("https://s.example/p/")]
        self.assertEqual(len(fetched), pages.MAX_PAGES_PER_SOURCE)
        self.assertEqual(len(pauses), pages.MAX_PAGES_PER_SOURCE - 1)

    def test_robots_blocks_sitemap_source(self):
        t = self.table(); t["https://s.example/robots.txt"] = b"User-agent: *\nDisallow: /\n"
        feeds._ROBOTS_CACHE.clear()
        store = FakeStore(src(), KWROWS)
        st = run.run(store, fetch=Net(t), sleep=lambda s: None)
        feeds._ROBOTS_CACHE.clear()
        self.assertEqual((st.sources_failed, st.items_inserted), (1, 0))
        self.assertIn("robots", store.results[1][1])

    def test_listing_page_source(self):
        listing = b'<html><body><a href="/news/2026/school-1">x</a></body></html>'
        t = {"https://s.example/news": listing, "https://s.example/news/2026/school-1": article()}
        store = FakeStore(src(url="https://s.example/news"), KWROWS)
        st = run.run(store, fetch=Net(t), sleep=lambda s: None)
        self.assertEqual(st.items_inserted, 1)

    def test_rss_source_without_feed_falls_back_to_sitemap(self):
        home = b"<!doctype html><html><head></head><body>home</body></html>"
        t = {"https://s.example/": home, "https://s.example/sitemap.xml": SITEMAP,
             "https://s.example/a/1": article(), "https://s.example/a/2": article("نص بلا صلة بالمنطقة إطلاقاً ولا بأي شيء مما نبحث عنه هنا")}
        t["https://s.example/a/2"] = t["https://s.example/a/2"].replace("افتتاح مدرسة جديدة في عبين".encode(), "خبر عام".encode())
        feeds._ROBOTS_CACHE.clear()
        store = FakeStore(src(kind="rss", url="https://s.example/"), KWROWS)
        st = run.run(store, fetch=Net(t), sleep=lambda s: None)
        self.assertEqual((st.sources_failed, st.items_inserted), (0, 1))
        self.assertEqual(store.results[1], (True, None))

    def test_no_feed_and_no_sitemap_still_reports_error(self):
        home = b"<!doctype html><html><head></head><body>home</body></html>"
        feeds._ROBOTS_CACHE.clear()
        store = FakeStore(src(kind="rss", url="https://s.example/"), KWROWS)
        st = run.run(store, fetch=Net({"https://s.example/": home}), sleep=lambda s: None)
        self.assertEqual(st.sources_failed, 1)


if __name__ == "__main__":
    unittest.main()
