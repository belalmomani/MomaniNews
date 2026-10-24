-- =====================================================================
-- 1) عدّادات الأخبار للترتيب من الخادم: المشاهدات، الإعجابات («موثوق»)، التعليقات.
--    الأعمدة مخزَّنة (denormalized) وتُحدَّث بمشغّلات security definer فقط، فلا يستطيع التطبيق كتابتها.
-- 2) الإشراف الأسرع: كلمات محظورة (للأدمن فقط) → أخبار تُعلَّم بتحذير للمشرف، وتعليقات تُخفى تلقائياً.
-- 3) admin_insights(): إحصاءات للأدمن فقط (أكثر الأخبار قراءة، أداء المصادر، منشورات المستخدمين).
-- ملاحظة أمنية: التحذيرات والكلمات في جداول منفصلة مسحوبة من الزوار، لأن جدول news مقروء للعامة عمودياً كاملاً.
-- =====================================================================

alter table public.news
  add column view_count     bigint not null default 0,
  add column credible_count integer not null default 0,
  add column comment_count  integer not null default 0;

-- ملء أولي من البيانات الحالية
update public.news n set
  credible_count = (select count(*) from public.news_votes v where v.news_id = n.id and v.vote = 'credible'),
  comment_count  = (select count(*) from public.comments c where c.news_id = n.id and c.status = 'visible');

create index news_views_idx    on public.news (view_count desc,     published_at desc) where status = 'approved';
create index news_credible_idx on public.news (credible_count desc, published_at desc) where status = 'approved';
create index news_comments_idx on public.news (comment_count desc,  published_at desc) where status = 'approved';

-- غير الأدمن لا يُدخل عدّادات مزوّرة (يعمل قبل فحص WITH CHECK)
create function public.news_zero_counts() returns trigger
language plpgsql security definer set search_path = public, pg_temp as $$
begin
  if not public.is_admin() then
    new.view_count := 0; new.credible_count := 0; new.comment_count := 0;
  end if;
  return new;
end;
$$;
create trigger news_zero_counts before insert on public.news
  for each row execute function public.news_zero_counts();

create function public.recount_news(p_id bigint) returns void
language sql security definer set search_path = public, pg_temp as $$
  update public.news n set
    credible_count = (select count(*) from public.news_votes v where v.news_id = p_id and v.vote = 'credible'),
    comment_count  = (select count(*) from public.comments c where c.news_id = p_id and c.status = 'visible')
  where n.id = p_id;
$$;

create function public.recount_on_change() returns trigger
language plpgsql security definer set search_path = public, pg_temp as $$
begin
  if tg_op in ('INSERT', 'UPDATE') then perform public.recount_news(new.news_id); end if;
  if tg_op in ('DELETE', 'UPDATE') then perform public.recount_news(old.news_id); end if;
  return null;
end;
$$;
create trigger news_votes_recount after insert or update or delete on public.news_votes
  for each row execute function public.recount_on_change();
create trigger comments_recount after insert or update or delete on public.comments
  for each row execute function public.recount_on_change();

-- تسجيل مشاهدة: المسجَّل مرة واحدة لكل خبر، والزائر بلا تحقق (مؤشر تقريبي وليس رقماً موثّقاً ضد التلاعب).
create table public.news_viewers (
  news_id bigint not null references public.news (id) on delete cascade,
  user_id uuid   not null references public.users (id) on delete cascade,
  primary key (news_id, user_id)
);
alter table public.news_viewers enable row level security;
revoke all on public.news_viewers from anon, authenticated;

create function public.track_news_view(p_news_id bigint) returns void
language plpgsql security definer set search_path = public, pg_temp as $$
declare uid uuid := auth.uid(); n bigint;
begin
  if not exists (select 1 from public.news where id = p_news_id and status = 'approved' and published_at <= now()) then
    return;
  end if;
  if uid is not null then
    insert into public.news_viewers (news_id, user_id) values (p_news_id, uid) on conflict do nothing;
    get diagnostics n = row_count;
    if n = 0 then return; end if;
  end if;
  update public.news set view_count = view_count + 1 where id = p_news_id;
end;
$$;

-- ---------------------------------------------------------------------
-- كلمات محظورة + علامات التحذير
-- ---------------------------------------------------------------------
create table public.blocked_words (
  id         bigint generated always as identity primary key,
  word       text not null check (char_length(btrim(word)) between 2 and 80),
  created_by uuid default auth.uid() references public.users (id) on delete set null,
  created_at timestamptz not null default now()
);
create unique index blocked_words_word_key on public.blocked_words (lower(btrim(word)));
alter table public.blocked_words enable row level security;
revoke all on public.blocked_words from anon, authenticated;
grant select, delete on public.blocked_words to authenticated;
grant insert (word)  on public.blocked_words to authenticated;
create policy blocked_words_admin on public.blocked_words for all to authenticated
  using (public.is_admin()) with check (public.is_admin());

