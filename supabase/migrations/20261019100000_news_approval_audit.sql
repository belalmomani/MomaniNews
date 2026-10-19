-- =====================================================================
-- تسجيل من اعتمد الخبر ومتى (approved_by / approved_at) في الجدول نفسه، إضافةً إلى admin_logs.
-- يُضبط تلقائياً بالمشغّل عند الانتقال إلى approved، ولا يمكن للتطبيق تزويره
-- (القيمة تُكتب فوق أي قيمة يرسلها العميل). عند إعادة الخبر إلى حالة أخرى تُمسح القيمتان.
-- =====================================================================
alter table public.news
  add column approved_by uuid references public.users (id) on delete set null,
  add column approved_at timestamptz;

create or replace function public.news_before_write() returns trigger
language plpgsql as $$
begin
  if new.status = 'approved' and new.published_at is null then
    new.published_at := now();
  end if;
  if new.status = 'approved' and (tg_op = 'INSERT' or old.status is distinct from 'approved') then
    new.approved_by := auth.uid();
    new.approved_at := now();
  elsif new.status <> 'approved' then
    new.approved_by := null;
    new.approved_at := null;
  elsif tg_op = 'UPDATE' then
    new.approved_by := old.approved_by;     -- لا تعديل يدوي لقيمة الاعتماد
    new.approved_at := old.approved_at;
  end if;
  return new;
end;
$$;

-- الأخبار المعتمدة سابقاً: وقت الاعتماد التقريبي = تاريخ النشر؛ المعتمِد غير معروف فيبقى فارغاً
update public.news set approved_at = published_at where status = 'approved' and approved_at is null;
