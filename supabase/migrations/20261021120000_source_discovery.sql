-- سجل دائم لاكتشاف المصادر: لا يُعاد فحص النطاق نفسه كل تشغيل، وتُحفظ نتيجة كل فحص وسببها.
-- 1) sources: أصل المصدر (يدوي/بحث/آلي) وتاريخ اكتشافه وعدد الأخبار ذات الصلة وقت التحقق. أعمدة إضافية فقط.
-- 2) source_candidates: نطاقات مرشحة (من نتائج البحث أو الأخبار المخزّنة) وحالة فحص كل منها وموعد إعادة الفحص.
--    الكتابة من service_role فقط (مهمة GitHub Actions)؛ القراءة للأدمن فقط.
alter table public.sources
  add column if not exists origin         text not null default 'manual' check (origin in ('manual', 'search', 'auto')),
  add column if not exists discovered_at  timestamptz,
  add column if not exists relevance_hits int  not null default 0;

create table if not exists public.source_candidates (
  domain        text primary key check (domain ~ '^[a-z0-9.-]{3,253}$'),
  via           text not null default 'search' check (via in ('search', 'news', 'admin')),
  relevant      boolean not null default false,
  status        text not null default 'new' check (status in ('new', 'registered', 'rejected', 'blocked', 'stale')),
  reason        text check (reason is null or char_length(reason) <= 300),
  source_id     bigint references public.sources (id) on delete set null,
  found_at      timestamptz not null default now(),
  checked_at    timestamptz,
  next_check_at timestamptz not null default now()
);
create index if not exists source_candidates_due_idx on public.source_candidates (status, next_check_at);

alter table public.source_candidates enable row level security;
revoke all on public.source_candidates from anon, authenticated;
grant select on public.source_candidates to authenticated;
drop policy if exists source_candidates_admin on public.source_candidates;
create policy source_candidates_admin on public.source_candidates for select to authenticated using (public.is_admin());
