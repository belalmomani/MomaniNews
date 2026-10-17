-- صور وفيديو للأخبار.
-- 1) عمود video_url (https فقط). عمود image_url موجود أصلاً.
-- 2) حاوية تخزين عامة news-media (القراءة للجميع عبر الرابط العام)، الرفع والحذف للأدمن فقط.
--    الأنواع المسموحة: JPEG للصور و MP4 للفيديو. الحد 50MB (حد الخطة المجانية لكل ملف).
alter table public.news
  add column video_url text check (video_url is null or video_url ~ '^https://');

insert into storage.buckets (id, name, public, file_size_limit, allowed_mime_types)
values ('news-media', 'news-media', true, 52428800, array['image/jpeg', 'video/mp4'])
on conflict (id) do nothing;

create policy "news media: admin upload" on storage.objects
  for insert to authenticated
  with check (bucket_id = 'news-media' and public.is_admin());

create policy "news media: public read" on storage.objects
  for select to anon, authenticated
  using (bucket_id = 'news-media');

create policy "news media: admin delete" on storage.objects
  for delete to authenticated
  using (bucket_id = 'news-media' and public.is_admin());
