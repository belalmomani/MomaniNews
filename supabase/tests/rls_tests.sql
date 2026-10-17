-- =====================================================================
-- اختبارات الأمان (RLS) لمخطط MomaniNews.
-- تعمل على Postgres محلي (بعد 00_local_supabase_stub.sql) وعلى Supabase SQL Editor.
-- كل شيء داخل معاملة تُلغى في النهاية (لا يبقى أي أثر). النتيجة تظهر كرسالة "خطأ":
--   RLS TESTS PASSED: N checks      ← نجحت كل الفحوص
--   RLS TESTS FAILED: ...           ← فشل ما، مع أسماء الفحوص
-- يحاكي المستخدمين بضبط request.jwt.claims والدور (anon / authenticated / service_role).
-- يُشغَّل على مشروع جديد فارغ فقط (يُنشئ مستخدمين تجريبيين في auth.users ثم يتراجع).
-- =====================================================================
begin;

create schema t;
create table t.results (n serial, name text, pass boolean, actual text, expected text);

-- يشغّل استعلاماً يرجع قيمة واحدة بهوية مستخدم ودور معيّنين؛ يرجع نص الخطأ ERR:<sqlstate> عند الفشل
create function t.sel(uid uuid, r text, q text) returns text language plpgsql as $$
declare res text;
begin
  perform set_config('request.jwt.claims',
    case when uid is null then '' else json_build_object('sub', uid, 'role', r)::text end, true);
  perform set_config('request.jwt.claim.sub', coalesce(uid::text, ''), true);
  execute format('set local role %I', r);
  begin
    execute q into res;
    res := coalesce(res, '<null>');
  exception when others then
    res := 'ERR:' || sqlstate;
  end;
  execute 'reset role';
  return res;
end $$;

-- يشغّل أمر كتابة ويرجع عدد الصفوف المتأثرة (أو ERR:<sqlstate>)
create function t.dml(uid uuid, r text, q text) returns text language plpgsql as $$
declare res text; n bigint;
begin
  perform set_config('request.jwt.claims',
    case when uid is null then '' else json_build_object('sub', uid, 'role', r)::text end, true);
  perform set_config('request.jwt.claim.sub', coalesce(uid::text, ''), true);
  execute format('set local role %I', r);
  begin
    execute q;
    get diagnostics n = row_count;
    res := n::text;
  exception when others then
    res := 'ERR:' || sqlstate;
  end;
  execute 'reset role';
  return res;
end $$;

create function t.chk(p_name text, p_actual text, p_expected text) returns void language sql as $$
  insert into t.results (name, pass, actual, expected)
  values (p_name, p_actual is not distinct from p_expected, p_actual, p_expected);
$$;

do $tests$
declare
  ua uuid := 'aaaaaaaa-0000-0000-0000-000000000001';
  ub uuid := 'bbbbbbbb-0000-0000-0000-000000000002';
  uc uuid := 'cccccccc-0000-0000-0000-000000000003';  -- admin
  ud uuid := 'dddddddd-0000-0000-0000-000000000004';  -- banned
  n_ok bigint; n_pend bigint; n_rej bigint; n_future bigint;
  anon text := 'anon'; au text := 'authenticated'; sr text := 'service_role'; su text := current_user;
  i int; v text; fails text; total int;
