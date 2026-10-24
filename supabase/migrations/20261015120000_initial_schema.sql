-- =====================================================================
-- MomaniNews — المخطط الأولي (PostgreSQL / Supabase)
--
-- يُنفَّذ مرة واحدة على مشروع جديد من SQL Editor في Supabase (يعمل كله في معاملة واحدة).
-- الأمان يقوم على ثلاث طبقات: (1) تفعيل RLS على كل الجداول، (2) سحب كل الصلاحيات ثم منح
-- أضيق صلاحيات ممكنة على مستوى الأعمدة، (3) قيود وقواعد على مستوى القاعدة نفسها.
-- الاختبارات: supabase/tests/rls_tests.sql
-- =====================================================================
begin;

-- ---------------------------------------------------------------------
-- الأنواع
-- ---------------------------------------------------------------------
create type public.user_role          as enum ('user', 'admin');
create type public.verification_state as enum ('none', 'pending', 'approved', 'rejected');
create type public.news_status        as enum ('pending', 'approved', 'rejected', 'archived');
create type public.vote_kind          as enum ('credible', 'not_credible');
create type public.comment_status     as enum ('visible', 'hidden');
create type public.request_status     as enum ('pending', 'approved', 'rejected');
create type public.ad_status          as enum ('pending', 'approved', 'rejected');

-- ---------------------------------------------------------------------
-- regions (إضافة على الجداول الـ 11: تُسهّل إضافة مناطق جديدة لاحقاً دون تعديل الكود)
-- ---------------------------------------------------------------------
create table public.regions (
  id         text primary key check (id ~ '^[a-z0-9_-]{1,32}$'),
  name_ar    text not null check (char_length(btrim(name_ar)) between 1 and 60),
  sort_order int  not null default 0,
  is_active  boolean not null default true
);

insert into public.regions (id, name_ar, sort_order) values
  ('abeen',  'عبين',  1),
  ('abelin', 'عبلين', 2),
  ('sakhra', 'صخرة',  3);

-- ---------------------------------------------------------------------
-- users: صف لكل حساب في auth.users (يُنشأ تلقائياً بمشغّل عند أول دخول بـ Google)
-- ---------------------------------------------------------------------
create table public.users (
  id           uuid primary key references auth.users (id) on delete cascade,
  email        text,
  display_name text not null default 'مستخدم'
               check (char_length(btrim(display_name)) between 1 and 100),
  avatar_url   text check (avatar_url is null or avatar_url ~ '^https://'),
  role         public.user_role not null default 'user',
  verification public.verification_state not null default 'none',
  is_banned    boolean not null default false,
  created_at   timestamptz not null default now()
);

-- ---------------------------------------------------------------------
-- دوال الصلاحيات (مصدر الحقيقة الوحيد لقاعدة «من يحق له»)
-- ---------------------------------------------------------------------
create function public.is_admin() returns boolean
language sql stable security definer set search_path = public, pg_temp as $$
  select exists (
    select 1 from public.users u
    where u.id = auth.uid() and u.role = 'admin' and not u.is_banned
  );
$$;

-- قرار مفتوح: من يحق له التصويت والتعليق. الافتراض: أي مستخدم مسجّل غير محظور.
-- لقصرها على الموثّقين غيّر الشرط إلى:  and u.verification = 'approved'
create function public.can_interact() returns boolean
language sql stable security definer set search_path = public, pg_temp as $$
  select exists (
    select 1 from public.users u
    where u.id = auth.uid() and not u.is_banned
  );
$$;

-- إنشاء صف users تلقائياً عند أول تسجيل دخول
create function public.handle_new_user() returns trigger
language plpgsql security definer set search_path = public, pg_temp as $$
begin
  insert into public.users (id, email, display_name, avatar_url)
  values (
    new.id,
    new.email,
    left(coalesce(nullif(btrim(new.raw_user_meta_data ->> 'full_name'), ''),
                  nullif(btrim(new.raw_user_meta_data ->> 'name'), ''),
                  'مستخدم'), 100),
    case
      when new.raw_user_meta_data ->> 'avatar_url' ~ '^https://' then new.raw_user_meta_data ->> 'avatar_url'
      when new.raw_user_meta_data ->> 'picture'    ~ '^https://' then new.raw_user_meta_data ->> 'picture'
    end
  )
  on conflict (id) do nothing;
  return new;
end;
$$;

