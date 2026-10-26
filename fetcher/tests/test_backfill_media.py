import unittest

from momani_fetcher import backfill_media as bm
from momani_fetcher.feeds import FeedError

PAGE = ('<html><head><meta property="og:title" content="عنوان">'
        '<meta property="og:image" content="https://s.com/p.jpg"></head><body></body></html>').encode()
PAGE_VIDEO = PAGE.replace(b"</head>", b'<meta property="og:video" content="https://s.com/v.mp4"></head>')
NO_MEDIA = b'<html><head><meta property="og:title" content="x"></head></html>'


class FakeStore:
    def __init__(self, rows):
        self.rows, self.written = rows, []

    def news_without_image(self, limit, before_id=None):
        rows = [r for r in self.rows if not before_id or r["id"] < before_id]
        return sorted(rows, key=lambda r: -r["id"])[:limit]

    def set_media(self, nid, img, vid):
        self.written.append((nid, img, vid))


def fetcher(pages):
    def f(url):
        if url.endswith("/robots.txt"):
            host = url.split("/robots.txt")[0]
            if host in pages.get("__robots_block__", ()):
                return b"User-agent: *\nDisallow: /"
            return b""
        v = pages.get(url)
        if v is None:
            raise FeedError("HTTP 404")
        return v
    return f


class BackfillTests(unittest.TestCase):
    def setUp(self):
        from momani_fetcher import feeds
        feeds._ROBOTS_CACHE.clear()   # الذاكرة المؤقتة عامة؛ نعزل كل اختبار

    def rows(self):
        return [{"id": 5, "original_url": "https://a.com/1"}, {"id": 4, "original_url": "https://b.com/2"},
                {"id": 3, "original_url": "https://c.com/3"}, {"id": 2, "original_url": "https://d.com/4"},
                {"id": 1, "original_url": "http://e.com/5"}]

    def test_dry_run_reports_but_does_not_write(self):
        st = FakeStore(self.rows())
        pages = {"https://a.com/1": PAGE, "https://b.com/2": PAGE_VIDEO, "https://c.com/3": NO_MEDIA,
                 "__robots_block__": ("https://d.com",), "https://d.com/4": PAGE}
        rep = bm.run_backfill(st, fetcher(pages), sleep=lambda s: None, dry_run=True)
        self.assertEqual([], st.written)
        self.assertEqual((5, 2, 1, 1, 1), (rep["seen"], rep["images"], rep["videos"], rep["nothing"] - 1, rep["robots"]))
        self.assertEqual(1, rep["last_id"])

    def test_real_run_writes_media_only_when_found(self):
        st = FakeStore(self.rows())
        pages = {"https://a.com/1": PAGE, "https://b.com/2": PAGE_VIDEO, "https://c.com/3": NO_MEDIA}
        bm.run_backfill(st, fetcher(pages), sleep=lambda s: None, dry_run=False)
        self.assertEqual([(5, "https://s.com/p.jpg", None), (4, "https://s.com/p.jpg", "https://s.com/v.mp4")], st.written)

    def test_errors_counted_and_non_https_skipped(self):
        st = FakeStore(self.rows())
        rep = bm.run_backfill(st, fetcher({}), sleep=lambda s: None, dry_run=False)
        self.assertEqual(4, rep["errors"])      # 404 لأربعة روابط https
        self.assertEqual(1, rep["nothing"])     # رابط http متجاوَز
        self.assertEqual([], st.written)

    def test_before_id_continues_with_older(self):
        st = FakeStore(self.rows())
        rep = bm.run_backfill(st, fetcher({}), sleep=lambda s: None, dry_run=True, before_id=3)
        self.assertEqual(2, rep["seen"])

    def test_limit_is_capped(self):
        rows = [{"id": i, "original_url": f"https://x.com/{i}"} for i in range(1, 400)]
        rep = bm.run_backfill(FakeStore(rows), fetcher({}), sleep=lambda s: None, limit=10_000)
        self.assertEqual(bm.MAX_LIMIT, rep["seen"])

    def test_sleep_between_fetches_only(self):
        calls = []
        bm.run_backfill(FakeStore(self.rows()[:3]), fetcher({}), sleep=calls.append, dry_run=True, delay=2)
        self.assertEqual([2, 2], calls)

    def test_report_lists_samples_and_continuation(self):
        st = FakeStore(self.rows())
        rep = bm.run_backfill(st, fetcher({"https://a.com/1": PAGE}), sleep=lambda s: None, dry_run=True)
        text = bm.report(rep)
        self.assertIn("dry-run", text)
        self.assertIn("#5: https://s.com/p.jpg", text)
        self.assertIn("before_id=1", text)


if __name__ == "__main__":
    unittest.main()
