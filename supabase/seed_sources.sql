delete from public.sources where url in (
  'https://petra.gov.jo/', 'https://www.alrai.com/', 'https://www.addustour.com/', 'https://www.ammonnews.net/',
  'https://www.khaberni.com/', 'https://www.sarayanews.com/', 'https://www.jfranews.com.jo/', 'https://www.garaanews.com/', 'https://www.gerasanews.com/',
  'https://www.rumonline.net/', 'https://jordanzad.com/', 'https://alanbatnews.net/',
  'https://www.alzad.net/rss.php', 'https://www.jfranews.com/rss.php'
);

insert into public.sources (name, url, kind) values
  ('وكالة الأنباء الأردنية (بترا)', 'https://petra.gov.jo/Include/Rss.jsp', 'rss'),
  ('الرأي', 'https://alrai.com/rss', 'rss'),
  ('الدستور', 'https://www.addustour.com/rss', 'rss'),
  ('عمون', 'https://www.ammonnews.net/rss', 'rss'),
  ('خبرني', 'https://www.khaberni.com/rss', 'rss'),
  ('سرايا', 'https://www.sarayanews.com/rss.php', 'rss'),
  ('جفرا نيوز', 'https://jfranews.com.jo/rss.php', 'rss'),
  ('جراسا', 'https://www.gerasanews.com/rss.php', 'rss'),
  ('رم', 'https://www.rumonline.net/rss.php', 'rss'),
  ('زاد الأردن', 'https://www.jordanzad.com/rss.php', 'rss'),
  ('الأنباط', 'https://alanbatnews.net/rss.php', 'rss'),
  ('الغد', 'https://alghad.com/rss', 'rss'),
  ('جو 24', 'https://jo24.net/rss.xml', 'rss'),
  ('السبيل', 'https://www.assabeel.net/', 'rss'),
  ('الوكيل الإخباري', 'https://www.alwakeelnews.com/', 'rss'),
  ('وطن نيوز', 'https://watananews.com/', 'rss'),
  ('الوقائع', 'https://alwakaai.com/', 'rss'),
  ('المملكة', 'https://www.almamlakatv.com/', 'rss'),
  ('رؤيا', 'https://royanews.tv/', 'rss'),
  ('السوسنة', 'https://www.assawsana.com/', 'rss'),
  ('عجلون نيوز', 'https://www.ajlounnews.net/', 'rss')
on conflict (url) do nothing;

update public.sources set is_active = false where url in ('https://www.assabeel.net/', 'https://watananews.com/');
