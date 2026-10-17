-- أخبار تجريبية معتمدة للتأكد من اتصال التطبيق بالقاعدة. اختياري.
-- ملاحظة: هذه نصوص تجريبية وليست أخباراً حقيقية. احذفها قبل الإطلاق بـ:
--   delete from public.news where source_name = 'تجريبي';
insert into public.news (title, excerpt, content, source_name, region_id, status, published_at) values
 ('خبر تجريبي من عبين', 'مقتطف تجريبي للتأكد من الاتصال.', E'هذا نص تجريبي.\n\nيُحذف قبل الإطلاق.', 'تجريبي', 'abeen',  'approved', now()),
 ('خبر تجريبي من عبلين', 'مقتطف تجريبي ثانٍ.',             E'نص تجريبي ثانٍ.\n\nيُحذف قبل الإطلاق.',   'تجريبي', 'abelin', 'approved', now() - interval '1 hour'),
 ('خبر تجريبي من صخرة', null,                               E'نص تجريبي ثالث بلا مقتطف.',              'تجريبي', 'sakhra', 'approved', now() - interval '2 hours'),
 ('خبر تجريبي معلّق (يجب ألا يظهر)', null,                  'يجب ألا يراه التطبيق لأنه غير معتمد.',    'تجريبي', 'abeen',  'pending',  null);