create trigger on_auth_user_created
  after insert on auth.users
  for each row execute function public.handle_new_user();

-- ---------------------------------------------------------------------
-- sources
-- ---------------------------------------------------------------------
create table public.sources (
  id              bigint generated always as identity primary key,
  name            text not null check (char_length(btrim(name)) between 1 and 120),
  url             text not null unique check (url ~ '^https://'),
  kind            text not null default 'rss' check (kind in ('rss', 'html', 'manual')),
  region_id       text references public.regions (id) on delete set null,
  is_active       boolean not null default true,
  last_fetched_at timestamptz,
  created_at      timestamptz not null default now()
);

-- ---------------------------------------------------------------------
-- news: لا تظهر للعامة إلا إذا كانت status = approved وتاريخ النشر قد حان
-- ---------------------------------------------------------------------
create table public.news (
  id           bigint generated always as identity primary key,
  title        text not null check (char_length(btrim(title)) between 1 and 300),
  excerpt      text check (excerpt is null or char_length(excerpt) <= 600),
  content      text not null check (char_length(btrim(content)) >= 1),
  image_url    text check (image_url is null or image_url ~ '^https://'),
  source_id    bigint references public.sources (id) on delete set null,
  source_name  text check (source_name is null or char_length(source_name) <= 120),
  original_url text unique check (original_url is null or original_url ~ '^https://'),
  region_id    text not null references public.regions (id),
  status       public.news_status not null default 'pending',
  published_at timestamptz,
  created_at   timestamptz not null default now(),
  -- بحث نصي: إعداد 'simple' (بلا تجذير). لا يوحّد الهمزات ولا يتجاهل التشكيل بعد.
  search       tsvector generated always as (
                 to_tsvector('simple',
                   coalesce(title, '') || ' ' || coalesce(excerpt, '') || ' ' || coalesce(content, ''))
               ) stored,
  constraint news_published_when_approved check (status <> 'approved' or published_at is not null)
);

create index news_published_idx on public.news (published_at desc) where status = 'approved';
create index news_region_idx    on public.news (region_id, published_at desc) where status = 'approved';
create index news_search_idx    on public.news using gin (search);

create function public.news_before_write() returns trigger
language plpgsql as $$
begin
  if new.status = 'approved' and new.published_at is null then
    new.published_at := now();
  end if;
  return new;
end;
$$;

create trigger news_before_write
  before insert or update on public.news
  for each row execute function public.news_before_write();

-- ---------------------------------------------------------------------
-- keywords + news_keywords (يغلق ثغرة «ربط الكلمات المفتاحية بالأخبار»؛ إضافة على الجداول الـ 11)
-- ---------------------------------------------------------------------
create table public.keywords (
  id         bigint generated always as identity primary key,
  word       text not null check (char_length(btrim(word)) between 1 and 80),
  region_id  text references public.regions (id) on delete set null,
  is_active  boolean not null default true,
  created_at timestamptz not null default now()
);
create unique index keywords_word_key on public.keywords (lower(btrim(word)));

create table public.news_keywords (
  news_id    bigint not null references public.news (id) on delete cascade,
  keyword_id bigint not null references public.keywords (id) on delete cascade,
  primary key (news_id, keyword_id)
);

-- ---------------------------------------------------------------------
-- news_votes: المفتاح الأساسي (news_id, user_id) = صوت واحد لكل مستخدم لكل خبر على مستوى القاعدة
-- ---------------------------------------------------------------------
create table public.news_votes (
  news_id    bigint not null references public.news (id) on delete cascade,
  user_id    uuid   not null default auth.uid() references public.users (id) on delete cascade,
  vote       public.vote_kind not null,
  created_at timestamptz not null default now(),
  updated_at timestamptz not null default now(),
  primary key (news_id, user_id)
);

create function public.set_updated_at() returns trigger
language plpgsql as $$
begin
  new.updated_at := now();
  return new;
end;
$$;

create trigger news_votes_updated_at
  before update on public.news_votes
  for each row execute function public.set_updated_at();

-- ---------------------------------------------------------------------
-- comments
-- ---------------------------------------------------------------------
create table public.comments (
  id         bigint generated always as identity primary key,
  news_id    bigint not null references public.news (id) on delete cascade,
  user_id    uuid   not null default auth.uid() references public.users (id) on delete cascade,
  body       text   not null check (char_length(btrim(body)) between 1 and 500),
  status     public.comment_status not null default 'visible',
  created_at timestamptz not null default now()
);
create index comments_news_idx on public.comments (news_id, created_at desc) where status = 'visible';

