# الإشعارات الفورية (Firebase Cloud Messaging)

الآلية: عند إدراج صف في جدول `notifications` بدون `user_id` (إشعار عام) يستدعي Database Webhook دالة Edge Function اسمها `send-push`، وهي تبثّه إلى موضوع FCM اسمه `news`. التطبيق مشترك في الموضوع إن كان مفتاح الإشعارات مفعّلاً. الاستطلاع الدوري (WorkManager) يبقى احتياطاً، ولا تتكرر الإشعارات لأن المعرّف نفسه يستبدل الإشعار.

## ما تم في التطبيق
- `app/google-services.json` (لتطبيق `com.momaninews.app` فقط، مشروع dreams-3e774). هذا الملف غير سري بطبيعته (معرّفات عامة).
- إضافة `firebase-messaging` وإضافة Gradle `com.google.gms.google-services`.
- `MomaniMessagingService` + `PushTopics.sync` (يُستدعى عند فتح التطبيق وعند تغيير مفتاح الإشعارات).
- Start.io: المعرّف 209209297 في AndroidManifest.

## خطوات يدوية (مرة واحدة)
1. Firebase Console → Project settings → Service accounts → Generate new private key. يُنزَّل ملف JSON. **لا تضعه في المستودع ولا ترسله في المحادثة.**
2. ثبّت Supabase CLI ثم من مجلد المشروع:
   ```
   supabase login
   supabase link --project-ref jpnifyumqmkbgxcqnyqb
   supabase secrets set PUSH_WEBHOOK_SECRET=<نص عشوائي طويل من اختيارك>
   supabase secrets set FCM_SERVICE_ACCOUNT="$(cat service-account.json)"
   supabase functions deploy send-push --no-verify-jwt
   ```
   (`--no-verify-jwt` لأن الاستدعاء يأتي من Webhook ويُحمى بالسر `x-webhook-secret`.)
3. Supabase Dashboard → Database → Webhooks → Create: الجدول `notifications`، الحدث `Insert`، النوع Supabase Edge Functions، الدالة `send-push`، وأضف ترويسة HTTP: `x-webhook-secret` بنفس قيمة السر أعلاه.
4. جرّب: أضف إشعاراً من لوحة الإدارة (بدون مستخدم محدد) وراقب Logs للدالة.

## ما اختُبر وما لم يُختبر
- اختُبر محلياً: منطق الدالة (6 اختبارات node: توقيع JWT RS256 يُتحقق منه بالمفتاح العام، رفض الطلبات بلا سر، تخطي إشعارات المستخدمين، معالجة الأخطاء) ومنطق تحليل الرسالة في التطبيق (FcmLogicTest يعمل في GitHub Actions).
- **لم يُختبر**: الإرسال الفعلي إلى خوادم Google وتسلّم الهاتف للإشعار، لأن البيئة هنا لا تصل إلى الشبكة الخارجية ولا تملك مفتاح حساب الخدمة. يلزم التجربة على هاتف حقيقي بعد الخطوات أعلاه.
