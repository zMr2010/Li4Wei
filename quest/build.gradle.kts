plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
}

val gameSources = rootProject.layout.projectDirectory.dir("app/src")

android {
    namespace = "com.fourthward.game"
    compileSdk = 35
    ndkVersion = libs.versions.androidNdk.get()

    defaultConfig {
        applicationId = "com.fourthward.game.quest"
        minSdk = 32
        targetSdk = 34
        versionCode = 1
        versionName = "0.1.0"

        ndk { abiFilters.add("arm64-v8a") }
        externalNativeBuild {
            cmake { arguments += "-DANDROID_STL=c++_shared" }
        }
    }

    sourceSets {
        named("main") {
            java.srcDirs(
                gameSources.dir("main/java/com/fourthward/game/core"),
                gameSources.dir("main/java/com/fourthward/game/vr"),
            )
            assets.srcDir(gameSources.dir("main/assets"))
            res.srcDir(gameSources.dir("main/res"))
        }
        named("test") {
            java.srcDir(gameSources.dir("test/java"))
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
    kotlinOptions { jvmTarget = "11" }
    buildFeatures { prefab = true }
    androidResources { noCompress += "m4a" }
    externalNativeBuild {
        cmake {
            path = gameSources.file("main/cpp/CMakeLists.txt").asFile
            version = libs.versions.cmake.get()
        }
    }
}

androidComponents {
    onVariants(selector().withBuildType("release")) { variant ->
        variant.applicationId.set("com.fourthward.li4wei")
    }
}

dependencies {
    implementation(libs.openxr.loader)
    implementation(libs.androidx.annotation)
    testImplementation(libs.junit)
}
