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
  ue uuid := 'eeeeeeee-0000-0000-0000-000000000005';  -- كاتب منشورات
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
  perform t.chk('regions: قراءة عامة', t.sel(null, anon, 'select count(*) from public.regions'), '4');
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

  -- ---------- التفاعلات (reactions) ----------
  perform t.chk('reactions: الزائر لا يتفاعل', t.dml(null, anon, format('insert into public.news_reactions (news_id,reaction) values (%s,''like'')', n_ok)), 'ERR:42501');
  perform t.chk('reactions: مستخدم يتفاعل', t.dml(ua, au, format('insert into public.news_reactions (news_id,reaction) values (%s,''like'')', n_ok)), '1');
  perform t.chk('reactions: تفاعل ثانٍ مرفوض (واحد لكل خبر)', t.dml(ua, au, format('insert into public.news_reactions (news_id,reaction) values (%s,''love'')', n_ok)), 'ERR:23505');
  perform t.chk('reactions: نوع غير معروف مرفوض', t.dml(ub, au, format('insert into public.news_reactions (news_id,reaction) values (%s,''xyz'')', n_ok)), 'ERR:23514');
  perform t.chk('reactions: انتحال user_id مرفوض', t.dml(ub, au, format('insert into public.news_reactions (news_id,user_id,reaction) values (%s,%L,''love'')', n_ok, ua)), 'ERR:42501');
  perform t.chk('reactions: خبر غير معتمد مرفوض', t.dml(ub, au, format('insert into public.news_reactions (news_id,reaction) values (%s,''love'')', n_pend)), 'ERR:42501');
  perform t.chk('reactions: المحظور مرفوض', t.dml(ud, au, format('insert into public.news_reactions (news_id,reaction) values (%s,''love'')', n_ok)), 'ERR:42501');
  perform t.chk('reactions: ب يتفاعل', t.dml(ub, au, format('insert into public.news_reactions (news_id,reaction) values (%s,''love'')', n_ok)), '1');
  perform t.chk('reactions: تغيير تفاعله', t.dml(ua, au, format('update public.news_reactions set reaction=''wow'' where news_id=%s', n_ok)), '1');
  perform t.chk('reactions: لا يغيّر تفاعل غيره', t.dml(ub, au, format('update public.news_reactions set reaction=''sad'' where user_id=%L', ua)), '0');
  perform t.chk('reactions: لا يغيّر news_id', t.dml(ua, au, format('update public.news_reactions set news_id=%s where user_id=%L', n_pend, ua)), 'ERR:42501');
  perform t.chk('reactions: لا يرى تفاعلات غيره', t.sel(ub, au, 'select count(*) from public.news_reactions'), '1');
  perform t.chk('reactions: الزائر لا يقرأ الجدول', t.sel(null, anon, 'select count(*) from public.news_reactions'), 'ERR:42501');
  perform t.chk('reactions: العدّاد العام', t.sel(null, anon, format('select string_agg(reaction||'':''||n, '','' order by reaction) from public.news_reaction_counts where news_id=%s', n_ok)), 'love:1,wow:1');
  perform t.chk('reactions: العدّاد لا يشمل غير المعتمد', t.sel(null, anon, format('select count(*) from public.news_reaction_counts where news_id=%s', n_pend)), '0');
  perform t.chk('reactions: لا يحذف تفاعل غيره', t.dml(ub, au, format('delete from public.news_reactions where user_id=%L', ua)), '0');
  perform t.chk('reactions: يحذف تفاعله', t.dml(ua, au, format('delete from public.news_reactions where news_id=%s', n_ok)), '1');
  perform t.chk('reactions: الزائر لا يحذف', t.dml(null, anon, 'delete from public.news_reactions'), 'ERR:42501');
  perform t.dml(ua, au, format('insert into public.news_reactions (news_id,reaction) values (%s,''like'')', n_ok));

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
  -- ---------- إيقاف الإعلان والعدّادات ----------
  perform t.chk('adpause: الساري يظهر', t.sel(null, anon, 'select count(*) from public.ads_public'), '1');
  perform t.chk('adpause: الأدمن يوقف', t.dml(uc, au, 'update public.advertisements set is_paused=true where title=''إعلان 1'''), '1');
  perform t.chk('adpause: الموقوف لا يظهر', t.sel(null, anon, 'select count(*) from public.ads_public'), '0');
  perform t.chk('adpause: المستخدم لا يوقف/يستأنف', t.dml(ua, au, 'update public.advertisements set is_paused=false'), '0');
  perform t.chk('adpause: المستخدم لا يضبط is_paused عند الإدراج', t.dml(ua, au, 'insert into public.advertisements (title,is_paused) values (''x'',true)'), 'ERR:42501');
  perform t.chk('adpause: الأدمن يستأنف', t.dml(uc, au, 'update public.advertisements set is_paused=false where title=''إعلان 1'''), '1');
  perform t.chk('track: مشاهدة من الزائر', t.sel(null, anon, format('select public.track_ad(%s,''view'')::text', (select id from public.advertisements where title='إعلان 1'))), '');
  perform t.chk('track: نقرة من الزائر', t.sel(null, anon, format('select public.track_ad(%s,''click'')::text', (select id from public.advertisements where title='إعلان 1'))), '');
  perform t.chk('track: العدّادان زادا', t.sel(null, su, 'select views||''/''||clicks from public.advertisements where title=''إعلان 1'''), '1/1');
  perform t.chk('track: المنتهي لا يُحتسب', t.sel(null, anon, format('select public.track_ad(%s,''view'')::text', (select id from public.advertisements where title='إعلان 2'))), '');
  perform t.chk('track: المنتهي بقي صفراً', t.sel(null, su, 'select views::text from public.advertisements where title=''إعلان 2'''), '0');
  perform t.chk('track: نوع خاطئ مرفوض', t.sel(null, anon, 'select public.track_ad(1,''x'')'), 'ERR:P0001');
  perform t.chk('track: لا تعديل مباشر للعدّاد', t.dml(null, anon, 'update public.advertisements set views=999'), 'ERR:42501');

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
  insert into public.keywords (word) values ('كلمة-اختبار-1');
  perform t.chk('keywords: المستخدم لا يرى', t.sel(ua, au, 'select count(*) from public.keywords'), '0');
  perform t.chk('keywords: الزائر مرفوض', t.sel(null, anon, 'select count(*) from public.keywords'), 'ERR:42501');
  perform t.chk('keywords: المستخدم لا يضيف', t.dml(ua, au, 'insert into public.keywords (word) values (''x'')'), 'ERR:42501');
  perform t.chk('keywords: الأدمن يضيف', t.dml(uc, au, 'insert into public.keywords (word) values (''كلمة-اختبار-2'')'), '1');
  perform t.chk('keywords: التكرار مرفوض (حالة أحرف/مسافات)', t.dml(uc, au, 'insert into public.keywords (word) values ('' كلمة-اختبار-2 '')'), 'ERR:23505');
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

  -- ---------- الإبلاغ عن تعليق ----------
  perform t.chk('report: تحضير تعليق ظاهر', t.dml(ub, au, format('insert into public.comments (news_id,body) values (%s,''للإبلاغ'')', n_ok)), '1');
  perform t.chk('report: الزائر مرفوض', t.sel(null, anon, 'select public.report_comment((select min(id) from public.comments))'), 'ERR:42501');
  perform t.chk('report: لا يبلّغ عن تعليقه', t.sel(ub, au, format('select public.report_comment((select min(id) from public.comments where user_id=%L))', ub)), 'ERR:P0001');
  perform t.chk('report: مستخدم يبلّغ عن تعليق غيره', t.sel(uc, au, format('select public.report_comment((select min(id) from public.comments where user_id=%L), ''مسيء'')::text', ub)), '');
  perform t.chk('report: التكرار لا يضاعف', t.sel(uc, au, format('select public.report_comment((select min(id) from public.comments where user_id=%L))::text', ub)), '');
  perform t.chk('report: بلاغ واحد فقط', t.sel(null, su, 'select count(*) from public.comment_reports'), '1');
  perform t.chk('report: تعليق غير موجود', t.sel(uc, au, 'select public.report_comment(-1)'), 'ERR:P0002');
  perform t.chk('report: الأدمن يقرأ البلاغات', t.sel(uc, au, 'select count(*) from public.comment_reports'), '1');
  perform t.chk('report: المستخدم العادي لا يرى', t.sel(ua, au, 'select count(*) from public.comment_reports'), '0');
  perform t.chk('report: الزائر محظور من الجدول', t.sel(null, anon, 'select count(*) from public.comment_reports'), 'ERR:42501');
  perform t.chk('report: لا إدخال مباشر', t.dml(ua, au, format('insert into public.comment_reports (comment_id,reporter_id) values (1,%L)', ua)), 'ERR:42501');
  perform t.chk('report: المحظور مرفوض', t.sel(ud, au, format('select public.report_comment((select min(id) from public.comments where user_id=%L))', ub)), 'ERR:42501');
  perform t.chk('report: الأدمن يحذف البلاغ', t.dml(uc, au, 'delete from public.comment_reports'), '1');

  -- ---------- مؤشرات الإدارة ----------
  perform t.chk('stats: الزائر مرفوض', t.sel(null, anon, 'select public.admin_stats()'), 'ERR:42501');
  perform t.chk('stats: المستخدم العادي مرفوض', t.sel(ua, au, 'select public.admin_stats()'), 'ERR:42501');
  perform t.chk('stats: الأدمن يحصل على أرقام', t.sel(uc, au, 'select (public.admin_stats() ? ''news_pending'')::text'), 'true');
  perform t.chk('stats: العدّ حقيقي', t.sel(uc, au, 'select (public.admin_stats()->>''users_total'')'), t.sel(null, su, 'select count(*) from public.users'));
  perform t.chk('stats: الإعلانات الجارية', t.sel(uc, au, 'select (public.admin_stats()->>''ads_active'')'), t.sel(null, su, 'select count(*) from public.ads_public'));

  -- ---------- الحذف المتسلسل ----------
  delete from auth.users where id = ua;
  perform t.chk('cascade: users', t.sel(null, su, format('select count(*) from public.users where id=%L', ua)), '0');
  perform t.chk('cascade: votes', t.sel(null, su, format('select count(*) from public.news_votes where user_id=%L', ua)), '0');
  perform t.chk('cascade: reactions', t.sel(null, su, format('select count(*) from public.news_reactions where user_id=%L', ua)), '0');
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

  -- ---------- محرك الجلب ----------
  perform t.chk('fetch: كلمات ابتدائية موجودة', t.sel(null, su, 'select count(*) from public.keywords where kind=''region'''), '3');
  perform t.chk('fetch: صخرة تحتاج سياقاً', t.sel(null, su, 'select needs_context::text from public.keywords where word=''صخرة'''), 'true');
  perform t.chk('fetch: منطقة momani موجودة', t.sel(null, su, 'select count(*) from public.regions where id=''momani'''), '1');
  perform t.chk('fetch: service_role يكتب fetch_runs', t.dml(null, sr, 'insert into public.fetch_runs (sources_total) values (2)'), '1');
  perform t.chk('fetch: الأدمن يقرأ fetch_runs', t.sel(uc, au, 'select count(*) from public.fetch_runs'), '1');
  perform t.chk('fetch: المستخدم العادي لا يرى fetch_runs', t.sel(ua, au, 'select count(*) from public.fetch_runs'), '0');
  perform t.chk('fetch: الزائر محظور من fetch_runs', t.sel(null, anon, 'select count(*) from public.fetch_runs'), 'ERR:42501');
  perform t.chk('fetch: المستخدم لا يكتب fetch_runs', t.dml(ua, au, 'insert into public.fetch_runs (sources_total) values (1)'), 'ERR:42501');
  perform t.chk('fetch_seen: الزائر محظور', t.sel(null, anon, 'select count(*) from public.fetch_seen'), 'ERR:42501');
  perform t.chk('fetch_seen: المستخدم محظور', t.sel(ua, au, 'select count(*) from public.fetch_seen'), 'ERR:42501');
  perform t.chk('fetch_seen: الأدمن محظور أيضاً (service_role فقط)', t.sel(uc, au, 'select count(*) from public.fetch_seen'), 'ERR:42501');
  perform t.chk('fetch_seen: service_role يكتب', t.dml(null, sr, $q$insert into public.fetch_seen (url) values ('https://x.example/a/1')$q$), '1');
  perform t.chk('fetch_seen: رابط http مرفوض', t.dml(null, sr, $q$insert into public.fetch_seen (url) values ('http://x.example/a/2')$q$), 'ERR:23514');
  perform t.chk('fetch_seen: service_role يقرأ', t.sel(null, sr, 'select count(*) from public.fetch_seen'), '1');
  perform t.chk('candidates: service_role يكتب', t.dml(null, sr, $q$insert into public.source_candidates (domain, via, relevant) values ('site.example', 'search', true)$q$), '1');
  perform t.chk('candidates: نطاق بحروف كبيرة مرفوض', t.dml(null, sr, $q$insert into public.source_candidates (domain) values ('BAD.example')$q$), 'ERR:23514');
  perform t.chk('candidates: حالة غير معروفة مرفوضة', t.dml(null, sr, $q$update public.source_candidates set status='x' where domain='site.example'$q$), 'ERR:23514');
  perform t.chk('candidates: الأدمن يقرأ', t.sel(uc, au, 'select count(*) from public.source_candidates'), '1');
  perform t.chk('candidates: المستخدم لا يرى', t.sel(ua, au, 'select count(*) from public.source_candidates'), '0');
  perform t.chk('candidates: الزائر محظور', t.sel(null, anon, 'select count(*) from public.source_candidates'), 'ERR:42501');
  perform t.chk('candidates: الأدمن لا يكتب (service_role فقط)', t.dml(uc, au, $q$insert into public.source_candidates (domain) values ('x.example')$q$), 'ERR:42501');
  perform t.chk('sources: origin الافتراضي يدوي', t.sel(null, sr, $q$select count(*) from public.sources where origin='manual'$q$), (select count(*)::text from public.sources));
  perform t.chk('sources: origin غير صالح مرفوض', t.dml(null, sr, $q$update public.sources set origin='x' where id=(select min(id) from public.sources)$q$), 'ERR:23514');
  perform t.chk('fetch: بصمة تكرار تُقبل', t.dml(null, sr, 'insert into public.news (title,content,region_id,fingerprint) values (''ب1'',''ن'',''abeen'',repeat(''a'',64))'), '1');
  perform t.chk('fetch: نفس البصمة مرفوضة', t.dml(null, sr, 'insert into public.news (title,content,region_id,fingerprint) values (''ب2'',''ن'',''abelin'',repeat(''a'',64))'), 'ERR:23505');
  perform t.chk('fetch: بصمة غير صالحة مرفوضة', t.dml(null, sr, 'insert into public.news (title,content,region_id,fingerprint) values (''ب3'',''ن'',''abeen'',''xyz'')'), 'ERR:23514');
  perform t.chk('fetch: المجلوب pending لا يظهر للعامة', t.sel(null, anon, 'select count(*) from public.news where fingerprint is not null'), '0');
  perform t.chk('link_checks: service_role يكتب', t.dml(null, sr, format($q$insert into public.link_checks (news_id, url, status, reason) values (%s, 'https://x.example/a', 'broken', 'HTTP 404')$q$, n_pend)), '1');
  perform t.chk('link_checks: حالة غير معروفة مرفوضة', t.dml(null, sr, format($q$update public.link_checks set status='fine' where news_id=%s$q$, n_pend)), 'ERR:23514');
  perform t.chk('link_checks: الأدمن يقرأ', t.sel(uc, au, 'select count(*) from public.link_checks'), '1');
  perform t.chk('link_checks: المستخدم لا يرى', t.sel(ua, au, 'select count(*) from public.link_checks'), '0');
  perform t.chk('link_checks: الزائر محظور', t.sel(null, anon, 'select count(*) from public.link_checks'), 'ERR:42501');
  perform t.chk('link_checks: الأدمن لا يكتب', t.dml(uc, au, format($q$update public.link_checks set status='ok' where news_id=%s$q$, n_pend)), 'ERR:42501');
  perform t.chk('fetch: news_refs للأدمن فقط', t.sel(ua, au, 'select count(*) from public.news_refs'), '0');
  perform t.chk('fetch: الزائر لا يقرأ news_refs', t.sel(null, anon, 'select count(*) from public.news_refs'), 'ERR:42501');

  -- ---------- تدقيق الاعتماد ----------
  perform t.chk('approval: خبر معلّق بلا معتمِد', t.sel(null, su, 'select (approved_by is null and approved_at is null)::text from public.news where title=''ب1'''), 'true');
  perform t.chk('approval: الأدمن يعتمد', t.dml(uc, au, 'update public.news set status=''approved'' where title=''ب1'''), '1');
  perform t.chk('approval: سُجّل المعتمِد', t.sel(null, su, format('select (approved_by=%L and approved_at is not null)::text from public.news where title=''ب1''', uc)), 'true');
  perform t.chk('approval: لا تزوير للمعتمِد', t.dml(uc, au, format('update public.news set approved_by=%L where title=''ب1''', ub)), '1');
  perform t.chk('approval: القيمة لم تتغير', t.sel(null, su, format('select (approved_by=%L)::text from public.news where title=''ب1''', uc)), 'true');
  perform t.chk('approval: الإرجاع للأرشيف يمسحها', t.dml(uc, au, 'update public.news set status=''archived'' where title=''ب1'''), '1');
  perform t.chk('approval: مُسحت', t.sel(null, su, 'select (approved_by is null and approved_at is null)::text from public.news where title=''ب1'''), 'true');

  -- ---------- منشورات المستخدمين وأبرز الأخبار ----------
  insert into auth.users (id, email, raw_user_meta_data) values (ue, 'e@test.local', '{"name":"كاتب"}');
  create temp table t_names as select id, display_name from public.users;
  grant select on t_names to anon, authenticated;
  perform t.chk('post: المستخدم يرسل منشوراً معلّقاً',
    t.dml(ue, au, format($q$insert into public.news (title, content, region_id, author_id, source_name)
      select 'منشور أ1', 'نص منشور أ1', 'abeen', %L, display_name from t_names where id = %L$q$, ue, ue)), '1');
  perform t.chk('post: يدخل بحالة pending', t.sel(null, su, $q$select status::text from public.news where title='منشور أ1'$q$), 'pending');
  perform t.chk('post: لا يظهر للزائر', t.sel(null, anon, $q$select count(*) from public.news where title='منشور أ1'$q$), '0');
  perform t.chk('post: لا يظهر لمستخدم آخر', t.sel(ub, au, $q$select count(*) from public.news where title='منشور أ1'$q$), '0');
  perform t.chk('post: لا يظهر لكاتبه مباشرةً (لا ملاحظات إدارية)', t.sel(ue, au, $q$select count(*) from public.news where title='منشور أ1'$q$), '0');
  perform t.chk('post: my_posts يعيد حالته لكاتبه', t.sel(ue, au, $q$select count(*) from public.my_posts() where title='منشور أ1' and status='pending'$q$), '1');
  perform t.chk('post: my_posts لا يعيد منشور غيره', t.sel(ub, au, 'select count(*) from public.my_posts()'), '0');
  perform t.chk('post: my_posts مرفوضة للزائر', t.sel(null, anon, 'select count(*) from public.my_posts()'), 'ERR:42501');
  perform t.chk('post: الزائر لا يرسل',
    t.dml(null, anon, $q$insert into public.news (title, content, region_id) values ('x','y','abeen')$q$), 'ERR:42501');
  perform t.chk('post: لا يُنشر معتمداً مباشرة',
    t.dml(ue, au, format($q$insert into public.news (title, content, region_id, author_id, source_name, status, published_at)
      select 'ن2', 'ن2', 'abeen', %L, display_name, 'approved', now() from t_names where id = %L$q$, ue, ue)), 'ERR:42501');
  perform t.chk('post: لا ينسب المنشور لغيره',
    t.dml(ue, au, format($q$insert into public.news (title, content, region_id, author_id, source_name)
      select 'ن3', 'ن3', 'abeen', %L, display_name from t_names where id = %L$q$, ub, ub)), 'ERR:42501');
  perform t.chk('post: لا ينتحل اسماً',
    t.dml(ue, au, format($q$insert into public.news (title, content, region_id, author_id, source_name) values ('ن4','ن4','abeen',%L,'الغد')$q$, ue)), 'ERR:42501');
  perform t.chk('post: لا يضع is_featured',
    t.dml(ue, au, format($q$insert into public.news (title, content, region_id, author_id, source_name, is_featured)
      select 'ن5', 'ن5', 'abeen', %L, display_name, true from t_names where id = %L$q$, ue, ue)), 'ERR:42501');
  perform t.chk('post: لا يضع رابطاً أصلياً',
    t.dml(ue, au, format($q$insert into public.news (title, content, region_id, author_id, source_name, original_url)
      select 'ن6', 'ن6', 'abeen', %L, display_name, 'https://x.y/z' from t_names where id = %L$q$, ue, ue)), 'ERR:42501');
  perform t.chk('post: صورة خارج مجلده مرفوضة',
    t.dml(ue, au, format($q$insert into public.news (title, content, region_id, author_id, source_name, image_url)
      select 'ن7', 'ن7', 'abeen', %L, display_name, 'https://p.supabase.co/storage/v1/object/public/user-posts/' || %L || '/a.jpg' from t_names where id = %L$q$, ue, ub, ue)), 'ERR:42501');
  perform t.chk('post: صورة من مجلده مقبولة',
    t.dml(ue, au, format($q$insert into public.news (title, content, region_id, author_id, source_name, image_url)
      select 'منشور أ2', 'ن8', 'abeen', %L, display_name, 'https://p.supabase.co/storage/v1/object/public/user-posts/' || %L || '/a.jpg' from t_names where id = %L$q$, ue, ue, ue)), '1');
  perform t.chk('post: نص أطول من 5000 مرفوض',
    t.dml(ue, au, format($q$insert into public.news (title, content, region_id, author_id, source_name)
      select 'ن9', repeat('ك', 5001), 'abeen', %L, display_name from t_names where id = %L$q$, ue, ue)), 'ERR:42501');
  perform t.chk('post: منطقة غير موجودة مرفوضة',
    t.dml(ue, au, format($q$insert into public.news (title, content, region_id, author_id, source_name)
      select 'ن10', 'ن10', 'nowhere', %L, display_name from t_names where id = %L$q$, ue, ue)), 'ERR:23503');
  perform t.chk('post: المحظور لا يرسل',
    t.dml(ud, au, format($q$insert into public.news (title, content, region_id, author_id, source_name)
      select 'ن11', 'ن11', 'abeen', %L, display_name from t_names where id = %L$q$, ud, ud)), 'ERR:42501');
  perform t.chk('post: المستخدم لا يعدّل الأخبار', t.dml(ue, au, $q$update public.news set title='hack' where title='منشور أ1'$q$), '0');
  perform t.chk('post: المستخدم لا يحذف الأخبار', t.dml(ue, au, $q$delete from public.news where title='منشور أ1'$q$), '0');
  for i in 1..3 loop
    perform t.chk('post: ملء حد المعلّقة ' || i,
      t.dml(ue, au, format($q$insert into public.news (title, content, region_id, author_id, source_name)
        select 'حد %s', 'ن', 'abeen', %L, display_name from t_names where id = %L$q$, i, ue, ue)), '1');
  end loop;
  perform t.chk('post: السادس المعلّق مرفوض',
    t.dml(ue, au, format($q$insert into public.news (title, content, region_id, author_id, source_name)
      select 'حد 6', 'ن', 'abeen', %L, display_name from t_names where id = %L$q$, ue, ue)), 'ERR:P0001');
  perform t.chk('post: الأدمن يعتمد منشور المستخدم', t.dml(uc, au, $q$update public.news set status='approved' where title='منشور أ1'$q$), '1');
  perform t.chk('post: يظهر للعامة بعد الاعتماد', t.sel(null, anon, $q$select count(*) from public.news where title='منشور أ1'$q$), '1');
  perform t.chk('post: الكاتب تلقّى إشعار النشر',
    t.sel(ue, au, $q$select count(*) from public.notifications where title='تم نشر منشورك' and news_id is not null$q$), '1');
  perform t.chk('post: الأدمن يرفض منشوراً', t.dml(uc, au, $q$update public.news set status='rejected', review_note='سبب داخلي' where title='منشور أ2'$q$), '1');
  perform t.chk('post: الكاتب تلقّى إشعار الرفض', t.sel(ue, au, $q$select count(*) from public.notifications where title='لم يُنشر منشورك'$q$), '1');
  perform t.chk('post: إشعار الرفض لا يحوي السبب الداخلي', t.sel(ue, au, $q$select count(*) from public.notifications where body like '%سبب داخلي%'$q$), '0');
  perform t.chk('post: my_posts تعرض الحالات',
    t.sel(ue, au, $q$select string_agg(status::text, ',' order by title) from public.my_posts() where title in ('منشور أ1','منشور أ2')$q$), 'approved,rejected');
  perform t.chk('post: أرشفة المعتمد', t.dml(uc, au, $q$update public.news set status='archived' where title='منشور أ1'$q$), '1');
  perform t.chk('post: لا إشعار ثانٍ بعد الأرشفة', t.sel(ue, au, $q$select count(*) from public.notifications where title='تم نشر منشورك'$q$), '1');
  update public.news set status = 'archived' where author_id is not null and status = 'pending';
  insert into public.news (title, content, region_id, author_id, source_name, status)
    select 'أرشيف ' || g, 'ن', 'abeen', ue, 'x', 'archived' from generate_series(1, 4) g;
  perform t.chk('post: العاشر اليوم يُقبل',
    t.dml(ue, au, format($q$insert into public.news (title, content, region_id, author_id, source_name)
      select 'يوم 1', 'ن', 'abeen', %L, display_name from t_names where id = %L$q$, ue, ue)), '1');
  perform t.chk('post: الحادي عشر يُرفض (حد اليوم)',
    t.dml(ue, au, format($q$insert into public.news (title, content, region_id, author_id, source_name)
      select 'يوم 2', 'ن', 'abeen', %L, display_name from t_names where id = %L$q$, ue, ue)), 'ERR:P0001');
  perform t.chk('post: الأدمن غير مقيّد بالحد',
    t.dml(uc, au, $q$insert into public.news (title, content, region_id, status, published_at) values ('أدمن', 'ن', 'abeen', 'approved', now())$q$), '1');
  perform t.chk('featured: المستخدم لا يميّز', t.dml(ue, au, $q$update public.news set is_featured=true where title='خبر معتمد'$q$), '0');
  perform t.chk('featured: الأدمن يميّز', t.dml(uc, au, $q$update public.news set is_featured=true where title='خبر معتمد'$q$), '1');
  perform t.chk('featured: يقرأه الزائر', t.sel(null, anon, 'select count(*) from public.news where is_featured'), '1');
  perform t.chk('user-posts: رفع إلى مجلده', t.dml(ue, au, format('insert into storage.objects (bucket_id,name) values (''user-posts'', %L)', ue || '/p1.jpg')), '1');
  perform t.chk('user-posts: رفع إلى مجلد غيره مرفوض', t.dml(ue, au, format('insert into storage.objects (bucket_id,name) values (''user-posts'', %L)', ub || '/p1.jpg')), 'ERR:42501');
  perform t.chk('user-posts: الزائر مرفوض', t.dml(null, anon, $q$insert into storage.objects (bucket_id,name) values ('user-posts', 'x/p.jpg')$q$), 'ERR:42501');
  perform t.chk('user-posts: المحظور مرفوض', t.dml(ud, au, format('insert into storage.objects (bucket_id,name) values (''user-posts'', %L)', ud || '/p1.jpg')), 'ERR:42501');
  perform t.chk('user-posts: القراءة عامة', t.sel(null, anon, $q$select count(*) from storage.objects where bucket_id='user-posts'$q$), '1');
  perform t.chk('user-posts: الحاوية عامة وJPEG وحد 3MB',
    t.sel(null, su, $q$select (public and file_size_limit=3145728 and allowed_mime_types=array['image/jpeg'])::text from storage.buckets where id='user-posts'$q$), 'true');
  for i in 2..30 loop
    perform t.dml(ue, au, format('insert into storage.objects (bucket_id,name) values (''user-posts'', %L)', ue || '/p' || i || '.jpg'));
  end loop;
  perform t.chk('user-posts: بلغ 30 ملفاً فيُرفض التالي', t.dml(ue, au, format('insert into storage.objects (bucket_id,name) values (''user-posts'', %L)', ue || '/p31.jpg')), 'ERR:42501');
  perform t.chk('user-posts: لا يحذف ملف غيره', t.dml(ub, au, format('delete from storage.objects where bucket_id=''user-posts'' and name=%L', ue || '/p1.jpg')), '0');
  perform t.chk('user-posts: يحذف ملفه', t.dml(ue, au, format('delete from storage.objects where bucket_id=''user-posts'' and name=%L', ue || '/p1.jpg')), '1');
  perform t.chk('user-posts: الأدمن يحذف أي ملف', t.dml(uc, au, format('delete from storage.objects where bucket_id=''user-posts'' and name=%L', ue || '/p2.jpg')), '1');

  -- ---------- عدّادات الترتيب والمشاهدات ----------
  select id into n_ok from public.news where title = 'خبر معتمد';
  perform t.chk('counts: المشاهدات تبدأ من صفر', t.sel(null, anon, format('select view_count from public.news where id=%L', n_ok)), '0');
  perform t.chk('counts: المستخدم لا يدخل عدّادات مزوّرة',
    t.dml(ue, au, format($q$insert into public.news (title, content, region_id, author_id, source_name, view_count)
      select 'مزوّر', 'ن', 'abeen', %L, display_name, 999999 from t_names where id = %L$q$, ue, ue)), 'ERR:P0001');
  perform t.chk('counts: المستخدم لا يعدّل العدّادات', t.dml(ub, au, format('update public.news set view_count=5 where id=%L', n_ok)), '0');
  perform t.chk('views: الزائر يزيد المشاهدة', t.sel(null, anon, format('select public.track_news_view(%L)::text', n_ok)), '');
  perform t.chk('views: العدّاد 1', t.sel(null, anon, format('select view_count from public.news where id=%L', n_ok)), '1');
  perform t.sel(ub, au, format('select public.track_news_view(%L)::text', n_ok));
  perform t.sel(ub, au, format('select public.track_news_view(%L)::text', n_ok));
  perform t.chk('views: المسجَّل مرة واحدة فقط', t.sel(null, anon, format('select view_count from public.news where id=%L', n_ok)), '2');
  perform t.sel(null, anon, format('select public.track_news_view(%L)::text', n_pend));
  perform t.chk('views: خبر معلّق لا يُحتسب', t.sel(null, su, format('select view_count from public.news where id=%L', n_pend)), '0');
  perform t.chk('views: news_viewers مغلق على المستخدم', t.sel(ub, au, 'select count(*) from public.news_viewers'), 'ERR:42501');
  perform t.dml(ub, au, format('insert into public.news_votes (news_id, vote) values (%L, ''credible'')', n_ok));
  perform t.chk('likes: credible_count = الأصوات الفعلية', t.sel(null, anon, format('select credible_count from public.news where id=%L', n_ok)), (select count(*) from public.news_votes where news_id=n_ok and vote='credible')::text);
  perform t.dml(ub, au, format('update public.news_votes set vote=''not_credible'' where news_id=%L', n_ok));
  perform t.chk('likes: تغيير الصوت يحدّث العدّاد', t.sel(null, anon, format('select credible_count from public.news where id=%L', n_ok)), (select count(*) from public.news_votes where news_id=n_ok and vote='credible')::text);
  update public.comments set created_at = now() - interval '1 hour' where user_id = ub;
  perform t.chk('comments: إدراج تعليق', t.dml(ub, au, format('insert into public.comments (news_id, body) values (%L, ''تعليق عادي'')', n_ok)), '1');
  perform t.chk('comments: comment_count = عدد الظاهر فعلاً', t.sel(null, anon, format('select comment_count from public.news where id=%L', n_ok)), (select count(*) from public.comments where news_id=n_ok and status='visible')::text);
  update public.comments set status = 'hidden' where body = 'تعليق عادي';
  perform t.chk('comments: الإخفاء ينقص العدّاد', t.sel(null, anon, format('select comment_count from public.news where id=%L', n_ok)), (select count(*) from public.comments where news_id=n_ok and status='visible')::text);
  -- ---------- كلمات محظورة ----------
  perform t.chk('blocked: المستخدم لا يقرأ', t.sel(ub, au, 'select count(*) from public.blocked_words'), '0');
  perform t.chk('blocked: الزائر مرفوض', t.sel(null, anon, 'select count(*) from public.blocked_words'), 'ERR:42501');
  perform t.chk('blocked: المستخدم لا يضيف', t.dml(ub, au, $q$insert into public.blocked_words (word) values ('سبّة')$q$), 'ERR:42501');
  perform t.chk('blocked: الأدمن يضيف', t.dml(uc, au, $q$insert into public.blocked_words (word) values ('سبّة')$q$), '1');
  perform t.chk('blocked: كلمة مكررة (حروف كبيرة/صغيرة) مرفوضة', t.dml(uc, au, $q$insert into public.blocked_words (word) values ('سبّة ')$q$), 'ERR:23505');
  perform t.chk('blocked: كلمة بحرف واحد مرفوضة', t.dml(uc, au, $q$insert into public.blocked_words (word) values ('س')$q$), 'ERR:23514');
  perform t.chk('blocked: الأدمن يقرأ', t.sel(uc, au, 'select count(*) from public.blocked_words'), '1');
  insert into public.news (title, content, region_id) values ('خبر فيه سبّة', 'نص', 'abeen');
  insert into public.news (title, content, region_id) values ('خبر نظيف', 'نص نظيف', 'abeen');
  perform t.chk('flags: الخبر المعلّق المخالف يُعلَّم', t.sel(uc, au, $q$select count(*) from public.news_flags f join public.news n on n.id=f.news_id where n.title='خبر فيه سبّة'$q$), '1');
  perform t.chk('flags: النظيف لا يُعلَّم', t.sel(uc, au, $q$select count(*) from public.news_flags f join public.news n on n.id=f.news_id where n.title='خبر نظيف'$q$), '0');
  perform t.chk('flags: المستخدم لا يراها', t.sel(ub, au, 'select count(*) from public.news_flags'), '0');
  perform t.chk('flags: الزائر مرفوض', t.sel(null, anon, 'select count(*) from public.news_flags'), 'ERR:42501');
  update public.comments set created_at = now() - interval '1 hour' where user_id = ub;
  perform t.chk('comments: تعليق بكلمة محظورة يُقبل لكن يُخفى', t.dml(ub, au, format('insert into public.comments (news_id, body) values (%L, ''هذه سبّة'')', n_ok)), '1');
  perform t.chk('comments: المخفي لا يظهر للعامة', t.sel(null, anon, $q$select count(*) from public.comments_public where body='هذه سبّة'$q$), '0');
  perform t.chk('comments: المخفي لا يُحتسب', t.sel(null, anon, format('select comment_count from public.news where id=%L', n_ok)), (select count(*) from public.comments where news_id=n_ok and status='visible')::text);
  perform t.chk('comments: الصف المخفي حالته hidden', t.sel(null, su, $q$select status::text from public.comments where body='هذه سبّة'$q$), 'hidden');
  -- ---------- إحصاءات الأدمن ----------
  perform t.chk('insights: المستخدم مرفوض', t.sel(ub, au, 'select public.admin_insights()::text'), 'ERR:42501');
  perform t.chk('insights: الزائر مرفوض', t.sel(null, anon, 'select public.admin_insights()::text'), 'ERR:42501');
  perform t.chk('insights: الأدمن: أعلى الأخبار مشاهدةً', t.sel(uc, au, $q$select (public.admin_insights()->'top_news'->0->>'title')$q$), 'خبر معتمد');
  perform t.chk('insights: عدّاد المعلّق المُعلَّم', t.sel(uc, au, $q$select (public.admin_insights()->>'flagged_pending')$q$), '1');
  perform t.chk('insights: كلمات محظورة', t.sel(uc, au, $q$select (public.admin_insights()->>'blocked_words')$q$), '1');
  perform t.chk('insights: منشورات المستخدمين مجموعة', t.sel(uc, au, $q$select (public.admin_insights()->'posts'->>'total')::int > 0$q$), 'true');

  delete from auth.users where id = ue;
  perform t.chk('post: حذف الحساب يحذف منشورات الكاتب (cascade)', t.sel(null, su, format('select count(*) from public.news where author_id=%L', ue)), '0');

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
