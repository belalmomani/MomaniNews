-- =====================================================================
-- الإبلاغ عن تعليق
-- جدول للبلاغات يقرؤه الأدمن فقط. المستخدم لا يلمس الجدول مباشرة؛ يبلّغ عبر الدالة
-- report_comment (security definer) لأن المستخدم العادي لا يرى تعليقات غيره في جدول comments.
-- بلاغ واحد لكل مستخدم لكل تعليق، ولا يمكن الإبلاغ عن تعليقك أنت.
-- =====================================================================
create table public.comment_reports (
  id          bigint generated always as identity primary key,
  comment_id  bigint not null references public.comments (id) on delete cascade,
  reporter_id uuid   not null references public.users (id) on delete cascade,
  reason      text   check (reason is null or char_length(reason) <= 200),
  created_at  timestamptz not null default now(),
  unique (comment_id, reporter_id)
);
create index comment_reports_comment_idx on public.comment_reports (comment_id);

alter table public.comment_reports enable row level security;
create policy comment_reports_admin_read   on public.comment_reports for select to authenticated using (public.is_admin());
create policy comment_reports_admin_delete on public.comment_reports for delete to authenticated using (public.is_admin());

-- Supabase يمنح الجداول الجديدة لـ anon/authenticated افتراضياً، فنسحبها صراحةً
revoke all on public.comment_reports from anon, authenticated;
grant select, delete on public.comment_reports to authenticated;   -- مقيّدة بالسياسات (أدمن فقط)

create function public.report_comment(p_comment_id bigint, p_reason text default null)
returns void
language plpgsql security definer set search_path = public, pg_temp as $$
declare owner uuid;
begin
  if auth.uid() is null or not public.can_interact() then
    raise exception 'not_allowed' using errcode = '42501';
  end if;
  select user_id into owner from public.comments where id = p_comment_id and status = 'visible';
  if owner is null then
    raise exception 'comment_not_found' using errcode = 'P0002';
  end if;
  if owner = auth.uid() then
    raise exception 'own_comment' using errcode = 'P0001';
  end if;
  insert into public.comment_reports (comment_id, reporter_id, reason)
  values (p_comment_id, auth.uid(), nullif(left(btrim(coalesce(p_reason, '')), 200), ''))
  on conflict (comment_id, reporter_id) do nothing;
end;
$$;
revoke execute on function public.report_comment(bigint, text) from public, anon, authenticated;
grant execute on function public.report_comment(bigint, text) to authenticated;
