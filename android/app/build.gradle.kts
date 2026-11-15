plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.raspi.piblue"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.raspi.piblue"
        minSdk = 24
        targetSdk = 34
        versionCode = 8
        versionName = "1.3.3"
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }

    packaging {
        resources.excludes += "/META-INF/{AL2.0,LGPL2.1}"
    }
}

// Deliberately minimal: framework views only. No Compose, no appcompat, no
// Material Components - the UI is LinearLayout / TextView / ListView, so the
// APK builds from a cold Gradle cache without touching Google Maven.
dependencies {
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.6.4")
}

// Dependency graph hygiene. Each rule below exists because a transitive artifact
// either failed to resolve or crashed the app, and together they keep the graph
// small enough to reason about:
//
// * kotlin-stdlib 1.9.x declares kotlin-stdlib-jdk7/jdk8 1.8.0 as dependencies,
//   but since 1.8.0 those artifacts are empty shims folded into kotlin-stdlib.
//   Dropping them is the standard fix and changes nothing at runtime.
// * androidx.core 1.13.1 drags in collection 1.0.0 through versionedparcelable;
//   pin it forward rather than resolve an old copy nobody tests against.
configurations.configureEach {
    exclude(group = "org.jetbrains.kotlin", module = "kotlin-stdlib-jdk7")
    exclude(group = "org.jetbrains.kotlin", module = "kotlin-stdlib-jdk8")
    // androidx.concurrent:concurrent-futures declares guava's listenablefuture:1.0, which is
    // nothing but the ListenableFuture interface. Excluding the artifact and compiling the
    // interface from a source file keeps the graph Guava-free
    // (src/main/java/com/google/common/util/concurrent/ListenableFuture.java).
    //
    // Do NOT go back to force("com.google.guava:listenablefuture:9999.0-empty-..."): that jar is
    // empty and is only correct when full Guava is on the classpath. Without it the APK ships a
    // dangling reference, and androidx.profileinstaller crashes the process 5-6s after launch.
    exclude(group = "com.google.guava", module = "listenablefuture")
    resolutionStrategy {
        force("androidx.collection:collection:1.2.0")
        force("androidx.annotation:annotation:1.6.0")
    }
}