-- قص النص + حد معدل النشر (تعليق واحد كل 10 ثوانٍ لكل مستخدم) ضد الإزعاج
create function public.comments_before_insert() returns trigger
language plpgsql security definer set search_path = public, pg_temp as $$
begin
  new.body := btrim(new.body);
  if exists (
    select 1 from public.comments c
    where c.user_id = new.user_id and c.created_at > now() - interval '10 seconds'
  ) then
    raise exception 'comment_rate_limited' using errcode = 'P0001';
  end if;
  return new;
end;
$$;

create trigger comments_before_insert
  before insert on public.comments
  for each row execute function public.comments_before_insert();

-- ---------------------------------------------------------------------
-- verification_requests: صور الهوية في حاوية تخزين خاصة، والجدول يحفظ المسار فقط
-- ---------------------------------------------------------------------
create table public.verification_requests (
  id            bigint generated always as identity primary key,
  user_id       uuid not null default auth.uid() references public.users (id) on delete cascade,
  full_name     text not null check (char_length(btrim(full_name)) between 2 and 120),
  -- المسار يجب أن يبدأ بمجلد = معرّف المستخدم (تفرضه سياسة التخزين وسياسة الجدول)
  document_path text not null check (document_path ~ '^[0-9a-f-]{36}/[A-Za-z0-9._-]{1,100}$'),
  status        public.request_status not null default 'pending',
  admin_note    text check (admin_note is null or char_length(admin_note) <= 500),
  reviewed_by   uuid references public.users (id) on delete set null,
  reviewed_at   timestamptz,
  created_at    timestamptz not null default now()
);
-- طلب معلّق واحد فقط لكل مستخدم
create unique index verification_one_pending_per_user
  on public.verification_requests (user_id) where status = 'pending';

create function public.verification_before_update() returns trigger
language plpgsql as $$
begin
  if new.status is distinct from old.status then
    if old.status <> 'pending' then
      raise exception 'request_already_reviewed' using errcode = 'P0001';
    end if;
    new.reviewed_by := auth.uid();
    new.reviewed_at := now();
  end if;
  return new;
end;
$$;

create trigger verification_before_update
  before update on public.verification_requests
  for each row execute function public.verification_before_update();

-- مزامنة حالة التوثيق في users (المستخدم نفسه لا يستطيع تعديلها)
create function public.verification_after_write() returns trigger
language plpgsql security definer set search_path = public, pg_temp as $$
begin
  if tg_op = 'INSERT' then
    update public.users set verification = 'pending' where id = new.user_id;
  elsif new.status is distinct from old.status then
    update public.users set verification = new.status::text::public.verification_state
    where id = new.user_id;
  end if;
  return null;
end;
$$;

create trigger verification_after_write
  after insert or update on public.verification_requests
  for each row execute function public.verification_after_write();

-- ---------------------------------------------------------------------
-- advertisements
-- ---------------------------------------------------------------------
create table public.advertisements (
  id         bigint generated always as identity primary key,
  user_id    uuid default auth.uid() references public.users (id) on delete set null,
  title      text not null check (char_length(btrim(title)) between 1 and 120),
  body       text check (body is null or char_length(body) <= 1000),
  image_url  text check (image_url is null or image_url ~ '^https://'),
  link_url   text check (link_url  is null or link_url  ~ '^https://'),
  contact    text check (contact is null or char_length(contact) <= 120),
  region_id  text references public.regions (id) on delete set null,
  status     public.ad_status not null default 'pending',
  admin_note text check (admin_note is null or char_length(admin_note) <= 500),
  starts_at  timestamptz,
  ends_at    timestamptz,
  created_at timestamptz not null default now(),
  constraint ads_dates_order check (starts_at is null or ends_at is null or ends_at > starts_at),
  constraint ads_approved_need_dates check (status <> 'approved' or (starts_at is not null and ends_at is not null))
);
create index ads_public_idx on public.advertisements (status, starts_at, ends_at);

-- حد أقصى 5 طلبات معلّقة لكل مستخدم
create function public.advertisements_before_insert() returns trigger
language plpgsql security definer set search_path = public, pg_temp as $$
begin
  if new.user_id is not null and (
    select count(*) from public.advertisements a
    where a.user_id = new.user_id and a.status = 'pending'
  ) >= 5 then
    raise exception 'too_many_pending_ads' using errcode = 'P0001';
  end if;
  return new;
