# إعداد تسجيل الدخول بـ Google

لا تحتاج بطاقة. الخطوات 1–4 من Google Cloud، والخطوة 5 من Supabase. في النهاية ترسل لي **Web Client ID فقط** (عام، ليس سراً).

## بصمة التوقيع (SHA-1) لنسخ debug

أصبح للتطبيق مفتاح توقيع ثابت (`app/debug.keystore`) حتى لا تتغير البصمة مع كل بناء على GitHub:

- **SHA-1:** `AB:A5:16:0A:84:37:17:7D:DB:96:AF:4B:64:D8:79:E2:68:5E:CF:58`
- اسم الحزمة: `com.momaninews.app`

(للإصدار النهائي على Google Play سيُنشأ مفتاح إصدار منفصل لاحقاً وتُضاف بصمته بنفس الطريقة.)

## الخطوات

1. افتح https://console.cloud.google.com وأنشئ مشروعاً جديداً (مثلاً MomaniNews).
2. **APIs & Services → OAuth consent screen** (أو Google Auth Platform): نوع **External**، اسم التطبيق «أخبار المومني»، بريد الدعم بريدك. النطاقات الافتراضية (email, profile, openid) تكفي. أضف بريدك (وبريد أي مختبِر) في **Test users** طالما التطبيق في وضع Testing.
3. **Credentials → Create credentials → OAuth client ID**، نوع **Android**: اسم الحزمة `com.momaninews.app` وبصمة SHA-1 أعلاه.
4. أنشئ OAuth client آخر نوعه **Web application** (أي اسم، بلا Authorized origins). سيعطيك **Client ID** و**Client secret**.
5. في Supabase: **Authentication → Providers → Google**: فعّله، والصق Web **Client ID** و**Client secret**. في خانة **Authorized Client IDs** ضع: `WEB_CLIENT_ID,ANDROID_CLIENT_ID` (Web أولاً، بفاصلة بلا مسافات). فعّل أيضاً **Skip nonce checks** إن طلب الإصدار ذلك لتدفق Android (سأحدد بدقة عند الدمج).

## ما أرسله لي

- **Web Client ID** (ينتهي بـ `.apps.googleusercontent.com`).
- لا ترسل Client secret.

## تنبيهات

- وضع Testing يسمح فقط بمن أضفتهم كـ Test users. للنشر العام يلزم نشر شاشة الموافقة (Publish app)؛ مع نطاقات email/profile/openid فقط لا يلزم عادةً تحقق مطوّل، لكن لم أجرّب ذلك.
- لم يُختبر شيء من هذا بعد: تسجيل الدخول سيُختبر فعلياً فقط بعد دمج الكود وتشغيل التطبيق على هاتف.

## ما أُضيف في الكود (الدفعة الحالية)

- زر «الدخول بحساب Google» يستخدم Credential Manager، ثم يبادل الرمز مع Supabase (`/auth/v1/token?grant_type=id_token`) مع nonce.
- الجلسة تُحفظ مشفّرة (AES-GCM بمفتاح Android Keystore) وتُجدَّد تلقائياً، ويُنشأ صف المستخدم في القاعدة بمشغّل عند أول دخول.
- Web Client ID مضبوط افتراضياً في `app/build.gradle.kts` (يمكن تجاوزه بـ `-PgoogleWebClientId=...`). **يجب أن يكون من نوع Web**، وأن يكون في Supabase ضمن Authorized Client IDs مع عميل Android.
- الدخول التجريبي يعود تلقائياً إن تُرك المعرّف فارغاً.
- التصويت والتعليقات ما زالت محلية تجريبية؛ ربطها بالقاعدة في الدفعة التالية.
