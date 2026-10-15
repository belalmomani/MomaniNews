# قواعد R8/ProGuard الخاصة بالتطبيق (النسخة release تفعّل التصغير).

# Start.io (مطلوبة حسب وثائقهم لمن يفعّل ProGuard/R8)
-keep class com.startapp.** {
      *;
}

-keep class com.truenet.** {
      *;
}
