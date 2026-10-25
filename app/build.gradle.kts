plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.plugin.compose")
}

android {
    namespace = "com.ytpl.app"
    compileSdk = 37

    defaultConfig {
        applicationId = "com.ytpl.app"
        minSdk = 26
        targetSdk = 36
        versionCode = 1
        versionName = "1.0.0"
    }

    // 서명: GitHub Secrets(YTPL_KEYSTORE_PASSWORD 등)가 있으면 그 값을, 없으면 저장소에 포함된 키를 사용.
    // 같은 키로 서명해야 새 버전이 기존 앱 위에 덮어 설치됩니다.
    signingConfigs {
        create("release") {
            storeFile = file(System.getenv("YTPL_KEYSTORE_PATH") ?: "ytpl-release.jks")
            storePassword = System.getenv("YTPL_KEYSTORE_PASSWORD") ?: "ytpl-local-key"
            keyAlias = System.getenv("YTPL_KEY_ALIAS") ?: "ytpl"
            keyPassword = System.getenv("YTPL_KEY_PASSWORD") ?: "ytpl-local-key"
        }
    }

    buildTypes {
        release {
            // 내장 엔진(yt-dlp 라이브러리)이 리플렉션을 쓰므로 코드 축소는 끔
            isMinifyEnabled = false
            signingConfig = signingConfigs.getByName("release")
        }
    }

    // 휴대폰 CPU별 APK + 모든 기기용 범용 APK를 함께 생성
    splits {
        abi {
            isEnable = true
            reset()
            include("arm64-v8a", "armeabi-v7a", "x86_64", "x86")
            isUniversalApk = true
        }
    }

    // python/ffmpeg/quickjs 실행 파일을 .so 형태로 내장 → 설치 시 압축 해제돼야 실행 가능
    packaging {
        jniLibs {
            useLegacyPackaging = true
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures {
        compose = true
    }

    // 개인용 앱이라 린트 경고로 릴리스 빌드가 멈추지 않게
    lint {
        checkReleaseBuilds = false
        abortOnError = false
    }
}

dependencies {
    // 유튜브 추출 엔진: yt-dlp + Python + QuickJS(서명 해독) / FFmpeg(변환)
    val youtubedlAndroid = "0.18.1"
    implementation("io.github.junkfood02.youtubedl-android:library:$youtubedlAndroid")
    implementation("io.github.junkfood02.youtubedl-android:ffmpeg:$youtubedlAndroid")

    val composeBom = platform("androidx.compose:compose-bom:2026.09.00")
    implementation(composeBom)
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.foundation:foundation")
    implementation("androidx.compose.material3:material3")

    implementation("androidx.core:core-ktx:1.16.0")
    implementation("androidx.activity:activity-compose:1.10.1")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.10.2")
}