begin
  su := 'postgres';
  -- ---------- تجهيز (بصلاحيات المالك) ----------
  insert into auth.users (id, email, raw_user_meta_data) values
    (ua, 'a@test.local', '{"full_name":"علي","picture":"https://example.com/p.png"}'),
    (ub, 'b@test.local', '{"name":"باسم"}'),
    (uc, 'c@test.local', '{}'),
    (ud, 'd@test.local', '{"picture":"http://insecure.example/p.png"}');
  update public.users set role = 'admin' where id = uc;
  update public.users set is_banned = true where id = ud;

  insert into public.news (title, content, region_id, status, published_at)
    values ('خبر معتمد', 'نص خبر معتمد عن عبين', 'abeen', 'approved', now() - interval '1 hour') returning id into n_ok;
  insert into public.news (title, content, region_id) values ('خبر معلق', 'نص سري معلق', 'abelin') returning id into n_pend;
  insert into public.news (title, content, region_id, status) values ('خبر مرفوض', 'نص مرفوض', 'sakhra', 'rejected') returning id into n_rej;
  insert into public.news (title, content, region_id, status, published_at)
    values ('خبر مستقبلي', 'نص مستقبلي', 'abeen', 'approved', now() + interval '1 day') returning id into n_future;
  insert into public.news (title, content, region_id, status) values ('خبر مؤرشف', 'نص مؤرشف', 'abeen', 'archived');

  -- ---------- المستخدمون ----------
  perform t.chk('trigger: display_name من full_name', t.sel(null, su, format('select display_name from public.users where id=%L', ua)), 'علي');
  perform t.chk('trigger: avatar https يُقبل', t.sel(null, su, format('select avatar_url from public.users where id=%L', ua)), 'https://example.com/p.png');
  perform t.chk('trigger: avatar http يُتجاهل', t.sel(null, su, format('select coalesce(avatar_url,''<null>'') from public.users where id=%L', ud)), '<null>');
  perform t.chk('trigger: display_name من name', t.sel(null, su, format('select display_name from public.users where id=%L', ub)), 'باسم');
  perform t.chk('users: الزائر لا يقرأ الجدول', t.sel(null, anon, 'select count(*) from public.users'), 'ERR:42501');
  perform t.chk('users: المستخدم يرى صفه فقط', t.sel(ua, au, 'select count(*) from public.users'), '1');
  perform t.chk('users: الأدمن يرى الكل', t.sel(uc, au, 'select count(*) from public.users'), '4');
  perform t.chk('users: لا يرفع دوره', t.dml(ua, au, format('update public.users set role=''admin'' where id=%L', ua)), 'ERR:42501');
  perform t.chk('users: لا يفك حظره/يعدّل is_banned', t.dml(ua, au, format('update public.users set is_banned=false where id=%L', ua)), 'ERR:42501');
  perform t.chk('users: لا يوثّق نفسه', t.dml(ua, au, format('update public.users set verification=''approved'' where id=%L', ua)), 'ERR:42501');
  perform t.chk('users: لا يغيّر email', t.dml(ua, au, format('update public.users set email=''x@y.z'' where id=%L', ua)), 'ERR:42501');
  perform t.chk('users: يعدّل اسمه', t.dml(ua, au, format('update public.users set display_name=''علي المومني'' where id=%L', ua)), '1');
  perform t.chk('users: لا يعدّل اسم غيره', t.dml(ua, au, format('update public.users set display_name=''x'' where id=%L', ub)), '0');
  perform t.chk('users: avatar http مرفوض', t.dml(ua, au, format('update public.users set avatar_url=''http://x.y/z.png'' where id=%L', ua)), 'ERR:23514');
  perform t.chk('users: لا حذف', t.dml(ua, au, format('delete from public.users where id=%L', ua)), 'ERR:42501');
  perform t.chk('admin_set_user_flags: مستخدم عادي مرفوض', t.sel(ua, au, format('select public.admin_set_user_flags(%L,''admin'',false)', ub)), 'ERR:42501');
  perform t.chk('admin_set_user_flags: الزائر ليس لديه صلاحية تنفيذ', t.sel(null, anon, format('select public.admin_set_user_flags(%L,''admin'',false)', ub)), 'ERR:42501');
  perform t.chk('admin_set_user_flags: لا يخفّض نفسه', t.sel(uc, au, format('select public.admin_set_user_flags(%L,''user'',false)', uc)), 'ERR:P0001');
  perform t.chk('admin_set_user_flags: لا يحظر نفسه', t.sel(uc, au, format('select public.admin_set_user_flags(%L,''admin'',true)', uc)), 'ERR:P0001');
  perform t.chk('admin_set_user_flags: مستخدم غير موجود', t.sel(uc, au, 'select public.admin_set_user_flags(''eeeeeeee-0000-0000-0000-000000000005'',''user'',false)'), 'ERR:P0002');
  perform t.chk('public_profiles: لا يظهر المحظور', t.sel(null, anon, format('select count(*) from public.public_profiles where id=%L', ud)), '0');
  perform t.chk('public_profiles: يظهر العادي', t.sel(null, anon, format('select count(*) from public.public_profiles where id=%L', ua)), '1');
  perform t.chk('public_profiles: لا يكشف البريد', t.sel(null, anon, 'select email from public.public_profiles'), 'ERR:42703');

  -- ---------- الأخبار ----------
  perform t.chk('news: الزائر يرى المعتمد المنشور فقط', t.sel(null, anon, 'select count(*) from public.news'), '1');
  perform t.chk('news: المستخدم نفس الشيء', t.sel(ua, au, 'select count(*) from public.news'), '1');
  perform t.chk('news: الأدمن يرى الكل', t.sel(uc, au, 'select count(*) from public.news'), '5');
  perform t.chk('news: لا يقرأ المعلّق بالمعرّف', t.sel(null, anon, format('select count(*) from public.news where id=%s', n_pend)), '0');
  perform t.chk('news: لا يقرأ المستقبلي', t.sel(null, anon, format('select count(*) from public.news where id=%s', n_future)), '0');
  perform t.chk('news: الزائر لا يضيف', t.dml(null, anon, 'insert into public.news (title,content,region_id) values (''x'',''y'',''abeen'')'), 'ERR:42501');
  perform t.chk('news: المستخدم لا يضيف', t.dml(ua, au, 'insert into public.news (title,content,region_id,status) values (''x'',''y'',''abeen'',''approved'')'), 'ERR:42501');
  perform t.chk('news: المستخدم لا يعتمد', t.dml(ua, au, format('update public.news set status=''approved'' where id=%s', n_pend)), '0');
  perform t.chk('news: المستخدم لا يحذف', t.dml(ua, au, format('delete from public.news where id=%s', n_ok)), '0');
  perform t.chk('news: بحث نصي', t.sel(null, anon, 'select count(*) from public.news where search @@ plainto_tsquery(''simple'', ''عبين'')'), '1');
  perform t.chk('news: بحث لا يكشف المعلّق', t.sel(null, anon, 'select count(*) from public.news where search @@ plainto_tsquery(''simple'', ''سري'')'), '0');
  perform t.chk('news: الأدمن يعتمد', t.dml(uc, au, format('update public.news set status=''approved'' where id=%s', n_pend)), '1');
  perform t.chk('news: published_at يُضبط تلقائياً', t.sel(null, su, format('select (published_at is not null)::text from public.news where id=%s', n_pend)), 'true');
  perform t.chk('news: الزائر يرى المعتمد حديثاً', t.sel(null, anon, 'select count(*) from public.news'), '2');
  perform t.chk('admin_logs: تغيير الحالة سُجّل', t.sel(uc, au, format('select count(*) from public.admin_logs where entity_type=''news'' and entity_id=%L and admin_id=%L', n_pend::text, uc)), '1');
  perform t.chk('news: الأدمن يرجعه معلقاً', t.dml(uc, au, format('update public.news set status=''pending'', published_at=null where id=%s', n_pend)), '1');
  perform t.chk('regions: قراءة عامة', t.sel(null, anon, 'select count(*) from public.regions'), '3');
  perform t.chk('regions: المستخدم لا يضيف', t.dml(ua, au, 'insert into public.regions (id,name_ar) values (''x'',''x'')'), 'ERR:42501');

  -- ---------- التصويت ----------
  perform t.chk('votes: الزائر لا يصوّت', t.dml(null, anon, format('insert into public.news_votes (news_id,vote) values (%s,''credible'')', n_ok)), 'ERR:42501');
  perform t.chk('votes: مستخدم يصوّت', t.dml(ua, au, format('insert into public.news_votes (news_id,vote) values (%s,''credible'')', n_ok)), '1');
  perform t.chk('votes: صوت ثانٍ مرفوض (صوت واحد)', t.dml(ua, au, format('insert into public.news_votes (news_id,vote) values (%s,''not_credible'')', n_ok)), 'ERR:23505');
  perform t.chk('votes: انتحال user_id مرفوض', t.dml(ua, au, format('insert into public.news_votes (news_id,user_id,vote) values (%s,%L,''credible'')', n_ok, ub)), 'ERR:42501');
  perform t.chk('votes: خبر غير معتمد مرفوض', t.dml(ub, au, format('insert into public.news_votes (news_id,vote) values (%s,''credible'')', n_pend)), 'ERR:42501');
  perform t.chk('votes: المحظور مرفوض', t.dml(ud, au, format('insert into public.news_votes (news_id,vote) values (%s,''credible'')', n_ok)), 'ERR:42501');
  perform t.chk('votes: ب يصوّت', t.dml(ub, au, format('insert into public.news_votes (news_id,vote) values (%s,''credible'')', n_ok)), '1');
  perform t.chk('votes: تغيير صوته', t.dml(ua, au, format('update public.news_votes set vote=''not_credible'' where news_id=%s', n_ok)), '1');
  perform t.chk('votes: لا يغيّر صوت غيره', t.dml(ub, au, format('update public.news_votes set vote=''not_credible'' where user_id=%L', ua)), '0');
  perform t.chk('votes: لا يغيّر news_id', t.dml(ua, au, format('update public.news_votes set news_id=%s where user_id=%L', n_pend, ua)), 'ERR:42501');
  perform t.chk('votes: لا حذف', t.dml(ua, au, 'delete from public.news_votes'), 'ERR:42501');
  perform t.chk('votes: لا يرى أصوات غيره', t.sel(ub, au, 'select count(*) from public.news_votes'), '1');
  perform t.chk('votes: الزائر لا يقرأ الجدول', t.sel(null, anon, 'select count(*) from public.news_votes'), 'ERR:42501');
  perform t.chk('votes: العدّاد العام', t.sel(null, anon, format('select credible||''/''||not_credible from public.news_vote_counts where news_id=%s', n_ok)), '1/1');
  perform t.chk('votes: العدّاد لا يشمل غير المعتمد', t.sel(null, anon, format('select count(*) from public.news_vote_counts where news_id=%s', n_pend)), '0');

  -- ---------- التعليقات ----------
  perform t.chk('comments: الزائر لا يعلّق', t.dml(null, anon, format('insert into public.comments (news_id,body) values (%s,''x'')', n_ok)), 'ERR:42501');
  perform t.chk('comments: مستخدم يعلّق', t.dml(ua, au, format('insert into public.comments (news_id,body) values (%s,''  تعليق أول  '')', n_ok)), '1');
  perform t.chk('comments: يُقصّ النص', t.sel(null, su, format('select body from public.comments where user_id=%L', ua)), 'تعليق أول');
  perform t.chk('comments: حد المعدل', t.dml(ua, au, format('insert into public.comments (news_id,body) values (%s,''ثان'')', n_ok)), 'ERR:P0001');
  update public.comments set created_at = created_at - interval '1 hour';
  perform t.chk('comments: نص فارغ مرفوض', t.dml(ua, au, format('insert into public.comments (news_id,body) values (%s,''   '')', n_ok)), 'ERR:23514');
  perform t.chk('comments: أطول من 500 مرفوض', t.dml(ua, au, format('insert into public.comments (news_id,body) values (%s,%L)', n_ok, repeat('x', 501))), 'ERR:23514');
  perform t.chk('comments: 500 مقبول', t.dml(ua, au, format('insert into public.comments (news_id,body) values (%s,%L)', n_ok, repeat('x', 500))), '1');
  update public.comments set created_at = created_at - interval '1 hour';
  perform t.chk('comments: خبر غير معتمد مرفوض', t.dml(ua, au, format('insert into public.comments (news_id,body) values (%s,''x'')', n_pend)), 'ERR:42501');
  perform t.chk('comments: لا يحدد status', t.dml(ua, au, format('insert into public.comments (news_id,body,status) values (%s,''x'',''hidden'')', n_ok)), 'ERR:42501');
  perform t.chk('comments: لا ينتحل user_id', t.dml(ua, au, format('insert into public.comments (news_id,body,user_id) values (%s,''x'',%L)', n_ok, ub)), 'ERR:42501');
  perform t.chk('comments: المحظور مرفوض', t.dml(ud, au, format('insert into public.comments (news_id,body) values (%s,''x'')', n_ok)), 'ERR:42501');
  perform t.chk('comments: ب يعلّق', t.dml(ub, au, format('insert into public.comments (news_id,body) values (%s,''تعليق ب'')', n_ok)), '1');
  perform t.chk('comments_public: يعرض بلا user_id', t.sel(null, anon, 'select user_id from public.comments_public'), 'ERR:42703');
  perform t.chk('comments_public: الزائر يرى 3', t.sel(null, anon, 'select count(*) from public.comments_public'), '3');
  perform t.chk('comments_public: is_mine', t.sel(ub, au, 'select count(*) from public.comments_public where is_mine'), '1');
  perform t.chk('comments_public: الزائر is_mine=false', t.sel(null, anon, 'select count(*) from public.comments_public where is_mine'), '0');
  perform t.chk('comments: الزائر لا يقرأ الجدول', t.sel(null, anon, 'select count(*) from public.comments'), 'ERR:42501');
  perform t.chk('comments: لا يخفي تعليقه بنفسه', t.dml(ub, au, 'update public.comments set status=''hidden'' where user_id=''bbbbbbbb-0000-0000-0000-000000000002'''), '0');
  perform t.chk('comments: لا يخفي تعليق غيره', t.dml(ub, au, format('update public.comments set status=''hidden'' where user_id=%L', ua)), '0');
  perform t.chk('comments: لا يعدّل النص', t.dml(ub, au, 'update public.comments set body=''x'''), 'ERR:42501');
  perform t.chk('comments: لا يحذف تعليق غيره', t.dml(ub, au, format('delete from public.comments where user_id=%L', ua)), '0');
  perform t.chk('comments: الأدمن يخفي', t.dml(uc, au, format('update public.comments set status=''hidden'' where user_id=%L and body=''تعليق أول''', ua)), '1');
  perform t.chk('comments_public: المخفي لا يظهر', t.sel(null, anon, 'select count(*) from public.comments_public'), '2');
  perform t.chk('comments: صاحب التعليق يحذفه', t.dml(ub, au, format('delete from public.comments where user_id=%L', ub)), '1');
  insert into public.comments (news_id, user_id, body) values (n_ok, ud, 'تعليق المحظور');
  perform t.chk('comments_public: تعليق المحظور مخفي', t.sel(null, anon, 'select count(*) from public.comments_public where body=''تعليق المحظور'''), '0');

  -- ---------- طلبات التوثيق + التخزين ----------
  perform t.chk('verif: الزائر مرفوض', t.dml(null, anon, 'insert into public.verification_requests (full_name,document_path) values (''x y'',''x/y.jpg'')'), 'ERR:42501');
  perform t.chk('verif: مسار خارج مجلده', t.dml(ua, au, format('insert into public.verification_requests (full_name,document_path) values (''علي أحمد'', %L)', ub || '/id.jpg')), 'ERR:42501');
  perform t.chk('verif: مسار غير صالح (قيد القاعدة)', t.dml(null, su, format('insert into public.verification_requests (user_id,full_name,document_path) values (%L,''علي أحمد'', ''../x.jpg'')', ua)), 'ERR:23514');
  perform t.chk('verif: مسار غير صالح عبر API مرفوض', t.dml(ua, au, 'insert into public.verification_requests (full_name,document_path) values (''علي أحمد'', ''../x.jpg'')'), 'ERR:42501');
  perform t.chk('verif: طلب صحيح', t.dml(ua, au, format('insert into public.verification_requests (full_name,document_path) values (''علي أحمد'', %L)', ua || '/id.jpg')), '1');
  perform t.chk('verif: users.verification صارت pending', t.sel(null, su, format('select verification::text from public.users where id=%L', ua)), 'pending');
  perform t.chk('verif: طلب معلّق ثانٍ مرفوض', t.dml(ua, au, format('insert into public.verification_requests (full_name,document_path) values (''علي أحمد'', %L)', ua || '/id2.jpg')), 'ERR:42501');
  perform t.chk('verif: ب لا يرى طلب أ', t.sel(ub, au, 'select count(*) from public.verification_requests'), '0');
  perform t.chk('verif: الأدمن يرى', t.sel(uc, au, 'select count(*) from public.verification_requests'), '1');
  perform t.chk('verif: لا يعتمد طلبه', t.dml(ua, au, 'update public.verification_requests set status=''approved'''), '0');
  perform t.chk('verif: لا يحدد user_id', t.dml(ua, au, 'update public.verification_requests set user_id=user_id'), 'ERR:42501');
  perform t.chk('verif: الأدمن يعتمد', t.dml(uc, au, format('update public.verification_requests set status=''approved'' where user_id=%L', ua)), '1');
  perform t.chk('verif: users.verification صارت approved', t.sel(null, su, format('select verification::text from public.users where id=%L', ua)), 'approved');
  perform t.chk('verif: المراجِع سُجّل', t.sel(null, su, format('select (reviewed_by=%L)::text from public.verification_requests where user_id=%L', uc, ua)), 'true');
  perform t.chk('verif: لا مراجعة ثانية', t.dml(uc, au, format('update public.verification_requests set status=''rejected'' where user_id=%L', ua)), 'ERR:P0001');
  perform t.chk('verif: الموثّق لا يقدّم طلباً جديداً', t.dml(ua, au, format('insert into public.verification_requests (full_name,document_path) values (''علي أحمد'', %L)', ua || '/id3.jpg')), 'ERR:42501');
  perform t.chk('verif: ب يقدّم', t.dml(ub, au, format('insert into public.verification_requests (full_name,document_path) values (''باسم خالد'', %L)', ub || '/id.png')), '1');
  perform t.chk('verif: الأدمن يرفض', t.dml(uc, au, format('update public.verification_requests set status=''rejected'', admin_note=''صورة غير واضحة'' where user_id=%L', ub)), '1');
  perform t.chk('verif: المرفوض يعيد التقديم', t.dml(ub, au, format('insert into public.verification_requests (full_name,document_path) values (''باسم خالد'', %L)', ub || '/id2.png')), '1');
  perform t.chk('storage: رفع إلى مجلده', t.dml(ua, au, format('insert into storage.objects (bucket_id,name) values (''verification-docs'', %L)', ua || '/id.jpg')), '1');
  perform t.chk('storage: رفع إلى مجلد غيره مرفوض', t.dml(ua, au, format('insert into storage.objects (bucket_id,name) values (''verification-docs'', %L)', ub || '/x.jpg')), 'ERR:42501');
  perform t.chk('storage: حاوية أخرى مرفوضة', t.dml(ua, au, format('insert into storage.objects (bucket_id,name) values (''other'', %L)', ua || '/x.jpg')), 'ERR:42501');
  perform t.chk('storage: الزائر مرفوض', t.dml(null, anon, 'insert into storage.objects (bucket_id,name) values (''verification-docs'', ''x/y.jpg'')'), 'ERR:42501');
  perform t.chk('storage: صاحب الملف يراه', t.sel(ua, au, 'select count(*) from storage.objects'), '1');
  perform t.chk('storage: ب لا يرى', t.sel(ub, au, 'select count(*) from storage.objects'), '0');
  perform t.chk('storage: الأدمن يرى', t.sel(uc, au, 'select count(*) from storage.objects'), '1');
  perform t.chk('storage: الزائر لا يرى', t.sel(null, anon, 'select count(*) from storage.objects'), '0');
  perform t.chk('storage: الحاوية خاصة', t.sel(null, su, 'select public::text from storage.buckets where id=''verification-docs'''), 'false');

  -- ---------- الإعلانات ----------
  perform t.chk('ads: الزائر مرفوض', t.dml(null, anon, 'insert into public.advertisements (title) values (''x'')'), 'ERR:42501');
  perform t.chk('ads: طلب إعلان', t.dml(ua, au, 'insert into public.advertisements (title,body,link_url) values (''إعلان 1'',''نص'',''https://example.com'')'), '1');
  perform t.chk('ads: لا يعتمد بنفسه', t.dml(ua, au, 'insert into public.advertisements (title,status) values (''x'',''approved'')'), 'ERR:42501');
  perform t.chk('ads: رابط http مرفوض', t.dml(ua, au, 'insert into public.advertisements (title,link_url) values (''x'',''http://a.b'')'), 'ERR:23514');
  for i in 2..5 loop
    perform t.chk('ads: طلب رقم ' || i, t.dml(ua, au, format('insert into public.advertisements (title) values (%L)', 'إعلان ' || i)), '1');
  end loop;
  perform t.chk('ads: السادس معلّق مرفوض', t.dml(ua, au, 'insert into public.advertisements (title) values (''إعلان 6'')'), 'ERR:P0001');
  perform t.chk('ads: ب لا يرى طلبات أ', t.sel(ub, au, 'select count(*) from public.advertisements'), '0');
  perform t.chk('ads: أ يرى طلباته', t.sel(ua, au, 'select count(*) from public.advertisements'), '5');
  perform t.chk('ads: لا يعدّل طلبه', t.dml(ua, au, 'update public.advertisements set status=''approved'''), '0');
  perform t.chk('ads: لا يحذف طلبه', t.dml(ua, au, 'delete from public.advertisements'), '0');
  perform t.chk('ads_public: لا شيء قبل الاعتماد', t.sel(null, anon, 'select count(*) from public.ads_public'), '0');
  perform t.chk('ads: اعتماد بلا تواريخ مرفوض', t.dml(uc, au, 'update public.advertisements set status=''approved'' where title=''إعلان 1'''), 'ERR:23514');
  perform t.chk('ads: اعتماد بتواريخ', t.dml(uc, au, 'update public.advertisements set status=''approved'', starts_at=now()-interval ''1 day'', ends_at=now()+interval ''1 day'' where title=''إعلان 1'''), '1');
  perform t.chk('ads: اعتماد منتهٍ', t.dml(uc, au, 'update public.advertisements set status=''approved'', starts_at=now()-interval ''3 days'', ends_at=now()-interval ''2 days'' where title=''إعلان 2'''), '1');
  perform t.chk('ads: ترتيب تواريخ خاطئ مرفوض', t.dml(uc, au, 'update public.advertisements set starts_at=now(), ends_at=now()-interval ''1 day'' where title=''إعلان 3'''), 'ERR:23514');
  perform t.chk('ads_public: الساري فقط', t.sel(null, anon, 'select count(*) from public.ads_public'), '1');
  perform t.chk('ads_public: لا يكشف admin_note', t.sel(null, anon, 'select admin_note from public.ads_public'), 'ERR:42703');
  perform t.chk('ads_public: لا يكشف user_id', t.sel(null, anon, 'select user_id from public.ads_public'), 'ERR:42703');
  perform t.chk('ads: الجدول مغلق للزائر', t.sel(null, anon, 'select count(*) from public.advertisements'), 'ERR:42501');

  -- ---------- الإشعارات ----------
  perform t.chk('notif: المستخدم لا ينشئ', t.dml(ua, au, 'insert into public.notifications (title) values (''x'')'), 'ERR:42501');
  perform t.chk('notif: الأدمن ينشئ عاماً', t.dml(uc, au, 'insert into public.notifications (title) values (''إشعار عام'')'), '1');
  perform t.chk('notif: الأدمن ينشئ لأ', t.dml(uc, au, format('insert into public.notifications (user_id,title) values (%L,''لأ'')', ua)), '1');
  perform t.chk('notif: الأدمن ينشئ لب', t.dml(uc, au, format('insert into public.notifications (user_id,title) values (%L,''لب'')', ub)), '1');
  perform t.chk('notif: أ يرى العام وخاصه', t.sel(ua, au, 'select count(*) from public.notifications'), '2');
  perform t.chk('notif: الزائر مرفوض', t.sel(null, anon, 'select count(*) from public.notifications'), 'ERR:42501');
  perform t.chk('notif: يعلّم خاصه مقروءاً', t.dml(ua, au, format('update public.notifications set read_at=now() where user_id=%L', ua)), '1');
  perform t.chk('notif: لا يعلّم إشعار غيره', t.dml(ua, au, format('update public.notifications set read_at=now() where user_id=%L', ub)), '0');
  perform t.chk('notif: لا يعلّم العام', t.dml(ua, au, 'update public.notifications set read_at=now() where user_id is null'), '0');
  perform t.chk('notif: لا يعدّل العنوان', t.dml(ua, au, 'update public.notifications set title=''x'''), 'ERR:42501');
  perform t.chk('notif: لا يحذف', t.dml(ua, au, 'delete from public.notifications'), '0');

  -- ---------- سجل التدقيق ----------
  perform t.chk('admin_logs: المستخدم لا يرى شيئاً', t.sel(ua, au, 'select count(*) from public.admin_logs'), '0');
  perform t.chk('admin_logs: الأدمن يرى', t.sel(uc, au, 'select (count(*)>0)::text from public.admin_logs'), 'true');
  perform t.chk('admin_logs: الأدمن لا يكتب مباشرة', t.dml(uc, au, 'insert into public.admin_logs (action,entity_type) values (''x'',''y'')'), 'ERR:42501');
  perform t.chk('admin_logs: لا تعديل', t.dml(uc, au, 'update public.admin_logs set action=''x'''), 'ERR:42501');
  perform t.chk('admin_logs: لا حذف', t.dml(uc, au, 'delete from public.admin_logs'), 'ERR:42501');
  perform t.chk('admin_logs: الزائر مرفوض', t.sel(null, anon, 'select count(*) from public.admin_logs'), 'ERR:42501');
  perform t.chk('admin_set_user_flags: ينجح ويُسجَّل', t.sel(uc, au, format('select public.admin_set_user_flags(%L,''user'',true)', ub)), '');
  perform t.chk('admin_set_user_flags: الحظر طُبّق', t.sel(null, su, format('select is_banned::text from public.users where id=%L', ub)), 'true');
  perform t.chk('admin_set_user_flags: سُجّل', t.sel(uc, au, 'select count(*) from public.admin_logs where action=''set_user_flags'''), '1');
  perform t.chk('المحظور لا يصوّت بعد الحظر', t.dml(ub, au, format('update public.news_votes set vote=''credible'' where user_id=%L', ub)), '0');
  perform t.chk('admin_set_user_flags: رفع الحظر', t.sel(uc, au, format('select public.admin_set_user_flags(%L,''user'',false)', ub)), '');

  -- ---------- المصادر والكلمات وسجل الجلب ----------
  insert into public.sources (name, url, is_active) values ('مصدر فعال', 'https://a.example/rss', true), ('مصدر معطل', 'https://b.example/rss', false);
  perform t.chk('sources: الزائر يرى الفعالة فقط', t.sel(null, anon, 'select count(*) from public.sources'), '1');
  perform t.chk('sources: الأدمن يرى الكل', t.sel(uc, au, 'select count(*) from public.sources'), '2');
  perform t.chk('sources: المستخدم لا يضيف', t.dml(ua, au, 'insert into public.sources (name,url) values (''x'',''https://c.example'')'), 'ERR:42501');
  perform t.chk('sources: رابط http مرفوض', t.dml(uc, au, 'insert into public.sources (name,url) values (''x'',''http://c.example'')'), 'ERR:23514');
  insert into public.keywords (word) values ('عبين');
  perform t.chk('keywords: المستخدم لا يرى', t.sel(ua, au, 'select count(*) from public.keywords'), '0');
  perform t.chk('keywords: الزائر مرفوض', t.sel(null, anon, 'select count(*) from public.keywords'), 'ERR:42501');
  perform t.chk('keywords: المستخدم لا يضيف', t.dml(ua, au, 'insert into public.keywords (word) values (''x'')'), 'ERR:42501');
  perform t.chk('keywords: الأدمن يضيف', t.dml(uc, au, 'insert into public.keywords (word) values (''عبلين'')'), '1');
  perform t.chk('keywords: التكرار مرفوض (حالة أحرف/مسافات)', t.dml(uc, au, 'insert into public.keywords (word) values ('' عبلين '')'), 'ERR:23505');
  perform t.chk('failed_fetch: المستخدم لا يكتب', t.dml(ua, au, 'insert into public.failed_fetch_logs (url) values (''x'')'), 'ERR:42501');
  perform t.chk('failed_fetch: service_role يكتب', t.dml(null, sr, 'insert into public.failed_fetch_logs (url,error) values (''https://a.example'',''timeout'')'), '1');
  perform t.chk('failed_fetch: المستخدم لا يرى', t.sel(ua, au, 'select count(*) from public.failed_fetch_logs'), '0');
  perform t.chk('failed_fetch: الأدمن يرى', t.sel(uc, au, 'select count(*) from public.failed_fetch_logs'), '1');
  perform t.chk('ingest: service_role يضيف خبراً معلّقاً', t.dml(null, sr, 'insert into public.news (title,content,region_id,original_url) values (''مجلوب'',''نص'',''abeen'',''https://news.example/1'')'), '1');
  perform t.chk('ingest: المجلوب لا يظهر للعامة', t.sel(null, anon, 'select count(*) from public.news where title=''مجلوب'''), '0');
  perform t.chk('ingest: تكرار الرابط مرفوض', t.dml(null, sr, 'insert into public.news (title,content,region_id,original_url) values (''مجلوب2'',''نص'',''abeen'',''https://news.example/1'')'), 'ERR:23505');

  -- ---------- دوال داخلية وأدمن محظور ----------
  perform t.chk('functions: دالة مشغّل غير قابلة للتنفيذ', t.sel(ua, au, 'select public.comments_before_insert()'), 'ERR:42501');
  perform t.chk('functions: handle_new_user غير قابلة للتنفيذ', t.sel(ua, au, 'select public.handle_new_user()'), 'ERR:42501');
  update public.users set is_banned = true where id = uc;
  perform t.chk('أدمن محظور يفقد الصلاحية', t.sel(uc, au, 'select count(*) from public.news'), t.sel(null, anon, 'select count(*) from public.news'));
  update public.users set is_banned = false where id = uc;

  -- ---------- الحذف المتسلسل ----------
  delete from auth.users where id = ua;
  perform t.chk('cascade: users', t.sel(null, su, format('select count(*) from public.users where id=%L', ua)), '0');
  perform t.chk('cascade: votes', t.sel(null, su, format('select count(*) from public.news_votes where user_id=%L', ua)), '0');
  perform t.chk('cascade: comments', t.sel(null, su, format('select count(*) from public.comments where user_id=%L', ua)), '0');
  perform t.chk('cascade: verification', t.sel(null, su, format('select count(*) from public.verification_requests where user_id=%L', ua)), '0');
  perform t.chk('cascade: الإعلانات تبقى بلا مالك', t.sel(null, su, 'select count(*) from public.advertisements where user_id is null and title like ''إعلان%'''), '5');

  -- ---------- وسائط الأخبار ----------
  perform t.chk('media: المستخدم العادي لا يرفع', t.dml(ua, au, 'insert into storage.objects (bucket_id,name) values (''news-media'', ''202610/a.jpg'')'), 'ERR:42501');
  perform t.chk('media: الزائر لا يرفع', t.dml(null, anon, 'insert into storage.objects (bucket_id,name) values (''news-media'', ''202610/a.jpg'')'), 'ERR:42501');
  perform t.chk('media: الأدمن يرفع', t.dml(uc, au, 'insert into storage.objects (bucket_id,name) values (''news-media'', ''202610/a.jpg'')'), '1');
  perform t.chk('media: الزائر يقرأ ملفات الأخبار فقط', t.sel(null, anon, 'select count(*) from storage.objects'), '1');
  perform t.chk('media: المستخدم العادي لا يحذف', t.dml(ua, au, 'delete from storage.objects where bucket_id=''news-media'''), '0');
  perform t.chk('media: PNG مسموح', t.sel(null, su, 'select (''image/png'' = any(allowed_mime_types))::text from storage.buckets where id=''news-media'''), 'true');
  perform t.chk('media: WEBM مسموح', t.sel(null, su, 'select (''video/webm'' = any(allowed_mime_types))::text from storage.buckets where id=''news-media'''), 'true');
  perform t.chk('media: exe غير مسموح', t.sel(null, su, 'select (''application/x-msdownload'' = any(allowed_mime_types))::text from storage.buckets where id=''news-media'''), 'false');
  perform t.chk('media: الأدمن يحذف', t.dml(uc, au, 'delete from storage.objects where bucket_id=''news-media'''), '1');
  perform t.chk('media: video_url http مرفوض', t.dml(null, sr, 'insert into public.news (title,content,region_id,video_url) values (''ف'',''ن'',''abeen'',''http://x.y/v.mp4'')'), 'ERR:23514');
  perform t.chk('media: video_url https مقبول', t.dml(null, sr, 'insert into public.news (title,content,region_id,video_url) values (''ف'',''ن'',''abeen'',''https://x.y/v.mp4'')'), '1');

  -- ---------- حذف الحساب الذاتي ----------
  perform t.chk('delete_my_account: الزائر مرفوض', t.sel(null, anon, 'select public.delete_my_account()'), 'ERR:42501');
  perform t.chk('delete_my_account: الأدمن الوحيد مرفوض', t.sel(uc, au, 'select public.delete_my_account()'), 'ERR:P0001');
  perform t.chk('delete_my_account: الأدمن لا يزال موجوداً', t.sel(null, su, format('select count(*) from public.users where id=%L', uc)), '1');
  perform t.chk('delete_my_account: مستخدم عادي ينجح', t.sel(ub, au, 'select public.delete_my_account()::text'), '');
  perform t.chk('delete_my_account: حُذف من auth', t.sel(null, su, format('select count(*) from auth.users where id=%L', ub)), '0');
  perform t.chk('delete_my_account: حُذف من users', t.sel(null, su, format('select count(*) from public.users where id=%L', ub)), '0');
  perform t.chk('delete_my_account: المحظور يستطيع حذف حسابه', t.sel(ud, au, 'select public.delete_my_account()::text'), '');
  perform t.chk('delete_my_account: الآخرون لم يتأثروا', t.sel(null, su, 'select count(*) from public.users'), '1');

  -- ---------- النتيجة ----------
  select count(*) into total from t.results;
  select string_agg(name || ' [فعلي=' || coalesce(actual, 'NULL') || ' متوقع=' || coalesce(expected, 'NULL') || ']', E'\n')
    into fails from t.results where not pass;
  if fails is not null then
    raise exception E'RLS TESTS FAILED (% of % checks):\n%', (select count(*) from t.results where not pass), total, fails;
  end if;
  raise exception 'RLS TESTS PASSED: % checks', total;
end
$tests$;

rollback;
