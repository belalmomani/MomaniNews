-- التفاعلات (إعجاب، حب، اهتمام، ضحك، دهشة، حزن، غضب): تفاعل واحد لكل مستخدم لكل خبر.
-- جدول جديد وعرض جديد فقط؛ لا تعديل على جداول قائمة. مبنيان على نمط news_votes نفسه (RLS + المفتاح الأساسي).
create table if not exists public.news_reactions (
  news_id    bigint not null references public.news (id) on delete cascade,
  user_id    uuid   not null default auth.uid() references public.users (id) on delete cascade,
  reaction   text   not null check (reaction in ('like','love','care','haha','wow','sad','angry')),
  created_at timestamptz not null default now(),
  updated_at timestamptz not null default now(),
  primary key (news_id, user_id)
);

drop trigger if exists news_reactions_updated_at on public.news_reactions;
create trigger news_reactions_updated_at
  before update on public.news_reactions
  for each row execute function public.set_updated_at();

-- العدّاد العام: للأخبار المعتمدة المنشورة فقط (كعرض الأصوات).
create or replace view public.news_reaction_counts as
  select r.news_id, r.reaction, count(*)::int as n
  from public.news_reactions r
  join public.news n on n.id = r.news_id
  where n.status = 'approved' and n.published_at <= now()
  group by r.news_id, r.reaction;

alter table public.news_reactions enable row level security;
revoke all on public.news_reactions from anon, authenticated;
grant select on public.news_reactions to authenticated;
grant insert (news_id, reaction) on public.news_reactions to authenticated;
grant update (reaction)          on public.news_reactions to authenticated;
grant delete                     on public.news_reactions to authenticated;
grant select on public.news_reaction_counts to anon, authenticated;

drop policy if exists reactions_read_own on public.news_reactions;
create policy reactions_read_own on public.news_reactions for select to authenticated
  using (user_id = auth.uid() or public.is_admin());

drop policy if exists reactions_insert on public.news_reactions;
create policy reactions_insert on public.news_reactions for insert to authenticated
  with check (
    user_id = auth.uid()
    and public.can_interact()
    and exists (select 1 from public.news n where n.id = news_id and n.status = 'approved')
  );

drop policy if exists reactions_update on public.news_reactions;
create policy reactions_update on public.news_reactions for update to authenticated
  using (user_id = auth.uid() and public.can_interact())
  with check (user_id = auth.uid() and public.can_interact());

drop policy if exists reactions_delete on public.news_reactions;
create policy reactions_delete on public.news_reactions for delete to authenticated
  using (user_id = auth.uid());
