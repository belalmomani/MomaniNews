-- محرك جلب الأخبار: تغييرات قابلة للتتبع على المخطط الحالي (لا جداول مكررة، البيانات الحالية محفوظة).
-- 1) الكلمات المفتاحية: نوع الكلمة + «تحتاج سياقاً» للكلمات الملتبسة (مثل «صخرة»).
-- 2) المصادر: حالة آخر جلب.
-- 3) الأخبار: بصمة منع التكرار، الكلمات المطابقة، تاريخ النشر الأصلي، المرجع المشابه، ملاحظات.
-- 4) news_refs: مراجع إضافية لنفس الخبر من مصادر أخرى (بدل منشور مكرر).
-- 5) fetch_runs: سجل تشغيل المحرك. الكتابة من service_role فقط (مهمة GitHub Actions)؛ القراءة للأدمن.
-- 6) منطقة «المومني» لأخبار المومني غير المرتبطة بقرية.

create type public.keyword_kind as enum ('region', 'name', 'alias', 'context', 'exclude');

alter table public.keywords
  add column kind          public.keyword_kind not null default 'alias',
  add column needs_context boolean not null default false;

alter table public.sources
  add column last_status     text check (last_status is null or last_status in ('ok', 'error')),
  add column last_error      text check (last_error is null or char_length(last_error) <= 300),
  add column last_success_at timestamptz;

alter table public.news
  add column fingerprint         text check (fingerprint is null or fingerprint ~ '^[0-9a-f]{64}$'),
  add column matched_terms       text[] not null default '{}',
  add column source_published_at timestamptz,
  add column duplicate_of        bigint references public.news (id) on delete set null,
  add column fetch_note          text check (fetch_note is null or char_length(fetch_note) <= 500),
  add column review_note         text check (review_note is null or char_length(review_note) <= 500);

-- تكرار حرفي للعنوان المطبَّع مرفوض في القاعدة نفسها (وليس في المحرك فقط)
create unique index news_fingerprint_key on public.news (fingerprint) where fingerprint is not null;
create index news_duplicate_of_idx on public.news (duplicate_of) where duplicate_of is not null;

create table public.news_refs (
  id          bigint generated always as identity primary key,
  news_id     bigint not null references public.news (id) on delete cascade,
  source_name text check (source_name is null or char_length(source_name) <= 120),
  url         text not null unique check (url ~ '^https://'),
  found_at    timestamptz not null default now()
);
create index news_refs_news_idx on public.news_refs (news_id);

create table public.fetch_runs (
  id               bigint generated always as identity primary key,
  started_at       timestamptz not null default now(),
  finished_at      timestamptz,
  trigger          text not null default 'schedule' check (trigger in ('schedule', 'manual')),
  sources_total    int not null default 0,
  sources_failed   int not null default 0,
  items_seen       int not null default 0,
  items_matched    int not null default 0,
  items_inserted   int not null default 0,
  items_duplicate  int not null default 0,
  error_summary    text check (error_summary is null or char_length(error_summary) <= 1000)
);
create index fetch_runs_started_idx on public.fetch_runs (started_at desc);

alter table public.news_refs  enable row level security;
alter table public.fetch_runs enable row level security;
create policy news_refs_admin  on public.news_refs  for select to authenticated using (public.is_admin());
create policy fetch_runs_admin on public.fetch_runs for select to authenticated using (public.is_admin());
-- في Supabase تمنح الصلاحيات الافتراضية كل جدول جديد لـ anon/authenticated؛ نسحبها صراحة ثم نمنح القراءة فقط (والسياسة تحصرها بالأدمن).
revoke all on public.news_refs, public.fetch_runs from anon, authenticated;
grant select on public.news_refs, public.fetch_runs to authenticated;

insert into public.regions (id, name_ar, sort_order) values ('momani', 'المومني', 4) on conflict (id) do nothing;

-- كلمات ابتدائية قابلة للتعديل من لوحة الإدارة (لا حاجة لتعديل الكود). المحرك يطبّع الهمزات والتاء المربوطة وغيرها.
-- «صخرة» ملتبسة (قبة الصخرة، صخرة بمعنى حجر) فتحتاج كلمة سياق من نوع context، وتُستبعد عبارات قبة الصخرة.
insert into public.keywords (word, region_id, kind, needs_context) values
  ('عبين',   'abeen',  'region', false),
  ('عبلين',  'abelin', 'region', false),
  ('صخرة',   'sakhra', 'region', true),
  ('مومني',  null,     'name',   false),
  ('عجلون',  null,     'context', false),
  ('كفرنجة', null,     'context', false),
  ('بلدية',  null,     'context', false),
  ('قرية',   null,     'context', false),
  ('بلدة',   null,     'context', false),
  ('قبة الصخرة',   null, 'exclude', false),
  ('الصخرة المشرفة', null, 'exclude', false)
on conflict do nothing;
