import unittest

from momani_fetcher import feeds, probe_sources

RSS = b'<?xml version="1.0"?><rss version="2.0"><channel><item><title>t</title><link>https://s.example/1</link></item></channel></rss>'
HOME = b'<html><head><link rel="alternate" type="application/rss+xml" href="https://s.example/feed.xml"></head><body>x</body></html>'
SITEMAP = b'<?xml version="1.0"?><urlset xmlns="http://www.sitemaps.org/schemas/sitemap/0.9"><url><loc>https://s.example/a</loc></url></urlset>'
SRC = {"name": "موقع", "url": "https://s.example/rss.php", "last_error": "invalid XML"}


def fetcher(table):
    def f(url):
        v = table.get(url)
        if v is None:
            raise feeds.FeedError("HTTP 404")
        if isinstance(v, Exception):
            raise v
        return v
    return f


class DiagnoseTests(unittest.TestCase):
    def setUp(self): feeds._ROBOTS_CACHE.clear()

    def test_finds_working_declared_feed(self):
        r = probe_sources.diagnose(SRC, fetcher({"https://s.example/": HOME, "https://s.example/feed.xml": RSS}))
        self.assertIn("✅ خلاصة تعمل: https://s.example/feed.xml", r["suggestion"])

    def test_robots_disallow_means_no_workaround(self):
        robots = b"User-agent: *\nDisallow: /\n"
        r = probe_sources.diagnose(SRC, fetcher({"https://s.example/robots.txt": robots, "https://s.example/": HOME}))
        self.assertIn("robots.txt يمنع", r["suggestion"])
        self.assertEqual(r["feeds"], [])        # لم نجلب شيئاً بعد المنع

    def test_site_down(self):
        r = probe_sources.diagnose(SRC, fetcher({"https://s.example/": feeds.FeedError("HTTP 403")}))
        self.assertIn("لا يستجيب لنا", r["suggestion"])
        self.assertIn("403", r["suggestion"])

    def test_sitemap_fallback(self):
        r = probe_sources.diagnose(SRC, fetcher({"https://s.example/": b"<html>no feed</html>", "https://s.example/sitemap.xml": SITEMAP}))
        self.assertIn("✅ sitemap يعمل: https://s.example/sitemap.xml", r["suggestion"])

    def test_wordpress_api(self):
        r = probe_sources.diagnose(SRC, fetcher({"https://s.example/": b"<html>x</html>", "https://s.example/wp-json/wp/v2/posts?per_page=1": b'[{"id":1}]'}))
        self.assertIn("WordPress API متاح", r["suggestion"])

    def test_nothing_works(self):
        r = probe_sources.diagnose(SRC, fetcher({"https://s.example/": b"<html>x</html>"}))
        self.assertIn("غير قابل للجلب", r["suggestion"])

    def test_report_lists_each_source(self):
        r = probe_sources.diagnose(SRC, fetcher({"https://s.example/": HOME, "https://s.example/feed.xml": RSS}))
        text = probe_sources.report([r])
        self.assertIn("موقع", text); self.assertIn("الاقتراح", text)


if __name__ == "__main__":
    unittest.main()