end;
$$;

create trigger advertisements_before_insert
  before insert on public.advertisements
  for each row execute function public.advertisements_before_insert();

-- ---------------------------------------------------------------------
-- notifications: user_id فارغ = إشعار عام لكل المستخدمين
-- ---------------------------------------------------------------------
create table public.notifications (
  id         bigint generated always as identity primary key,
  user_id    uuid references public.users (id) on delete cascade,
  title      text not null check (char_length(btrim(title)) between 1 and 120),
  body       text check (body is null or char_length(body) <= 500),
  news_id    bigint references public.news (id) on delete set null,
  read_at    timestamptz,
  created_at timestamptz not null default now()
);
create index notifications_user_idx on public.notifications (user_id, created_at desc);

-- ---------------------------------------------------------------------
-- admin_logs: سجل تدقيق للإضافة فقط (لا أحد يكتب فيه مباشرة؛ المشغّلات والدوال فقط)
-- ---------------------------------------------------------------------
create table public.admin_logs (
  id          bigint generated always as identity primary key,
  admin_id    uuid references public.users (id) on delete set null,
  action      text not null check (char_length(action) between 1 and 80),
  entity_type text not null check (char_length(entity_type) between 1 and 60),
  entity_id   text,
  details     jsonb not null default '{}'::jsonb,
  created_at  timestamptz not null default now()
);
create index admin_logs_created_idx on public.admin_logs (created_at desc);

create function public.log_status_change() returns trigger
language plpgsql security definer set search_path = public, pg_temp as $$
begin
  if new.status is distinct from old.status then
    insert into public.admin_logs (admin_id, action, entity_type, entity_id, details)
    values (auth.uid(), 'status_change', tg_argv[0], new.id::text,
            jsonb_build_object('from', old.status::text, 'to', new.status::text));
  end if;
  return new;
end;
$$;

create trigger news_log_status    after update on public.news
  for each row execute function public.log_status_change('news');
create trigger comments_log_status after update on public.comments
  for each row execute function public.log_status_change('comment');
create trigger ads_log_status     after update on public.advertisements
  for each row execute function public.log_status_change('advertisement');
create trigger verification_log_status after update on public.verification_requests
  for each row execute function public.log_status_change('verification_request');

-- تغيير الدور/الحظر: دالة وحيدة، للأدمن فقط، مع تدقيق، ولا يستطيع الأدمن تخفيض نفسه
create function public.admin_set_user_flags(p_user uuid, p_role public.user_role, p_banned boolean)
returns void
language plpgsql security definer set search_path = public, pg_temp as $$
begin
  if not public.is_admin() then
    raise exception 'forbidden' using errcode = '42501';
  end if;
  if p_user = auth.uid() and (p_role <> 'admin' or p_banned) then
    raise exception 'cannot_demote_self' using errcode = 'P0001';
  end if;
  update public.users set role = p_role, is_banned = p_banned where id = p_user;
  if not found then
    raise exception 'user_not_found' using errcode = 'P0002';
  end if;
  insert into public.admin_logs (admin_id, action, entity_type, entity_id, details)
  values (auth.uid(), 'set_user_flags', 'user', p_user::text,
          jsonb_build_object('role', p_role::text, 'is_banned', p_banned));
end;
$$;

-- ---------------------------------------------------------------------
-- failed_fetch_logs: الكتابة من service_role فقط (مهمة جلب الأخبار). لا تُسجَّل فيه أسرار.
-- ---------------------------------------------------------------------
create table public.failed_fetch_logs (
  id         bigint generated always as identity primary key,
  source_id  bigint references public.sources (id) on delete set null,
  url        text,
  error      text check (error is null or char_length(error) <= 500),
  created_at timestamptz not null default now()
);

-- ---------------------------------------------------------------------
-- العروض (Views): تكشف أعمدة آمنة فقط. تعمل بصلاحيات مالكها عمداً (تتجاوز RLS) وتُرشِّح بنفسها.
-- ---------------------------------------------------------------------
create view public.public_profiles as
  select id, display_name, avatar_url, verification
  from public.users
  where not is_banned;

