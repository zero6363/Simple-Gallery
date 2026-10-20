import org.jetbrains.kotlin.gradle.tasks.KotlinCompile
import org.jetbrains.kotlin.konan.properties.Properties
import java.io.FileInputStream

val isProprietary = gradle.startParameter.taskNames.any { task -> task.contains("Proprietary") }

plugins {
    alias(libs.plugins.android)
    alias(libs.plugins.kotlinAndroid)
    alias(libs.plugins.ksp)
    alias(libs.plugins.imgly).apply(false)
}

val keystorePropertiesFile: File = rootProject.file("keystore.properties")
val keystoreProperties = Properties()
if (keystorePropertiesFile.exists()) {
    keystoreProperties.load(FileInputStream(keystorePropertiesFile))
}

android {
    compileSdk = project.libs.versions.app.build.compileSDKVersion.get().toInt()
    // pin the build tools shipped in .tool/android-sdk
    buildToolsVersion = "34.0.0"

    defaultConfig {
        applicationId = libs.versions.app.version.appId.get()
        minSdk = project.libs.versions.app.build.minimumSDK.get().toInt()
        targetSdk = project.libs.versions.app.build.targetSDK.get().toInt()
        // append the versionCode so the app's about screen matches the APK filename (gallery-413 etc.)
        versionName = "${project.libs.versions.app.version.versionName.get()} (${project.libs.versions.app.version.versionCode.get()})"
        versionCode = project.libs.versions.app.version.versionCode.get().toInt()
        setProperty("archivesBaseName", "gallery-$versionCode")
    }

    signingConfigs {
        if (keystorePropertiesFile.exists()) {
            register("release") {
                keyAlias = keystoreProperties.getProperty("keyAlias")
                keyPassword = keystoreProperties.getProperty("keyPassword")
                storeFile = file(keystoreProperties.getProperty("storeFile"))
                storePassword = keystoreProperties.getProperty("storePassword")
            }
        }
    }

    buildFeatures {
        viewBinding = true
        buildConfig = true
    }

    buildTypes {
        debug {
            // we cannot change the original package name, else PhotoEditorSDK won't work
            //applicationIdSuffix = ".debug"
            // sign debug builds with the same key as release, so both variants can install over each other
            if (keystorePropertiesFile.exists()) {
                signingConfig = signingConfigs.getByName("release")
            }
        }
        release {
            isMinifyEnabled = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
            if (keystorePropertiesFile.exists()) {
                signingConfig = signingConfigs.getByName("release")
            }
        }
    }

    flavorDimensions.add("licensing")
    productFlavors {
        register("proprietary")
        register("foss")
        register("prepaid")
    }

    sourceSets {
        getByName("main").java.srcDirs("src/main/kotlin")
        if (isProprietary) {
            getByName("main").java.srcDirs("src/proprietary/kotlin")
        }
    }

    compileOptions {
        val currentJavaVersionFromLibs = JavaVersion.valueOf(libs.versions.app.build.javaVersion.get().toString())
        sourceCompatibility = currentJavaVersionFromLibs
        targetCompatibility = currentJavaVersionFromLibs
    }

    tasks.withType<KotlinCompile> {
        kotlinOptions.jvmTarget = project.libs.versions.app.build.kotlinJVMTarget.get()
    }

    // the code package must stay com.simplemobiletools.gallery.pro (R class / BuildConfig / manifest),
    // only the applicationId changes so this build installs alongside the original app
    namespace = "com.simplemobiletools.gallery.pro"

    lint {
        checkReleaseBuilds = false
        abortOnError = false
    }

    packagingOptions {
        resources {
            excludes += "META-INF/library_release.kotlin_module"
        }
    }
}

dependencies {
    implementation(libs.simple.tools.commons)
    implementation(libs.android.image.cropper)
    implementation(libs.exif)
    implementation(libs.android.gif.drawable)
    implementation(libs.androidx.constraintlayout)
    implementation(libs.androidx.media3.exoplayer)
    implementation(libs.sdk.panowidget)
    implementation(libs.sdk.videowidget)
    implementation(libs.sanselan)
    implementation(libs.imagefilters)
    implementation(libs.androidsvg.aar)
    implementation(libs.gestureviews)
    implementation(libs.subsamplingscaleimageview)
    implementation(libs.androidx.swiperefreshlayout)
    implementation(libs.awebp)
    implementation(libs.apng)
    implementation(libs.okio)
    implementation(libs.picasso) {
        exclude(group = "com.squareup.okhttp3", module = "okhttp")
    }
    compileOnly(libs.okhttp)

    ksp(libs.glide.compiler)
    implementation(libs.zjupure.webpdecoder)

    implementation(libs.bundles.room)
    ksp(libs.androidx.room.compiler)
}

// The local Simple-Commons checkout (see settings.gradle.kts) is compiled with Kotlin 1.9.10,
// whose transitive runtime jars cannot be dexed by the D8 shipped with AGP 7.4.0.
// Align them with the Kotlin version this app builds with.
configurations.all {
    resolutionStrategy {
        force(
            "org.jetbrains.kotlin:kotlin-stdlib:1.8.22",
            "org.jetbrains.kotlin:kotlin-stdlib-jdk7:1.8.22",
            "org.jetbrains.kotlin:kotlin-stdlib-jdk8:1.8.22",
            "org.jetbrains.kotlin:kotlin-parcelize-runtime:1.8.22",
            "org.jetbrains.kotlin:kotlin-android-extensions-runtime:1.8.22",
            "org.jetbrains.kotlinx:kotlinx-collections-immutable:0.3.5",
            "org.jetbrains.kotlinx:kotlinx-collections-immutable-jvm:0.3.5",
        )
    }
}

// Apply the PESDKPlugin
if (isProprietary) {
    apply(from = "../gradle/imglysdk.gradle")
}
