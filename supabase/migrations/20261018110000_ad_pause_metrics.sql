-- =====================================================================
-- إيقاف مؤقت للإعلانات + عدّاد مشاهدات ونقرات
-- is_paused: يوقف عرض إعلان معتمد دون رفضه أو حذفه (يمكن استئنافه).
-- views/clicks: عدّادان بسيطان تزيدهما الدالة track_ad فقط. هما مؤشر تقريبي وليسا مقياساً
-- مقاوماً للتلاعب (أي عميل يستطيع استدعاء الدالة)؛ لا تعتمد عليهما في فوترة.
-- =====================================================================
alter table public.advertisements
  add column is_paused boolean not null default false,
  add column views     bigint  not null default 0,
  add column clicks    bigint  not null default 0;

-- أعمدة الإدراج للمستخدم محصورة أصلاً (news/title/..)، فلا يستطيع ضبط is_paused أو العدّادات.
create or replace view public.ads_public as
  select id, title, body, image_url, link_url, contact, region_id, starts_at, ends_at
  from public.advertisements
  where status = 'approved' and not is_paused and now() between starts_at and ends_at;

create function public.track_ad(p_ad_id bigint, p_kind text) returns void
language plpgsql security definer set search_path = public, pg_temp as $$
begin
  if p_kind = 'view' then
    update public.advertisements set views = views + 1
    where id = p_ad_id and status = 'approved' and not is_paused and now() between starts_at and ends_at;
  elsif p_kind = 'click' then
    update public.advertisements set clicks = clicks + 1
    where id = p_ad_id and status = 'approved' and not is_paused and now() between starts_at and ends_at;
  else
    raise exception 'bad_kind' using errcode = 'P0001';
  end if;
end;
$$;
revoke execute on function public.track_ad(bigint, text) from public, anon, authenticated;
grant execute on function public.track_ad(bigint, text) to anon, authenticated;
