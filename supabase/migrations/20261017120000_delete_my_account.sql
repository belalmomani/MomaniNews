-- حذف الحساب ذاتياً (Google Play يشترطه لأي تطبيق فيه حسابات).
-- يحذف صف auth.users فتُحذف بياناته بالتسلسل (votes, comments, verification, notifications).
-- الإعلانات تبقى بلا مالك (user_id = null). لا يُسمح للأدمن الأخير بحذف نفسه حتى لا تبقى اللوحة بلا مشرف.
create function public.delete_my_account()
returns void
language plpgsql security definer set search_path = public, pg_temp as $$
declare
  me uuid := auth.uid();
begin
  if me is null then
    raise exception 'not_authenticated' using errcode = '42501';
  end if;
  if exists (select 1 from public.users where id = me and role = 'admin')
     and not exists (select 1 from public.users where role = 'admin' and id <> me) then
    raise exception 'last_admin' using errcode = 'P0001';
  end if;
  delete from auth.users where id = me;
end;
$$;

revoke execute on function public.delete_my_account() from public, anon, authenticated;
grant execute on function public.delete_my_account() to authenticated;