create view public.news_vote_counts as
  select n.id as news_id,
         (count(v.user_id) filter (where v.vote = 'credible'))::int     as credible,
         (count(v.user_id) filter (where v.vote = 'not_credible'))::int as not_credible
  from public.news n
  left join public.news_votes v on v.news_id = n.id
  where n.status = 'approved' and n.published_at <= now()
  group by n.id;

create view public.comments_public as
  select c.id, c.news_id, c.body, c.created_at,
         u.display_name, u.verification,
         (c.user_id = auth.uid()) as is_mine
  from public.comments c
  join public.news  n on n.id = c.news_id and n.status = 'approved' and n.published_at <= now()
  join public.users u on u.id = c.user_id and not u.is_banned
  where c.status = 'visible';

create view public.ads_public as
  select id, title, body, image_url, link_url, contact, region_id, starts_at, ends_at
  from public.advertisements
  where status = 'approved' and now() between starts_at and ends_at;

-- ---------------------------------------------------------------------
-- الصلاحيات: نسحب كل شيء أولاً ثم نمنح الأدنى (سياسات RLS تُقيّد الصفوف فوق ذلك)
-- ---------------------------------------------------------------------
revoke all on all tables    in schema public from anon, authenticated;
revoke all on all sequences in schema public from anon, authenticated;
revoke execute on all functions in schema public from public, anon, authenticated;

grant execute on function public.is_admin()       to anon, authenticated;
grant execute on function public.can_interact()   to anon, authenticated;
grant execute on function public.admin_set_user_flags(uuid, public.user_role, boolean) to authenticated;

-- قراءة عامة
grant select on public.regions, public.sources, public.news,
                public.public_profiles, public.news_vote_counts,
                public.comments_public, public.ads_public
  to anon, authenticated;

-- المستخدم المسجّل
grant select on public.users, public.news_votes, public.comments, public.verification_requests,
                public.advertisements, public.notifications, public.admin_logs,
                public.keywords, public.news_keywords, public.failed_fetch_logs
  to authenticated;

grant update (display_name, avatar_url) on public.users to authenticated;
grant insert (news_id, vote)            on public.news_votes to authenticated;
grant update (vote)                     on public.news_votes to authenticated;
grant insert (news_id, body)            on public.comments to authenticated;
grant update (status)                   on public.comments to authenticated;   -- الأدمن فقط عبر السياسة
grant delete                            on public.comments to authenticated;   -- صاحب التعليق أو الأدمن
grant insert (full_name, document_path) on public.verification_requests to authenticated;
grant update (status, admin_note)       on public.verification_requests to authenticated;  -- الأدمن فقط
grant insert (title, body, image_url, link_url, contact, region_id) on public.advertisements to authenticated;
grant update, delete                    on public.advertisements to authenticated;         -- الأدمن فقط
grant update (read_at)                  on public.notifications to authenticated;
grant insert, delete                    on public.notifications to authenticated;          -- الأدمن فقط

-- الكتابة الإدارية (تُقيَّد بسياسات is_admin())
grant insert, update, delete on public.regions, public.sources, public.news,
                                public.keywords, public.news_keywords
  to authenticated;

-- ---------------------------------------------------------------------
-- RLS
-- ---------------------------------------------------------------------
alter table public.regions               enable row level security;
alter table public.users                 enable row level security;
alter table public.sources               enable row level security;
alter table public.news                  enable row level security;
alter table public.keywords              enable row level security;
alter table public.news_keywords         enable row level security;
alter table public.news_votes            enable row level security;
alter table public.comments              enable row level security;
alter table public.verification_requests enable row level security;
alter table public.advertisements        enable row level security;
alter table public.notifications         enable row level security;
alter table public.admin_logs            enable row level security;
alter table public.failed_fetch_logs     enable row level security;

-- regions
create policy regions_read  on public.regions for select to anon, authenticated using (true);
create policy regions_admin on public.regions for all    to authenticated using (public.is_admin()) with check (public.is_admin());

-- users: القراءة لصاحب الصف أو الأدمن؛ التعديل لصاحب الصف على عمودين فقط (الصلاحيات أعلاه)
create policy users_read_own    on public.users for select to authenticated
  using (id = auth.uid() or public.is_admin());
create policy users_update_own  on public.users for update to authenticated
  using (id = auth.uid()) with check (id = auth.uid());

-- sources
create policy sources_read_active on public.sources for select to anon, authenticated using (is_active);
create policy sources_admin       on public.sources for all    to authenticated using (public.is_admin()) with check (public.is_admin());

