-- =====================================================================
-- مؤشرات لوحة الإدارة: دالة واحدة للأدمن فقط تُرجع JSON بأعداد حقيقية من الجداول.
-- لا بيانات مكتوبة مسبقاً؛ كل رقم يُحسب لحظة الطلب.
-- =====================================================================
create function public.admin_stats() returns jsonb
language plpgsql stable security definer set search_path = public, pg_temp as $$
begin
  if not public.is_admin() then
    raise exception 'admin_only' using errcode = '42501';
  end if;
  return jsonb_build_object(
    'news_pending',        (select count(*) from public.news where status = 'pending'),
    'news_approved',       (select count(*) from public.news where status = 'approved'),
    'news_rejected',       (select count(*) from public.news where status = 'rejected'),
    'news_fetched',        (select count(*) from public.news where fingerprint is not null),
    'news_last_7d',        (select count(*) from public.news where status = 'approved' and published_at > now() - interval '7 days'),
    'users_total',         (select count(*) from public.users),
    'users_new_7d',        (select count(*) from public.users where created_at > now() - interval '7 days'),
    'comments_visible',    (select count(*) from public.comments where status = 'visible'),
    'comments_reported',   (select count(distinct comment_id) from public.comment_reports),
    'verification_pending',(select count(*) from public.verification_requests where status = 'pending'),
    'ads_pending',         (select count(*) from public.advertisements where status = 'pending'),
    'ads_active',          (select count(*) from public.advertisements where status = 'approved' and not is_paused and now() between starts_at and ends_at),
    'ads_views',           (select coalesce(sum(views), 0) from public.advertisements),
    'ads_clicks',          (select coalesce(sum(clicks), 0) from public.advertisements),
    'sources_active',      (select count(*) from public.sources where is_active),
    'sources_failing',     (select count(*) from public.sources where is_active and last_status = 'error'),
    'last_fetch_at',       (select max(started_at) from public.fetch_runs)
  );
end;
$$;
revoke execute on function public.admin_stats() from public, anon, authenticated;
grant execute on function public.admin_stats() to authenticated;
