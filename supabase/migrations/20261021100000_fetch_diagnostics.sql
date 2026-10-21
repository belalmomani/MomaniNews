-- تشخيص أدق لمحرك الجلب: يفرّق بين «نجح التشغيل» و«وُجدت أخبار»، ويسجّل المرفوض وأسبابه وتقريراً لكل مصدر.
-- تغيير إضافي فقط (أعمدة جديدة بقيم افتراضية)؛ لا يمس البيانات ولا السياسات (fetch_runs للأدمن قراءةً فقط أصلاً).
alter table public.fetch_runs
  add column items_rejected int   not null default 0,
  add column reject_reasons jsonb not null default '{}'::jsonb,
  add column details        jsonb not null default '[]'::jsonb;
