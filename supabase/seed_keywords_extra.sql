-- توسيع الكلمات: تهجئات لاتينية للمناطق والعشيرة وسياقات محلية. قابلة للتعديل من لوحة الأدمن. آمن للإعادة.
-- «sakhra» لاتينية ملتبسة فتحتاج سياقاً كالعربية. المعالج يطبّع التشكيل والتشديد (عبّين = عبين).
insert into public.keywords (word, region_id, kind, needs_context) values
  ('abeen',  'abeen',  'alias', false),
  ('abelin', 'abelin', 'alias', false),
  ('ablin',  'abelin', 'alias', false),
  ('sakhra', 'sakhra', 'alias', true),
  ('momani', null,     'name',  false),
  ('ajloun', null,     'context', false),
  ('لواء كفرنجة', null, 'context', false),
  ('مجلس قروي', null,  'context', false)
on conflict do nothing;
