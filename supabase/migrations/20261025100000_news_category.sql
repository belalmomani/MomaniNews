-- تصنيف الخبر تلقائياً (وفاة/تهنئة/نجاح/خدمات/رياضة/طقس/حوادث). عمود اختياري جديد فقط؛ لا يغيّر الأخبار الحالية (null = غير مصنّف).
-- يكتبه الجالب (service_role) ويقرأه المدير في تبويب الاعتماد. التطبيق العام لا يعتمد عليه بعد.
alter table public.news
  add column if not exists category text
  check (category is null or category in ('death','congrats','achievement','services','sports','weather','incident'));
create index if not exists news_category_idx on public.news (category) where category is not null;
