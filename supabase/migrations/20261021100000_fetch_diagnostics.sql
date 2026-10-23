-- تشخيص أدق لمحرك الجلب: يفرّق بين «نجح التشغيل» و«وُجدت أخبار»، ويسجّل المرفوض وأسبابه وتقريراً لكل مصدر.
-- تغيير إضافي فقط (أعمدة جديدة بقيم افتراضية)؛ لا يمس البيانات ولا السياسات (fetch_runs للأدمن قراءةً فقط أصلاً).
alter table public.fetch_runs
  add column if not exists items_rejected int   not null default 0,
  add column if not exists reject_reasons jsonb not null default '{}'::jsonb,
  add column if not exists details        jsonb not null default '[]'::jsonb;
