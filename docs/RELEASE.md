# إصدار التطبيق (AAB) ونشره على Google Play

## 1) مفتاح الإصدار (مرة واحدة، ويجب حفظه نسخاً احتياطياً)
على حاسوب فيه Java (`keytool`):
```
keytool -genkeypair -v -keystore release.keystore -alias momaninews -keyalg RSA -keysize 2048 -validity 10000
```
احتفظ بالملف وكلمتي المرور في مكان آمن (مدير كلمات مرور). **ضياعه يمنعك من تحديث التطبيق** على Google Play. لا تضعه في المستودع ولا ترسله لي.

حوّله إلى base64 (على Linux/Mac: `base64 -w0 release.keystore`) وضع في **Settings → Secrets and variables → Actions → Secrets**:
`RELEASE_KEYSTORE_BASE64` و`RELEASE_KEYSTORE_PASSWORD` و`RELEASE_KEY_ALIAS` و`RELEASE_KEY_PASSWORD`.

## 2) بصمة Google للإصدار
بعد توقيع التطبيق بمفتاح الإصدار تتغير بصمة SHA-1. أضف عميل OAuth جديداً من نوع Android في Google Cloud (الحزمة `com.momaninews.app` وبصمة مفتاح الإصدار)، وأضف معرّفه إلى **Client IDs** في Supabase. وإن فعّلتَ «Play App Signing» (الافتراضي) فبصمة التوقيع الفعلية تُؤخذ من Play Console → App integrity، فأضفها كذلك.

## 3) بناء الحزمة
Actions → **Build release AAB** → Run workflow. الناتج `MomaniNews-release-aab`. رقم الإصدار (`versionCode`) يزيد تلقائياً مع كل تشغيل.

## 4) قبل الرفع إلى Play
- حساب مطوّر Google Play: رسم تسجيل لمرة واحدة (مدفوع، ويلزمك موافقتك).
- رابط سياسة الخصوصية: `admin/privacy.html` بعد نشر اللوحة (مثلاً `https://…/privacy.html`)، بعد أن تضع بريد التواصل فيه.
- نموذج «سلامة البيانات» في Play Console: يجب أن يطابق السياسة (حساب Google، صور الهوية، إعلانات Start.io).
- شاشة موافقة Google OAuth: انشرها (Publish) وإلا اقتصر الدخول على Test users.
- **لم يُختبر** نسخة release بعد (R8 يقلّص الكود وقد يكشف مشاكل لا تظهر في debug). شغّل الـ APK/AAB على جهاز قبل النشر.

## رابط سياسة الخصوصية في التطبيق

بعد نشر لوحة الأدمن (GitHub Pages) تكون السياسة على `https://USER.github.io/REPO/privacy.html`. ضع هذا الرابط في GitHub → Settings → Secrets and variables → Actions → **Variables** باسم `PRIVACY_URL` فيظهر زر «سياسة الخصوصية» في الإعدادات. استخدم الرابط نفسه في صفحة Play Console.
