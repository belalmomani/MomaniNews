-- صيغ كتابة لاتينية للمناطق والعشيرة (من قاموس fetcher/momani_fetcher/variants.py). آمن للإعادة؛ قابلة للتعديل من لوحة الأدمن.
-- «almomani» الملتصقة لا تلتقطها كلمة «momani» بعد التطبيع، لذلك تُضاف صراحةً. «sakhra/sakhrah» ملتبسة فتحتاج سياقاً.
insert into public.keywords (word, region_id, kind, needs_context) values
  ('almomani', null, 'name', false),
  ('almomany', null, 'name', false),
  ('momany', null, 'name', false),
  ('ibleen', 'abelin', 'alias', false),
  ('sakhrah', 'sakhra', 'alias', true),
  ('abeen', 'abeen', 'alias', false),
  ('abin', 'abeen', 'alias', false),
  ('ebeen', 'abeen', 'alias', false),
  ('ablin', 'abelin', 'alias', false),
  ('abilin', 'abelin', 'alias', false),
  ('eblin', 'abelin', 'alias', false),
  ('sakhra', 'sakhra', 'alias', true),
  ('momani', null, 'name', false),
  ('al momani', null, 'name', false),
  ('al momany', null, 'name', false)
on conflict do nothing;
