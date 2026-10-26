-- إشعار فوري تلقائي عند اعتماد خبر: يُدرج إشعاراً عاماً (user_id فارغ) فيصل إلى الهواتف عبر Webhook ثم send-push (FCM).
-- بلا هذا الترحيل كان الإشعار العام يُنشأ يدوياً فقط. آمن للإعادة. لا يمسّ الأخبار ولا الإشعارات الموجودة.
-- الأوضاع (جدول push_settings، صف واحد): off = بلا إشعارات تلقائية | important = وفيات وحوادث فقط (الافتراضي) | all = كل خبر معتمد.
-- في وضع all يُخفَّف الإرسال: إشعار عام واحد كل min_interval_minutes (افتراضياً 10 دقائق) حتى لا يتحول اعتماد عدة أخبار دفعة واحدة إلى إزعاج.
-- الوفيات والحوادث لا يُخفَّف إرسالها.
create table if not exists public.push_settings (
  id                   int primary key default 1 check (id = 1),
  mode                 text not null default 'important' check (mode in ('off','important','all')),
  min_interval_minutes int  not null default 10 check (min_interval_minutes between 0 and 1440)
);
insert into public.push_settings (id) values (1) on conflict (id) do nothing;

alter table public.push_settings enable row level security;
revoke all on public.push_settings from anon, authenticated;
grant select on public.push_settings to authenticated;
grant update (mode, min_interval_minutes) on public.push_settings to authenticated;
drop policy if exists push_settings_select on public.push_settings;
create policy push_settings_select on public.push_settings for select to authenticated using (public.is_admin());
drop policy if exists push_settings_update on public.push_settings;
create policy push_settings_update on public.push_settings for update to authenticated
  using (public.is_admin()) with check (public.is_admin());

create or replace function public.notify_on_approval() returns trigger
language plpgsql security definer set search_path = public, pg_temp as $$
declare
  s          record;
  important  boolean;
  last_at    timestamptz;
begin
  if new.status <> 'approved' then return new; end if;
  if tg_op = 'UPDATE' and old.status = 'approved' then return new; end if;   -- اعتُمد سابقاً
  if new.published_at is not null and new.published_at > now() then return new; end if;   -- منشور مجدول
  select * into s from public.push_settings where id = 1;
  if not found or s.mode = 'off' then return new; end if;
  important := coalesce(new.category, '') in ('death', 'incident');
  if s.mode = 'important' and not important then return new; end if;
  if not important then
    select max(created_at) into last_at from public.notifications where user_id is null;
    if last_at is not null and last_at > now() - make_interval(mins => s.min_interval_minutes) then return new; end if;
  end if;
  insert into public.notifications (user_id, title, body, news_id)
  values (null, left(new.title, 120), nullif(left(coalesce(new.excerpt, ''), 200), ''), new.id);
  return new;
end;
$$;
revoke all on function public.notify_on_approval() from public, anon, authenticated;

drop trigger if exists news_notify_on_approval on public.news;
create trigger news_notify_on_approval
  after insert or update of status on public.news
  for each row execute function public.notify_on_approval();
