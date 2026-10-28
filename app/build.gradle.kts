import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.compose.compiler)
}

android {
    namespace = "dev.smartdns"

    // Private DNS only exists from API 28, so 28 is the honest floor: promising behaviour on 26/27
    // would mean writing a key those releases never read.
    compileSdk = 37
    compileSdkMinor = 2

    defaultConfig {
        applicationId = "dev.smartdns"
        minSdk = 28
        targetSdk = 36
        versionCode = 1
        versionName = "1.0.0"
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures {
        compose = true
    }

    packaging {
        resources.excludes += setOf("/META-INF/{AL2.0,LGPL2.1}", "META-INF/DEPENDENCIES")
    }

    lint {
        // Release gate: any code-level finding fails the build. The four disabled checks report
        // deliberate version policy rather than defects.
        abortOnError = true
        checkReleaseBuilds = true
        warningsAsErrors = true
        disable += setOf(
            "AndroidGradlePluginVersion",
            "NewerVersionAvailable",
            "GradleDependency",
            "OldTargetApi",
        )
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_17)
    }
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.kotlinx.coroutines.android)

    implementation(platform(libs.compose.bom))
    implementation(libs.compose.ui)
    implementation(libs.compose.material3)

    testImplementation(libs.junit)
}
