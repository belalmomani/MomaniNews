-- ناشرون يحظرهم المدير من التطبيق (نطاق مثل example.com أو اسم ناشر). الجالب يرفض أخبارهم قبل المطابقة.
-- جدول جديد فقط. القراءة والكتابة للمدير؛ الجالب يقرأ بـ service_role.
create table if not exists public.blocked_publishers (
  id         bigint generated always as identity primary key,
  value      text not null check (char_length(btrim(value)) between 2 and 120),
  created_at timestamptz not null default now()
);
create unique index if not exists blocked_publishers_value_idx on public.blocked_publishers (lower(btrim(value)));

alter table public.blocked_publishers enable row level security;
revoke all on public.blocked_publishers from anon, authenticated;
grant select, delete on public.blocked_publishers to authenticated;
grant insert (value) on public.blocked_publishers to authenticated;
drop policy if exists blocked_publishers_select on public.blocked_publishers;
create policy blocked_publishers_select on public.blocked_publishers for select to authenticated using (public.is_admin());
drop policy if exists blocked_publishers_insert on public.blocked_publishers;
create policy blocked_publishers_insert on public.blocked_publishers for insert to authenticated with check (public.is_admin());
drop policy if exists blocked_publishers_delete on public.blocked_publishers;
create policy blocked_publishers_delete on public.blocked_publishers for delete to authenticated using (public.is_admin());
