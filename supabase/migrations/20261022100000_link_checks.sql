-- فحص صلاحية روابط الأخبار المخزّنة: لا نعتبر الرابط سليماً لمجرد أن الخادم أعاد 200.
-- جدول جديد فقط (لا تعديل على news). الكتابة من service_role (مهمة GitHub Actions)؛ القراءة للأدمن فقط.
create table if not exists public.link_checks (
  news_id       bigint primary key references public.news (id) on delete cascade,
  url           text not null,
  status        text not null check (status in ('ok', 'broken', 'changed', 'unreachable', 'blocked')),
  reason        text check (reason is null or char_length(reason) <= 300),
  attempts      int  not null default 0,
  checked_at    timestamptz not null default now(),
  next_check_at timestamptz not null default now()
);
create index if not exists link_checks_status_idx on public.link_checks (status, checked_at desc);

alter table public.link_checks enable row level security;
revoke all on public.link_checks from anon, authenticated;
grant select on public.link_checks to authenticated;
drop policy if exists link_checks_admin on public.link_checks;
create policy link_checks_admin on public.link_checks for select to authenticated using (public.is_admin());
