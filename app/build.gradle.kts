plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
}

// Supabase: الرابط والمفتاح العام (publishable/anon) عامّان بطبيعتهما ومصمَّمان للوجود داخل التطبيق؛
// الحماية الفعلية في سياسات RLS. ممنوع وضع مفتاح service_role هنا إطلاقاً.
// يمكن تجاوزهما بـ -PsupabaseUrl=... -PsupabaseKey=... (تُترك فارغة لتعطيل Supabase).
fun prop(name: String, default: String) =
    ((project.findProperty(name) as String?) ?: default).trim()
val supabaseUrl = prop("supabaseUrl", "https://jpnifyumqmkbgxcqnyqb.supabase.co").trimEnd('/')
val supabaseKey = prop("supabaseKey", "sb_publishable_sVF84cWS-dNvYn72x1risQ_z1_ewOUp")
// Web Client ID لتسجيل الدخول بـ Google (عام، ليس سراً؛ ممنوع وضع Client secret هنا). فارغ = الدخول التجريبي.
val googleWebClientId = prop("googleWebClientId", "1010340161934-tq8qfngl6b8l4lt73rd8fddvblflv66a.apps.googleusercontent.com")
// رابط سياسة الخصوصية (https فقط). فارغ = يُخفى الزر في الإعدادات. مثال: -PprivacyUrl=https://USER.github.io/REPO/privacy.html
val privacyUrl = prop("privacyUrl", "")
if (privacyUrl.isNotEmpty() && !Regex("""https://[^"\s\\]+""").matches(privacyUrl)) {
    throw GradleException("privacyUrl must be a valid https:// URL")
}
if (!Regex("""([0-9]+-[a-z0-9]+\.apps\.googleusercontent\.com)?""").matches(googleWebClientId)) {
    throw GradleException("googleWebClientId is not a valid Google client ID")
}
if (supabaseUrl.isNotEmpty() && !Regex("""https://[A-Za-z0-9.-]+""").matches(supabaseUrl)) {
    throw GradleException("supabaseUrl must be a valid https:// origin")
}
if (supabaseKey.startsWith("eyJ") && supabaseKey.contains("service_role")) {
    throw GradleException("Never embed a service_role key in the app")
}
if (!Regex("""[A-Za-z0-9._-]*""").matches(supabaseKey)) {
    throw GradleException("supabaseKey contains invalid characters")
}

android {
    namespace = "com.momaninews.app"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.momaninews.app"
        minSdk = 26
        targetSdk = 35
        // Google Play يشترط زيادة versionCode مع كل رفع؛ يمرّره workflow الإصدار (-PversionCode=رقم التشغيل).
        versionCode = (project.findProperty("versionCode") as String?)?.toIntOrNull() ?: 1
        versionName = "0.1.0"

        buildConfigField("String", "SUPABASE_URL", "\"$supabaseUrl\"")
        buildConfigField("String", "SUPABASE_KEY", "\"$supabaseKey\"")
        buildConfigField("String", "PRIVACY_URL", "\"$privacyUrl\"")
        buildConfigField("String", "GOOGLE_WEB_CLIENT_ID", "\"$googleWebClientId\"")
    }

    // مفتاح توقيع ثابت لنسخ debug (ليس سراً: مفتاح تطوير عام). بدونه يولّد كل تشغيل على GitHub
    // مفتاحاً جديداً فتتغير البصمة SHA-1 ويتعطل تسجيل الدخول بـ Google. للإصدار النهائي مفتاح منفصل لاحقاً.
    // مفتاح الإصدار من متغيرات بيئة (GitHub Secrets). غير موجود = إصدار غير موقّع (لا يُنشر).
    val releaseKeystorePath: String? = System.getenv("RELEASE_KEYSTORE_PATH")
    signingConfigs {
        if (releaseKeystorePath != null) {
            create("release") {
                storeFile = file(releaseKeystorePath)
                storePassword = System.getenv("RELEASE_KEYSTORE_PASSWORD")
                keyAlias = System.getenv("RELEASE_KEY_ALIAS")
                keyPassword = System.getenv("RELEASE_KEY_PASSWORD")
            }
        }
        getByName("debug") {
            storeFile = file("debug.keystore")
            storePassword = "android"
            keyAlias = "androiddebugkey"
            keyPassword = "android"
        }
    }

    buildTypes {
        release {
            if (releaseKeystorePath != null) signingConfig = signingConfigs.getByName("release")
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
        }
    }
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.activity.compose)
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.navigation.compose)
    implementation(libs.androidx.credentials)
    implementation(libs.androidx.credentials.play)
    implementation(libs.googleid)
    implementation(libs.startapp.inapp.sdk)
    debugImplementation(libs.androidx.compose.ui.tooling)

    testImplementation(libs.junit)
    testImplementation(libs.org.json)
}
