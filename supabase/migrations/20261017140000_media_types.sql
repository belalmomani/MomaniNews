-- يوسّع أنواع الملفات المسموحة في حاوية news-media: كل صيغ الصور الشائعة وصيغ الفيديو الشائعة.
-- الحد الأقصى للملف يبقى 50MB: هذا سقف الخطة المجانية في Supabase ولا يمكن تجاوزه دون ترقية.
update storage.buckets
set allowed_mime_types = array[
  'image/jpeg', 'image/png', 'image/webp', 'image/gif',
  'video/mp4', 'video/webm', 'video/3gpp', 'video/quicktime', 'video/x-matroska'
]
where id = 'news-media';
