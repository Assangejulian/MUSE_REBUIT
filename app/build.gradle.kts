plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
}

android {
    namespace = "com.muse.app"
    compileSdk = 35
    defaultConfig {
        applicationId = "com.muse.app"
        minSdk = 26
        targetSdk = 35
        versionCode = 6
        versionName = "0.2.0"
        buildConfigField("String", "GITHUB_OWNER", "\"Assangejulian\"")
        buildConfigField("String", "GITHUB_REPO", "\"MUSE_REBUIT\"")
    }
    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro",
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
        aidl = true
    }
    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
        }
    }
}

dependencies {
    implementation(project(":core-design"))
    implementation(project(":core-llm"))
    implementation(project(":core-memory"))
    implementation(project(":core-agent"))
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.lifecycle.runtime)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.navigation.compose)
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.material.icons)
    implementation(libs.okhttp)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.shizuku.api)
    implementation(libs.shizuku.provider)
    debugImplementation(libs.androidx.compose.ui.tooling)
    testImplementation(libs.junit)
}

fun registerDistCopy(buildType: String) {
    val titled = buildType.replaceFirstChar { it.uppercase() }
    val assembleName = "assemble$titled"
    val copyTask = tasks.register<Copy>("dist$titled") {
        group = "distribution"
        description = "Copy $buildType APK into /dist with a versioned name"
        dependsOn(assembleName)
        val version = android.defaultConfig.versionName
        from(layout.buildDirectory.file("outputs/apk/$buildType/app-$buildType.apk"))
        into(rootProject.layout.projectDirectory.dir("dist"))
        rename { "Muse-$version-$buildType.apk" }
        doLast {
            println("dist/Muse-$version-$buildType.apk")
        }
    }
    tasks.named(assembleName).configure { finalizedBy(copyTask) }
}

afterEvaluate {
    registerDistCopy("debug")
    registerDistCopy("release")
}
