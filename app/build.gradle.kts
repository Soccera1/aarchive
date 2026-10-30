plugins { id("com.android.application") }

dependencies {
    implementation("com.google.android.material:material:1.14.0")
}

val masterBuildNumber = providers.gradleProperty("masterBuildNumber").map { it.toInt() }.orElse(1)
val updateChannel = providers.gradleProperty("updateChannel").orElse("none").get()
val updateRepository = providers.gradleProperty("updateRepository").orElse("Soccera1/aarchive").get()
val appVersion = providers.gradleProperty("appVersion").orElse("1.0").get()
val updaterTests = providers.gradleProperty("updaterTests").map { it.toBooleanStrict() }.orElse(false)
require(updateChannel in listOf("none", "github-master", "github-release")) { "Invalid updateChannel" }
require(updateRepository.matches(Regex("[A-Za-z0-9_.-]+/[A-Za-z0-9_.-]+"))) { "Invalid updateRepository" }
require(appVersion.matches(Regex("[0-9]+(\\.[0-9]+){1,3}"))) { "Invalid appVersion" }

android {
    namespace = "dev.aarchive"
    compileSdk = 35
    ndkVersion = "28.0.13004108"
    defaultConfig {
        applicationId = "dev.aarchive"
        minSdk = 26
        targetSdk = 35
        versionCode = masterBuildNumber.get()
        versionName = appVersion
        buildConfigField("String", "UPDATE_CHANNEL", "\"none\"")
        buildConfigField("String", "UPDATE_REPOSITORY", "\"$updateRepository\"")
        manifestPlaceholders["updateChannel"] = "none"
        manifestPlaceholders["updateRepository"] = updateRepository
        testInstrumentationRunner = "dev.aarchive.UpdateInstrumentation"
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
        getByName("release") {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            if (updaterTests.get()) proguardFiles("proguard-updater-tests.pro")
            externalNativeBuild { cmake { arguments += "-DAARCHIVE_OPTIMIZED=ON" } }
        }
        create("master") {
            initWith(getByName("release"))
            signingConfig = signingConfigs.getByName("master")
            versionNameSuffix = "-master.${masterBuildNumber.get()}"
            isDebuggable = false
            val channel = if (updateChannel == "github-master") updateChannel else "none"
            buildConfigField("String", "UPDATE_CHANNEL", "\"$channel\"")
            manifestPlaceholders["updateChannel"] = channel
        }
        create("versioned") {
            initWith(getByName("release"))
            signingConfig = signingConfigs.getByName("master")
            val channel = if (updateChannel == "github-release") updateChannel else "none"
            buildConfigField("String", "UPDATE_CHANNEL", "\"$channel\"")
            manifestPlaceholders["updateChannel"] = channel
        }
    }
    buildFeatures { buildConfig = true }
    testBuildType = "master"
    sourceSets.getByName("androidTest").assets.srcDir(layout.buildDirectory.dir("update-test-assets"))
    externalNativeBuild { cmake { path = file("src/main/cpp/CMakeLists.txt"); version = "3.22.1" } }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}
