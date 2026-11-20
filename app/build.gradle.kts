import java.security.KeyStore

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

// กุญแจเซ็นแอป: CI อ่านจาก GitHub Secrets (ไม่มี = ใช้ debug key สำหรับทดสอบ)
val ksFile = file("release.jks")
// secret ที่ไม่ได้ตั้งจะถูกส่งมาเป็นข้อความว่าง ("") ไม่ใช่ null → ถือว่าไม่ได้ตั้ง
fun env(name: String): String? = System.getenv(name)?.takeIf { it.isNotBlank() }
val hasReleaseKey = ksFile.exists() && env("KEYSTORE_PASSWORD") != null

android {
    namespace = "com.bestspace.vault"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.bestspace.unitconverter"
        minSdk = 26
        targetSdk = 35
        versionCode = (System.getenv("GITHUB_RUN_NUMBER") ?: "1").toInt()
        versionName = "1.0." + (System.getenv("GITHUB_RUN_NUMBER") ?: "0")
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    signingConfigs {
        if (hasReleaseKey) {
            create("release") {
                val storePw = System.getenv("KEYSTORE_PASSWORD")
                storeFile = ksFile
                storePassword = storePw
                // ไม่ได้ตั้ง KEY_ALIAS (หรือว่าง) → ใช้ alias แรกในไฟล์กุญแจเอง
                keyAlias = System.getenv("KEY_ALIAS")?.takeIf { it.isNotBlank() } ?: run {
                    val types = listOf("PKCS12", "JKS")
                    types.firstNotNullOfOrNull { t ->
                        try {
                            KeyStore.getInstance(t).apply { ksFile.inputStream().use { load(it, storePw.toCharArray()) } }
                                .aliases().toList().firstOrNull()
                        } catch (e: Exception) { null }
                    } ?: "bestspace"
                }
                keyPassword = System.getenv("KEY_PASSWORD")?.takeIf { it.isNotBlank() } ?: storePw
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            signingConfig = if (hasReleaseKey) signingConfigs.getByName("release") else signingConfigs.getByName("debug")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
    buildFeatures { compose = true }
    packaging { resources { excludes += "/META-INF/{AL2.0,LGPL2.1}" } }
    lint {
        checkReleaseBuilds = false      // MANAGE_EXTERNAL_STORAGE เป็นนโยบายของ Play Store – แอปนี้ติดตั้งเอง ไม่ผ่าน Play
        abortOnError = false
    }
}

dependencies {
    val composeBom = platform("androidx.compose:compose-bom:2024.12.01")
    implementation(composeBom)
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-graphics")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended")
    implementation("androidx.compose.foundation:foundation")
    implementation("androidx.activity:activity-compose:1.9.3")
    implementation("androidx.fragment:fragment-ktx:1.8.5")       // BiometricPrompt ต้องใช้ FragmentActivity
    implementation("androidx.biometric:biometric:1.1.0")
    implementation("androidx.core:core-ktx:1.15.0")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.8.7")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.8.7")
    implementation("androidx.exifinterface:exifinterface:1.3.7")
    implementation("androidx.documentfile:documentfile:1.0.1")
    implementation("androidx.media3:media3-exoplayer:1.4.1")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.9.0")

    testImplementation("junit:junit:4.13.2")

    // ทดสอบบน emulator (ใช้งานจริง + ตรวจข้อมูลหลงเหลือ) – ไม่อยู่ใน APK ที่ติดตั้งจริง
    androidTestImplementation(composeBom)
    androidTestImplementation("androidx.test:runner:1.6.2")
    androidTestImplementation("androidx.test:rules:1.6.1")
    androidTestImplementation("androidx.test.ext:junit:1.2.1")
    androidTestImplementation("androidx.test.espresso:espresso-core:3.6.1")
    androidTestImplementation("androidx.test.espresso:espresso-intents:3.6.1")
    androidTestImplementation("androidx.test.uiautomator:uiautomator:2.3.0")
    androidTestImplementation("androidx.test:core-ktx:1.6.1")
    androidTestImplementation("androidx.compose.ui:ui-test-junit4")
    debugImplementation("androidx.compose.ui:ui-test-manifest")
}
