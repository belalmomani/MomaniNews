-- ذاكرة الصفحات المفحوصة لمصادر sitemap/الصفحات (بلا RSS): بدونها تُعاد الصفحات غير ذات الصلة كل 30 دقيقة
-- وتزاحم الجديد. لا توجد جداول مكررة: هذا الجدول لا يحل محله شيء موجود (news لا تخزّن المرفوض عمداً).
-- الوصول لـ service_role فقط (مهمة GitHub Actions)؛ لا سياسات ولا صلاحيات للتطبيق إطلاقاً.
create table if not exists public.fetch_seen (
  url       text primary key check (url ~ '^https://' and char_length(url) <= 1000),
  source_id bigint references public.sources (id) on delete cascade,
  seen_at   timestamptz not null default now()
);
create index if not exists fetch_seen_source_idx on public.fetch_seen (source_id, seen_at desc);
alter table public.fetch_seen enable row level security;
revoke all on public.fetch_seen from anon, authenticated;
