plugins { id("com.android.application") }

val masterBuildNumber = providers.gradleProperty("masterBuildNumber").map { it.toInt() }.orElse(1)

android {
    namespace = "dev.aarchive"
    compileSdk = 35
    ndkVersion = "28.0.13004108"
    defaultConfig {
        applicationId = "dev.aarchive"
        minSdk = 26
        targetSdk = 35
        versionCode = masterBuildNumber.get()
        versionName = "1.0"
        ndk { abiFilters += listOf("armeabi-v7a", "arm64-v8a", "x86", "x86_64") }
        externalNativeBuild { cmake { arguments += "-DANDROID_STL=c++_static" } }
    }
    signingConfigs {
        create("master") {
            storeFile = rootProject.file("signing/master-development.p12")
            storeType = "PKCS12"
            storePassword = "android"
            keyAlias = "aarchive-master-development"
            keyPassword = "android"
        }
    }
    buildTypes {
        create("master") {
            initWith(getByName("release"))
            signingConfig = signingConfigs.getByName("master")
            versionNameSuffix = "-master.${masterBuildNumber.get()}"
            isDebuggable = false
        }
    }
    externalNativeBuild { cmake { path = file("src/main/cpp/CMakeLists.txt"); version = "3.22.1" } }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}
