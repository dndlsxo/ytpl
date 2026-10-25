// AGP 9.x에는 Kotlin 컴파일이 내장되어 있어 kotlin-android 플러그인은 적용하지 않습니다.
// 아래 kotlin 선언은 빌드 클래스패스의 Kotlin 버전을 Compose 컴파일러와 맞추기 위한 것입니다.
plugins {
    id("com.android.application") version "9.4.0" apply false
    id("org.jetbrains.kotlin.android") version "2.4.10" apply false
    id("org.jetbrains.kotlin.plugin.compose") version "2.4.10" apply false
}