-- news
create policy news_read_public on public.news for select to anon, authenticated
  using (status = 'approved' and published_at <= now());
create policy news_admin       on public.news for all    to authenticated
  using (public.is_admin()) with check (public.is_admin());

-- keywords / news_keywords: داخلية للأدمن
create policy keywords_admin      on public.keywords      for all to authenticated using (public.is_admin()) with check (public.is_admin());
create policy news_keywords_admin on public.news_keywords for all to authenticated using (public.is_admin()) with check (public.is_admin());

-- news_votes
create policy votes_read_own on public.news_votes for select to authenticated
  using (user_id = auth.uid() or public.is_admin());
create policy votes_insert   on public.news_votes for insert to authenticated
  with check (
    user_id = auth.uid()
    and public.can_interact()
    and exists (select 1 from public.news n where n.id = news_id and n.status = 'approved')
  );
create policy votes_update   on public.news_votes for update to authenticated
  using (user_id = auth.uid() and public.can_interact())
  with check (user_id = auth.uid() and public.can_interact());

-- comments (القراءة العامة عبر العرض comments_public)
create policy comments_read_own on public.comments for select to authenticated
  using (user_id = auth.uid() or public.is_admin());
create policy comments_insert   on public.comments for insert to authenticated
  with check (
    user_id = auth.uid()
    and public.can_interact()
    and status = 'visible'
    and exists (select 1 from public.news n where n.id = news_id and n.status = 'approved')
  );
create policy comments_update_admin on public.comments for update to authenticated
  using (public.is_admin()) with check (public.is_admin());
create policy comments_delete       on public.comments for delete to authenticated
  using (user_id = auth.uid() or public.is_admin());

-- verification_requests
create policy verif_read_own on public.verification_requests for select to authenticated
  using (user_id = auth.uid() or public.is_admin());
create policy verif_insert   on public.verification_requests for insert to authenticated
  with check (
    user_id = auth.uid()
    and public.can_interact()
    and status = 'pending'
    and split_part(document_path, '/', 1) = auth.uid()::text
    and (select u.verification from public.users u where u.id = auth.uid()) in ('none', 'rejected')
  );
create policy verif_update_admin on public.verification_requests for update to authenticated
  using (public.is_admin()) with check (public.is_admin());

-- advertisements (القراءة العامة عبر العرض ads_public)
create policy ads_read_own on public.advertisements for select to authenticated
  using (user_id = auth.uid() or public.is_admin());
create policy ads_insert   on public.advertisements for insert to authenticated
  with check (user_id = auth.uid() and public.can_interact() and status = 'pending');
create policy ads_update_admin on public.advertisements for update to authenticated
  using (public.is_admin()) with check (public.is_admin());
create policy ads_delete_admin on public.advertisements for delete to authenticated
  using (public.is_admin());

-- notifications
create policy notif_read   on public.notifications for select to authenticated
  using (user_id = auth.uid() or user_id is null or public.is_admin());
create policy notif_update on public.notifications for update to authenticated
  using (user_id = auth.uid()) with check (user_id = auth.uid());
create policy notif_admin_insert on public.notifications for insert to authenticated
  with check (public.is_admin());
create policy notif_admin_delete on public.notifications for delete to authenticated
  using (public.is_admin());

-- admin_logs / failed_fetch_logs: قراءة الأدمن فقط
create policy admin_logs_read on public.admin_logs for select to authenticated using (public.is_admin());
create policy failed_fetch_read on public.failed_fetch_logs for select to authenticated using (public.is_admin());

-- ---------------------------------------------------------------------
-- التخزين: حاوية خاصة لصور الهوية. المستخدم يرفع إلى مجلده فقط، ويقرأ مجلده فقط، والأدمن يقرأ الكل.
-- ---------------------------------------------------------------------
insert into storage.buckets (id, name, public, file_size_limit, allowed_mime_types)
values ('verification-docs', 'verification-docs', false, 5242880,
        array['image/jpeg', 'image/png', 'image/webp'])
on conflict (id) do nothing;

create policy "verification docs: upload to own folder" on storage.objects
  for insert to authenticated
  with check (bucket_id = 'verification-docs' and (storage.foldername(name))[1] = auth.uid()::text);

create policy "verification docs: read own or admin" on storage.objects
  for select to authenticated
  using (bucket_id = 'verification-docs'
         and ((storage.foldername(name))[1] = auth.uid()::text or public.is_admin()));

commit;
