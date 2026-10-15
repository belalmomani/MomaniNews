plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
}

// رابط API يُمرَّر بـ -PapiBaseUrl=https://.../api/v1/ أو في gradle.properties المحلي.
// الرابط عام وليس سراً. إن تُرك فارغاً يعمل التطبيق ببيانات تجريبية فقط. HTTPS إلزامي.
val rawApiBaseUrl = (project.findProperty("apiBaseUrl") as String?)?.trim().orEmpty()
val apiBaseUrl = if (Regex("""https://[^"\s\\]+""").matches(rawApiBaseUrl)) {
    rawApiBaseUrl.trimEnd('/') + "/"
} else {
    ""
}
if (rawApiBaseUrl.isNotEmpty() && apiBaseUrl.isEmpty()) {
    throw GradleException("apiBaseUrl must be a valid https:// URL")
}

android {
    namespace = "com.momaninews.app"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.momaninews.app"
        minSdk = 26
        targetSdk = 35
        versionCode = 1
        versionName = "0.1.0"

        buildConfigField("String", "API_BASE_URL", "\"$apiBaseUrl\"")
    }

    buildTypes {
        release {
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
    implementation(libs.startapp.inapp.sdk)
    debugImplementation(libs.androidx.compose.ui.tooling)

    testImplementation(libs.junit)
    testImplementation(libs.org.json)
}
