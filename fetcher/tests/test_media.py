import unittest
from xml.etree import ElementTree as ET

from momani_fetcher import media
from momani_fetcher.feeds import parse_feed
from momani_fetcher.wpapi import parse_posts
from momani_fetcher.pages import extract_page


class MediaTests(unittest.TestCase):
    def test_clean_url_https_only(self):
        self.assertEqual("https://a.com/x.jpg", media.clean_url(" https://a.com/x.jpg "))
        self.assertIsNone(media.clean_url("http://a.com/x.jpg"))
        self.assertIsNone(media.clean_url("data:image/png;base64,AAAA"))
        self.assertIsNone(media.clean_url("https://a.com/x y.jpg"))
        self.assertIsNone(media.clean_url("https://" + "a" * 1100))

    def test_first_image_skips_pixels_logos_gif(self):
        raw = ('<p><img src="https://t.com/pixel.png" width="1" height="1">'
               '<img src="https://t.com/logo.png"><img src="https://t.com/a.gif">'
               '<img data-src="https://t.com/real.jpg" src="https://t.com/ph.jpg"></p>')
        self.assertEqual("https://t.com/real.jpg", media.first_image(raw))
        self.assertIsNone(media.first_image('<img src="http://t.com/x.jpg">'))
        self.assertIsNone(media.first_image(""))

    def test_first_image_handles_escaped_html(self):
        self.assertEqual("https://t.com/p.jpg", media.first_image("&lt;img src=&quot;https://t.com/p.jpg&quot;&gt;"))

    def test_from_element_enclosure_and_media(self):
        n = ET.fromstring('<item xmlns:media="http://search.yahoo.com/mrss/"><title>x</title>'
                          '<enclosure url="https://t.com/v.mp4" type="video/mp4"/>'
                          '<media:thumbnail url="https://t.com/th.jpg"/></item>')
        self.assertEqual(("https://t.com/th.jpg", "https://t.com/v.mp4"), media.from_element(n))

    def test_from_element_ignores_non_mp4_video_and_http(self):
        n = ET.fromstring('<item><enclosure url="https://t.com/v.webm" type="video/webm"/>'
                          '<enclosure url="http://t.com/i.jpg" type="image/jpeg"/></item>')
        self.assertEqual((None, None), media.from_element(n))

    def test_rss_item_gets_image_from_description(self):
        xml = ('<rss><channel><item><title>خبر</title><link>https://s.com/1</link>'
               '<description>&lt;img src="https://s.com/p.jpg"&gt; نص</description></item></channel></rss>').encode()
        it = parse_feed(xml)[0]
        self.assertEqual("https://s.com/p.jpg", it.image_url)
        self.assertEqual("", it.video_url)

    def test_rss_item_without_media_is_empty(self):
        xml = b'<rss><channel><item><title>t</title><link>https://s.com/2</link></item></channel></rss>'
        it = parse_feed(xml)[0]
        self.assertEqual(("", ""), (it.image_url, it.video_url))

    def test_wp_posts_image(self):
        data = ('[{"link":"https://s.com/a","title":{"rendered":"عنوان"},"excerpt":{"rendered":"م"},'
                '"content":{"rendered":"<img src=\\"https://s.com/c.jpg\\">"},"date_gmt":"2026-10-20T10:00:00"}]').encode()
        self.assertEqual("https://s.com/c.jpg", parse_posts(data)[0].image_url)

    def test_page_og_image_and_video(self):
        html = ('<html><head><title>t</title><meta property="og:title" content="عنوان">'
                '<meta property="og:image" content="https://s.com/o.jpg">'
                '<meta property="og:video" content="https://s.com/v.mp4"></head><body></body></html>').encode()
        it = extract_page(html, "https://s.com/p")
        self.assertEqual("https://s.com/o.jpg", it.image_url)
        self.assertEqual("https://s.com/v.mp4", it.video_url)


if __name__ == "__main__":
    unittest.main()
