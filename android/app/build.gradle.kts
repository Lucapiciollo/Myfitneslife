import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.kapt")
}

android {
    namespace = "com.myfitai.app"
    compileSdk = 36

    sourceSets {
        getByName("test").java.srcDir("$projectDir/src/testFixturesShared/java")
        getByName("androidTest").assets.srcDir("$projectDir/schemas")
        getByName("androidTest").java.srcDir("$projectDir/src/testFixturesShared/java")
    }

    defaultConfig {
        applicationId = "com.myfitai.app"
        minSdk = 26
        targetSdk = 36
        versionCode = 1
        versionName = "0.1.0"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    buildFeatures {
        viewBinding = true
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_17)
    }
}

kapt {
    correctErrorTypes = true
    arguments {
        arg("room.schemaLocation", "$projectDir/schemas")
        arg("room.incremental", "true")
    }
}

configurations.configureEach {
    resolutionStrategy.force(
        "org.jetbrains.kotlin:kotlin-stdlib:2.3.21",
        "org.jetbrains.kotlin:kotlin-stdlib-jdk7:2.3.21",
        "org.jetbrains.kotlin:kotlin-stdlib-jdk8:2.3.21",
        "org.jetbrains.kotlinx:kotlinx-serialization-bom:1.8.1",
        "org.jetbrains.kotlinx:kotlinx-serialization-core:1.8.1",
        "org.jetbrains.kotlinx:kotlinx-serialization-core-jvm:1.8.1",
        "org.jetbrains.kotlinx:kotlinx-serialization-json:1.8.1",
        "org.jetbrains.kotlinx:kotlinx-serialization-json-jvm:1.8.1",
    )
}

if (file("google-services.json").exists()) {
    apply(plugin = "com.google.gms.google-services")
}

dependencies {
    val roomVersion = "2.8.5"

    implementation("androidx.core:core-ktx:1.16.0")
    implementation("androidx.appcompat:appcompat:1.7.1")
    implementation("androidx.fragment:fragment-ktx:1.8.6")
    implementation("com.google.android.material:material:1.13.0")
    implementation("androidx.constraintlayout:constraintlayout:2.2.1")
    implementation("com.github.AppDevNext.AndroidChart:chartLib:5.3")

    implementation("androidx.lifecycle:lifecycle-viewmodel-ktx:2.9.2")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.9.2")
    implementation("androidx.work:work-runtime-ktx:2.10.1")
    implementation("androidx.room:room-runtime:$roomVersion")
    implementation("androidx.room:room-ktx:$roomVersion")
    kapt("androidx.room:room-compiler:$roomVersion")

    testImplementation("junit:junit:4.13.2")
    testImplementation("org.json:json:20240303")
    androidTestImplementation("androidx.test:core:1.6.1")
    androidTestImplementation("androidx.test.ext:junit:1.2.1")
    androidTestImplementation("androidx.test:runner:1.6.2")
    androidTestImplementation("androidx.test:rules:1.6.1")
    androidTestImplementation("androidx.test.uiautomator:uiautomator:2.3.0")
    androidTestImplementation("androidx.room:room-testing:$roomVersion")
}
