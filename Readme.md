# أخبار المومني — MomaniNews (Android)

تطبيق Android أصلي (Kotlin + Jetpack Compose) بواجهة عربية RTL. لا يستخدم WebView.

## حالة المشروع

**المرحلة 2: واجهة الأخبار** (فوق تأسيس المرحلة 1). تتضمن شاشة البداية، والصفحة الرئيسية، وبطاقات الأخبار القابلة للنقر، وتفاصيل الخبر، والبحث في العنوان والمقتطف والمحتوى، والتصفية حسب المنطقة، والسحب للتحديث، وحالات التحميل والفراغ والخطأ.
البيانات **تجريبية** محلية (`SampleNewsRepository`). لا يوجد خادم ولا اتصال بالإنترنت ولا تسجيل دخول بعد.
حالة الخطأ مبنية في الواجهة لكنها لا تظهر مع البيانات التجريبية لأن المستودع التجريبي لا يفشل؛ تُختبر فعلياً عند ربط الخادم.

## البناء المجاني عبر GitHub Actions

1. أنشئ مستودعاً جديداً (خاصاً أو عاماً) على GitHub.
2. ارفع محتويات هذا المجلد كما هي (يجب أن يظهر `gradlew` و`.github/` في جذر المستودع).
3. افتح تبويب **Actions** ثم الـ workflow باسم **Build Android APK** (يعمل تلقائياً عند الرفع على `main`، أو شغّله يدوياً بـ **Run workflow**).
4. عند نجاح البناء افتح التشغيل ونزّل الملف من قسم **Artifacts** باسم `MomaniNews-debug-apk`، ثم فك الضغط لتحصل على `app-debug.apk`.
5. انقل الملف إلى الهاتف وثبّته (يلزم السماح بالتثبيت من مصادر غير معروفة).

## ربط التطبيق بالخادم (المرحلة 3)

افتراضياً يعمل التطبيق ببيانات تجريبية. لتفعيل الاتصال بالـ API (HTTPS فقط، حسب `docs/API_CONTRACT.md`):

- في GitHub: Settings > Secrets and variables > Actions > **Variables** > أضف `API_BASE_URL` بقيمة مثل `https://example.com/api/v1/`.
- محلياً: `./gradlew assembleDebug -PapiBaseUrl=https://example.com/api/v1/`.

قيمة غير https تُفشل البناء عمداً. لا تضع أسراراً أو كلمات مرور قاعدة بيانات في التطبيق أو في هذا المتغير.

## إعلانات Start.io

- بانر Start.io يظهر أسفل الصفحة الرئيسية وتفاصيل الخبر والحساب وتسجيل الدخول (لا يظهر على شاشة البداية).
- معرّف التطبيق في `AndroidManifest.xml`، وتخطيط البانر في `res/layout/view_startapp_banner.xml`، والمكوّن `ui/components/BannerAd.kt`.
- إصدار المكتبة مثبّت في `gradle/libs.versions.toml` (`startapp`). إن لم يُحمَّل الإصدار عند البناء فغيّره إلى `5.+` مؤقتاً.
- في نسخ التجربة لا تضغط على إعلانات تطبيقك الحقيقي: مخالفة لسياسة شبكات الإعلانات وقد تعطّل الحساب.
- المكتبة تجمع بيانات الجهاز لأغراض الإعلان؛ يلزم ذكر ذلك في سياسة الخصوصية وفي نموذج Data Safety عند النشر على Google Play.

## البناء المحلي (اختياري)

يتطلب Android Studio أو Android SDK مع JDK 17:

```
./gradlew assembleDebug
```

الناتج: `app/build/outputs/apk/debug/app-debug.apk`

## البنية

```
app/src/main/java/com/momaninews/app/
  MainActivity.kt
  navigation/        Routes, AppNavHost
  data/model/        NewsItem, Region
  data/repository/   NewsRepository, SampleNewsRepository, filterNews
  data/sample/       SampleNews (بيانات تجريبية)
  ui/theme/          Color, Type, Theme (RTL إجباري)
  ui/components/     NewsCard, DemoBanner
  ui/screens/        splash, home (HomeViewModel), detail
```

## ملاحظات

- `minSdk = 26` (Android 8.0) بسبب الأيقونة التكيفية بدون ملفات PNG.
- الخط الحالي هو خط النظام (يدعم العربية). لإضافة Cairo أو Tajawal ضع الملفات في `res/font` وعدّل `ui/theme/Type.kt`.
- نسخة release تحتاج توقيعاً بمفتاح خاص قبل التثبيت أو النشر؛ تُعدّ في مرحلة الإصدار. لا ترفع ملفات المفاتيح إلى GitHub.
- إصدارات المكتبات في `gradle/libs.versions.toml` لم تُختبر بالبناء بعد.
