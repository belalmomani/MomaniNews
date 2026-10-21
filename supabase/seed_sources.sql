delete from public.sources where url in (
  'https://petra.gov.jo/', 'https://www.alrai.com/', 'https://www.addustour.com/', 'https://www.ammonnews.net/',
  'https://www.khaberni.com/', 'https://www.sarayanews.com/', 'https://www.jfranews.com.jo/', 'https://www.garaanews.com/', 'https://www.gerasanews.com/',
  'https://www.rumonline.net/', 'https://jordanzad.com/', 'https://alanbatnews.net/',
  'https://www.alzad.net/rss.php', 'https://www.jfranews.com/rss.php',
  'https://alrai.com/rss', 'https://alanbatnews.net/rss.php', 'https://www.almamlakatv.com/'
);

insert into public.sources (name, url, kind) values
  ('وكالة الأنباء الأردنية (بترا)', 'https://petra.gov.jo/Include/Rss.jsp', 'rss'),
  ('الرأي - خلاصة 1', 'https://alrai.com/rss-feed/1', 'rss'),
  ('الرأي - خلاصة 2', 'https://alrai.com/rss-feed/2', 'rss'),
  ('الرأي - خلاصة 28', 'https://alrai.com/rss-feed/28', 'rss'),
  ('الدستور', 'https://www.addustour.com/rss', 'rss'),
  ('عمون', 'https://www.ammonnews.net/rss', 'rss'),
  ('خبرني', 'https://www.khaberni.com/rss', 'rss'),
  ('سرايا', 'https://www.sarayanews.com/rss.php', 'rss'),
  ('جفرا نيوز', 'https://jfranews.com.jo/rss.php', 'rss'),
  ('جراسا', 'https://www.gerasanews.com/rss.php', 'rss'),
  ('رم', 'https://www.rumonline.net/rss.php', 'rss'),
  ('زاد الأردن', 'https://www.jordanzad.com/rss.php', 'rss'),
  ('الأنباط', 'https://alanbatnews.net/', 'rss'),
  ('الغد', 'https://alghad.com/rss', 'rss'),
  ('جو 24', 'https://jo24.net/rss.xml', 'rss'),
  ('السبيل', 'https://www.assabeel.net/', 'rss'),
  ('الوكيل الإخباري', 'https://www.alwakeelnews.com/', 'rss'),
  ('وطن نيوز', 'https://watananews.com/', 'rss'),
  ('الوقائع', 'https://alwakaai.com/', 'rss'),
  ('المملكة', 'https://almamlakatv.com/feed.xml', 'rss'),
  ('رؤيا', 'https://royanews.tv/', 'rss'),
  ('السوسنة', 'https://www.assawsana.com/', 'rss'),
  ('عجلون نيوز', 'https://www.ajlounnews.net/', 'rss')
on conflict (url) do nothing;

update public.sources set is_active = false where url in ('https://www.assabeel.net/', 'https://watananews.com/');

-- خلاصات بحث بالكلمات (تغطي ناشرين كثيرين دفعة واحدة). غير مُجرَّبة من بيئتي: قد يمنعها robots.txt فيظهر السبب في تقرير الجلب.
insert into public.sources (name, url, kind) values
  ('بحث الأخبار: عبين', 'https://news.google.com/rss/search?q=%D8%B9%D8%A8%D9%8A%D9%86&hl=ar&gl=JO&ceid=JO:ar', 'rss'),
  ('بحث الأخبار: عبلين', 'https://news.google.com/rss/search?q=%D8%B9%D8%A8%D9%84%D9%8A%D9%86&hl=ar&gl=JO&ceid=JO:ar', 'rss'),
  ('بحث الأخبار: صخرة عجلون', 'https://news.google.com/rss/search?q=%D8%B5%D8%AE%D8%B1%D8%A9%20%D8%B9%D8%AC%D9%84%D9%88%D9%86&hl=ar&gl=JO&ceid=JO:ar', 'rss'),
  ('بحث الأخبار: عشيرة المومني', 'https://news.google.com/rss/search?q=%D8%B9%D8%B4%D9%8A%D8%B1%D8%A9%20%D8%A7%D9%84%D9%85%D9%88%D9%85%D9%86%D9%8A&hl=ar&gl=JO&ceid=JO:ar', 'rss'),
  ('بحث الأخبار: آل المومني', 'https://news.google.com/rss/search?q=%D8%A2%D9%84%20%D8%A7%D9%84%D9%85%D9%88%D9%85%D9%86%D9%8A&hl=ar&gl=JO&ceid=JO:ar', 'rss'),
  ('بحث الأخبار: المومني عجلون', 'https://news.google.com/rss/search?q=%D8%A7%D9%84%D9%85%D9%88%D9%85%D9%86%D9%8A%20%D8%B9%D8%AC%D9%84%D9%88%D9%86&hl=ar&gl=JO&ceid=JO:ar', 'rss')
on conflict (url) do nothing;
