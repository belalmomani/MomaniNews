-- «متجري»: تطبيقات من GitHub يعرضها المدير في التطبيق. جدول جديد فقط، لا يمسّ أي بيانات قائمة.
-- الزائر والمستخدم يقرآن المعتمَد فقط؛ الإضافة والتعديل والحذف للمدير. الصور والمعلومات تُسحب من GitHub بيد المدير ثم يعتمدها.
create table if not exists public.store_apps (
  id           bigint generated always as identity primary key,
  repo         text not null unique check (repo ~ '^[A-Za-z0-9_.-]{1,100}/[A-Za-z0-9_.-]{1,100}$'),
  title        text not null check (char_length(btrim(title)) between 1 and 100),
  description  text not null default '' check (char_length(description) <= 2000),
  category     text not null default 'other' check (category in ('apps','games','tools','news','other')),
  icon_url     text check (icon_url is null or (icon_url ~ '^https://' and char_length(icon_url) <= 1000)),
  screenshots  text[] not null default '{}' check (cardinality(screenshots) <= 8),
  apk_url      text check (apk_url is null or (apk_url ~ '^https://github\.com/' and char_length(apk_url) <= 1000)),
  version_name text check (version_name is null or char_length(version_name) <= 60),
  size_bytes   bigint check (size_bytes is null or size_bytes >= 0),
  status       text not null default 'pending' check (status in ('pending','approved','rejected')),
  sort_order   int not null default 0,
  created_at   timestamptz not null default now(),
  updated_at   timestamptz not null default now()
);
create index if not exists store_apps_status_idx on public.store_apps (status, category, sort_order);

drop trigger if exists store_apps_updated_at on public.store_apps;
create trigger store_apps_updated_at before update on public.store_apps
  for each row execute function public.set_updated_at();

alter table public.store_apps enable row level security;
revoke all on public.store_apps from anon, authenticated;
grant select on public.store_apps to anon, authenticated;
grant insert, update, delete on public.store_apps to authenticated;
drop policy if exists store_apps_select on public.store_apps;
create policy store_apps_select on public.store_apps for select to anon, authenticated using (status = 'approved' or public.is_admin());
drop policy if exists store_apps_insert on public.store_apps;
create policy store_apps_insert on public.store_apps for insert to authenticated with check (public.is_admin());
drop policy if exists store_apps_update on public.store_apps;
create policy store_apps_update on public.store_apps for update to authenticated using (public.is_admin()) with check (public.is_admin());
drop policy if exists store_apps_delete on public.store_apps;
create policy store_apps_delete on public.store_apps for delete to authenticated using (public.is_admin());
