plugins {
    alias(libs.plugins.android.application)
}

android {
    namespace = "com.zektopic.slpoststamps"
    compileSdk {
        version = release(36) {
            minorApiLevel = 1
        }
    }

    defaultConfig {
        applicationId = "com.zektopic.slpoststamps"
        minSdk = 24
        targetSdk = 36
        // CI overrides these so each tagged release is installable over the
        // last. Locally they stay at the defaults.
        versionCode = (System.getenv("VERSION_CODE") ?: "1").toInt()
        versionName = System.getenv("VERSION_NAME")?.removePrefix("v") ?: "1.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    // Populated from environment variables in CI (see docs/RELEASING.md).
    // Absent locally, in which case `release` stays unsigned and only debug
    // builds are usable - which is the correct default for a dev machine.
    signingConfigs {
        val keystoreFile = System.getenv("KEYSTORE_FILE")
        if (!keystoreFile.isNullOrBlank()) {
            create("release") {
                storeFile = file(keystoreFile)
                storePassword = System.getenv("KEYSTORE_PASSWORD")
                keyAlias = System.getenv("KEY_ALIAS")
                keyPassword = System.getenv("KEY_PASSWORD")
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
            signingConfig = signingConfigs.findByName("release")
        }
    }
    // One declaration for the whole project. This previously said 11 while
    // gradle-daemon-jvm.properties said 21, the IDE config said 21, CI used 21
    // and the README said 11 - four sources, none of them agreeing, and no
    // toolchain to reconcile them.
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    buildFeatures {
        viewBinding = true
    }
    testOptions {
        unitTests {
            // android.util.Log is a stub in JVM unit tests; without this it
            // throws "not mocked" instead of returning a default.
            isReturnDefaultValues = true
        }
    }
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.appcompat)
    implementation(libs.material)
    implementation(libs.androidx.constraintlayout)
    testImplementation(libs.junit)
    androidTestImplementation(libs.androidx.junit)
    androidTestImplementation(libs.androidx.espresso.core)
    implementation(libs.androidx.swiperefreshlayout)
}