create table public.news_flags (
  news_id    bigint primary key references public.news (id) on delete cascade,
  reason     text not null,
  created_at timestamptz not null default now()
);
alter table public.news_flags enable row level security;
revoke all on public.news_flags from anon, authenticated;
grant select on public.news_flags to authenticated;
create policy news_flags_admin on public.news_flags for select to authenticated using (public.is_admin());

create function public.first_blocked_word(p_text text) returns text
language sql stable security definer set search_path = public, pg_temp as $$
  select word from public.blocked_words
  where position(lower(btrim(word)) in lower(coalesce(p_text, ''))) > 0
  order by id limit 1;
$$;

create function public.news_flag_blocked() returns trigger
language plpgsql security definer set search_path = public, pg_temp as $$
declare w text;
begin
  if new.status = 'pending' then
    w := public.first_blocked_word(new.title || ' ' || new.content);
    if w is not null then
      insert into public.news_flags (news_id, reason) values (new.id, 'كلمة محظورة: ' || w) on conflict do nothing;
    end if;
  end if;
  return null;
end;
$$;
create trigger news_flag_blocked after insert on public.news
  for each row execute function public.news_flag_blocked();

-- تعليق يحوي كلمة محظورة يُقبل ثم يُخفى تلقائياً (يبقى للأدمن في الجدول)
create function public.comments_flag_blocked() returns trigger
language plpgsql security definer set search_path = public, pg_temp as $$
begin
  if public.first_blocked_word(new.body) is not null then
    update public.comments set status = 'hidden' where id = new.id;
  end if;
  return null;
end;
$$;
-- AFTER (وليس BEFORE) لأن سياسة الإدراج تشترط status='visible' عند الفحص
create trigger comments_flag_blocked after insert on public.comments
  for each row execute function public.comments_flag_blocked();

-- ---------------------------------------------------------------------
-- إحصاءات الأدمن
-- ---------------------------------------------------------------------
create function public.admin_insights() returns jsonb
language plpgsql stable security definer set search_path = public, pg_temp as $$
begin
  if not public.is_admin() then
    raise exception 'admin_only' using errcode = '42501';
  end if;
  return jsonb_build_object(
    'top_news', coalesce((
      select jsonb_agg(jsonb_build_object('id', id, 'title', title, 'views', view_count,
                                          'credible', credible_count, 'comments', comment_count))
      from (select id, title, view_count, credible_count, comment_count from public.news
            where status = 'approved' and published_at <= now()
            order by view_count desc, published_at desc limit 5) t), '[]'::jsonb),
    'sources', coalesce((
      select jsonb_agg(jsonb_build_object('name', name, 'total', total, 'approved', approved,
                                          'rejected', rejected, 'pending', pending))
      from (select s.name,
                   count(n.id) as total,
                   count(n.id) filter (where n.status = 'approved') as approved,
                   count(n.id) filter (where n.status = 'rejected') as rejected,
                   count(n.id) filter (where n.status = 'pending')  as pending
            from public.sources s left join public.news n on n.source_id = s.id
            where s.is_active group by s.id, s.name order by count(n.id) desc, s.name limit 15) t), '[]'::jsonb),
    'posts', jsonb_build_object(
      'total',    (select count(*) from public.news where author_id is not null),
      'approved', (select count(*) from public.news where author_id is not null and status = 'approved'),
      'rejected', (select count(*) from public.news where author_id is not null and status = 'rejected'),
      'pending',  (select count(*) from public.news where author_id is not null and status = 'pending'),
      'last_7d',  (select count(*) from public.news where author_id is not null and created_at > now() - interval '7 days')),
    'flagged_pending', (select count(*) from public.news_flags f join public.news n on n.id = f.news_id where n.status = 'pending'),
    'blocked_words',   (select count(*) from public.blocked_words),
    'comments_hidden', (select count(*) from public.comments where status = 'hidden')
  );
end;
$$;

-- لا تنفيذ للدوال الداخلية من التطبيق
revoke execute on function
  public.news_zero_counts(), public.recount_news(bigint), public.recount_on_change(),
  public.first_blocked_word(text), public.news_flag_blocked(), public.comments_flag_blocked(),
  public.track_news_view(bigint), public.admin_insights()
  from public, anon, authenticated;
grant execute on function public.track_news_view(bigint) to anon, authenticated;
grant execute on function public.admin_insights() to authenticated;
