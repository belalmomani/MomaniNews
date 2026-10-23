-- latin spellings + plural and local context keywords (generated from variants.py). safe to re-run.
insert into public.keywords (word, region_id, kind, needs_context) values
  ('almomani', null, 'name', false),
  ('almomany', null, 'name', false),
  ('momany', null, 'name', false),
  ('ibleen', 'abelin', 'alias', false),
  ('sakhrah', 'sakhra', 'alias', true),
  ('مومنيين', null, 'name', false),
  ('الجنيد', null, 'context', false),
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
