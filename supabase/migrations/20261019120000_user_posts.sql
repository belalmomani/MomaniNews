-- =====================================================================
-- منشورات المستخدمين + أبرز الأخبار.
-- أي مستخدم مسجّل غير محظور يستطيع إرسال منشور (خبر) يدخل بحالة pending ولا يظهر لأحد حتى يعتمده مدير.
-- الأعمدة الجديدة في news:
--   author_id   كاتب المنشور (null للأخبار المجلوبة/الإدارية). يُحذف المنشور مع حذف حساب كاتبه.
--   is_featured «أبرز الأخبار» (شريط القصص في الرئيسية)؛ يضبطه المدير فقط.
-- لا سياسة قراءة لصاحب المنشور المعلّق (حتى لا يرى review_note المخصّصة للمشرفين)؛
-- يرى حالة منشوراته عبر الدالة my_posts() التي لا تُرجع الملاحظات.
-- =====================================================================
alter table public.news
  add column author_id   uuid references public.users (id) on delete cascade,
  add column is_featured boolean not null default false;

create index news_featured_idx on public.news (published_at desc) where status = 'approved' and is_featured;
create index news_author_idx   on public.news (author_id, created_at desc) where author_id is not null;

-- إدراج المستخدم: كل الحقول الإدارية/المجلوبة يجب أن تكون فارغة، والاسم المعروض هو اسمه الحقيقي (لا انتحال)
create policy news_user_insert on public.news for insert to authenticated
with check (
      author_id = auth.uid()
  and public.can_interact()
  and status = 'pending' and published_at is null
  and source_id is null and original_url is null and video_url is null and excerpt is null
  and fingerprint is null and matched_terms = '{}' and duplicate_of is null
  and fetch_note is null and review_note is null and source_published_at is null
  and approved_by is null and approved_at is null and is_featured = false
  and char_length(content) <= 5000
  and source_name = (select u.display_name from public.users u where u.id = auth.uid())
  and (image_url is null or image_url like '%/storage/v1/object/public/user-posts/' || auth.uid()::text || '/%')
);

-- حدود الإساءة: 5 منشورات معلّقة كحد أقصى، و10 كل 24 ساعة (لا تنطبق على المدير ولا على الجالب)
create function public.news_user_limits() returns trigger
language plpgsql security definer set search_path = public, pg_temp as $$
begin
  if new.author_id is null or public.is_admin() then
    return new;
  end if;
  if (select count(*) from public.news where author_id = new.author_id and status = 'pending') >= 5 then
    raise exception 'too_many_pending_posts' using errcode = 'P0001';
  end if;
  if (select count(*) from public.news where author_id = new.author_id and created_at > now() - interval '1 day') >= 10 then
    raise exception 'too_many_posts_today' using errcode = 'P0001';
  end if;
  return new;
end;
$$;
create trigger news_user_limits before insert on public.news
  for each row execute function public.news_user_limits();

-- إشعار الكاتب عند اعتماد منشوره أو رفضه (مرة واحدة: من pending فقط)
create function public.notify_post_author() returns trigger
language plpgsql security definer set search_path = public, pg_temp as $$
begin
  if new.author_id is not null and old.status = 'pending' and new.status is distinct from old.status then
    if new.status = 'approved' then
      insert into public.notifications (user_id, title, body, news_id)
      values (new.author_id, 'تم نشر منشورك', left(new.title, 200), new.id);
    elsif new.status = 'rejected' then
      insert into public.notifications (user_id, title, body)
      values (new.author_id, 'لم يُنشر منشورك', left(new.title, 200));
    end if;
  end if;
  return new;
end;
$$;
create trigger news_notify_author after update on public.news
  for each row execute function public.notify_post_author();

-- حالة منشوراتي (بدون أي ملاحظات إدارية)
create function public.my_posts()
returns table (id bigint, title text, status public.news_status, created_at timestamptz, published_at timestamptz)
language sql stable security definer set search_path = public, pg_temp as $$
  select n.id, n.title, n.status, n.created_at, n.published_at
  from public.news n
  where n.author_id = auth.uid()
  order by n.created_at desc
  limit 50;
$$;
revoke execute on function public.my_posts(), public.news_user_limits(), public.notify_post_author() from public, anon, authenticated;
grant  execute on function public.my_posts() to authenticated;

-- حاوية صور المنشورات: عامة للقراءة، JPEG فقط، 3MB للملف، الرفع لمجلد المستخدم نفسه، 30 ملفاً كحد أقصى لكل مستخدم
insert into storage.buckets (id, name, public, file_size_limit, allowed_mime_types)
values ('user-posts', 'user-posts', true, 3145728, array['image/jpeg'])
on conflict (id) do nothing;

create policy "user posts: own upload" on storage.objects for insert to authenticated
with check (
      bucket_id = 'user-posts'
  and (storage.foldername(name))[1] = auth.uid()::text
  and public.can_interact()
  and (select count(*) from storage.objects o
       where o.bucket_id = 'user-posts' and (storage.foldername(o.name))[1] = auth.uid()::text) < 30
);
create policy "user posts: public read" on storage.objects for select to anon, authenticated
using (bucket_id = 'user-posts');
create policy "user posts: own or admin delete" on storage.objects for delete to authenticated
using (bucket_id = 'user-posts' and ((storage.foldername(name))[1] = auth.uid()::text or public.is_admin()));
