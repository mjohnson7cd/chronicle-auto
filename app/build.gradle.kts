import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import java.util.Properties

/** Automotive builds share the applicationId; offset keeps their versionCodes distinct on Play. */
val AUTOMOTIVE_VERSION_CODE_OFFSET = 1_000_000

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.ksp)
    alias(libs.plugins.play.publisher)
    id("kotlin-parcelize")
    id("kotlin-kapt") // Still needed for data binding
    id("com.google.android.gms.oss-licenses-plugin")
}

android {
    namespace = "io.github.mjohnson7cd.chronicleauto"
    compileSdk = 37

    lint {
        abortOnError = false
        baseline = file("lint-baseline.xml")
        checkReleaseBuilds = true
        checkAllWarnings = true
    }

    defaultConfig {
        applicationId = "io.github.mjohnson7cd.chronicleauto"
        minSdk = 30
        targetSdk = 36
        versionCode = 70
        versionName = "0.62.7"

        testInstrumentationRunner = "io.github.mjohnson7cd.chronicleauto.application.ChronicleTestRunner"

        buildConfigField("boolean", "IS_AUTOMOTIVE", "false")
    }

    // Form-factor flavors. Both share the same applicationId (one Play listing), but the
    // automotive build ships on the dedicated "Android Automotive OS only" Play track, so its
    // versionCode must never collide with the mobile one.
    flavorDimensions += "formfactor"
    productFlavors {
        create("mobile") {
            dimension = "formfactor"
        }
        create("automotive") {
            dimension = "formfactor"
            // e.g. mobile 69 -> automotive 1000069
            versionCode = AUTOMOTIVE_VERSION_CODE_OFFSET + (defaultConfig.versionCode ?: 0)
            versionNameSuffix = "-automotive"
            // Google built-in (GM Ultium EVs) is Android 12+; API 30 matches main and the AAOS minimum.
            minSdk = 30
            buildConfigField("boolean", "IS_AUTOMOTIVE", "true")
        }
    }

    signingConfigs {
        create("release") {
            // CI: KEYSTORE_FILE / KEYSTORE_PASSWORD / KEY_ALIAS / KEY_PASSWORD env vars.
            // Local: keystore.properties in the project root (see keystore.properties.example).
            // Relative storeFile paths in keystore.properties resolve against the project root.
            val keystorePropertiesFile = rootProject.file("keystore.properties")

            if (System.getenv("KEYSTORE_FILE") != null) {
                // Relative to app/ (CI workflows decode the keystore into app/)
                storeFile = file(System.getenv("KEYSTORE_FILE"))
                storePassword = System.getenv("KEYSTORE_PASSWORD")
                keyAlias = System.getenv("KEY_ALIAS")
                keyPassword = System.getenv("KEY_PASSWORD")
            } else if (keystorePropertiesFile.exists()) {
                val keystoreProperties = Properties()
                keystorePropertiesFile.inputStream().use { keystoreProperties.load(it) }

                storeFile = rootProject.file(keystoreProperties.getProperty("storeFile"))
                storePassword = keystoreProperties.getProperty("storePassword")
                keyAlias = keystoreProperties.getProperty("keyAlias")
                keyPassword = keystoreProperties.getProperty("keyPassword")
            }
            // If neither exists, release builds are left unsigned (a warning is logged below).
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro",
            )

            // Use release signing config if a keystore is configured and present
            val releaseSigningConfig = signingConfigs.getByName("release")
            val releaseStoreFile = releaseSigningConfig.storeFile
            if (releaseStoreFile != null && releaseStoreFile.exists()) {
                signingConfig = releaseSigningConfig
            } else {
                logger.warn(
                    "Release keystore not configured or missing (${releaseStoreFile ?: "no keystore.properties / KEYSTORE_FILE"}); " +
                        "release build will be unsigned.",
                )
            }
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_21
        targetCompatibility = JavaVersion.VERSION_21
    }
    buildFeatures {
        dataBinding = true
        buildConfig = true
    }

    packaging {
        resources {
            excludes +=
                setOf(
                    "META-INF/LICENSE.md",
                    "META-INF/LICENSE-notice.md",
                )
        }
    }

    sourceSets {
        // Share test utilities between unit tests and instrumented tests
        getByName("test") {
            java.srcDir("src/testShared/java")
        }
        getByName("androidTest") {
            java.srcDir("src/testShared/java")
        }
    }
}

kotlin {
    jvmToolchain(21)

    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_21)
        freeCompilerArgs.addAll(
            "-opt-in=kotlinx.coroutines.ExperimentalCoroutinesApi",
            // Enable Kotlin 2.0+ support for KAPT
            "-Xallow-unstable-dependencies",
        )
    }
}

afterEvaluate {
    tasks.matching { it.name.endsWith("OssLicensesCleanUp") }.configureEach {
        val dependencyTaskName = name.replace("LicensesCleanUp", "DependencyTask")
        tasks.findByName(dependencyTaskName)?.let { dependencyTask ->
            dependsOn(dependencyTask)
        }
    }
}

// Play Publisher configuration
play {
    // Service account from environment variable (CI) or file (local)
    val credentialsFile = file("play-store-credentials.json")
    if (System.getenv("PLAY_STORE_SERVICE_ACCOUNT_JSON") != null) {
        serviceAccountCredentials.set(
            file(System.getenv("PLAY_CREDENTIALS_FILE") ?: "play-credentials.json"),
        )
    } else if (credentialsFile.exists()) {
        serviceAccountCredentials.set(credentialsFile)
    }

    track.set("internal") // Default track
    defaultToAppBundles.set(true) // Use AAB by default

    // For apps not yet published to production, releases must be drafts
    // Change to COMPLETED once the app is live on Play Store
    releaseStatus.set(com.github.triplet.gradle.androidpublisher.ReleaseStatus.DRAFT)
}

// AAOS media apps must ship on the dedicated "Android Automotive OS only" form-factor track,
// which the Play Developer API exposes with an "automotive:" prefix. Note: a --track CLI flag
// overrides this, so publish the automotive variant with e.g. --track=automotive:internal.
android.playConfigs {
    register("automotive") {
        track.set("automotive:internal")
    }
}

// KSP configuration for Room
ksp {
    arg("room.schemaLocation", "$projectDir/schemas")
    arg("room.incremental", "true")
    arg("room.expandProjection", "true")
}

// KAPT still needed for data binding (doesn't fully support KSP yet)
kapt {
    correctErrorTypes = true
}

dependencies {

    implementation(libs.material)
    implementation(libs.glide)
    implementation(libs.timber)
    implementation(libs.fetch)
    implementation(libs.work)
    implementation(libs.result)
    implementation(libs.swiperefresh)
    implementation(libs.seismic)
    implementation(libs.security.crypto)
    implementation(libs.billing.ktx)
    implementation(libs.browserx)
    implementation(libs.oss)
    implementation(libs.appcompat)
    implementation(libs.annotation)
    implementation(libs.coroutines)
    implementation(libs.lifecycle.livedata.ktx)
    implementation(libs.lifecycle.viewmodel.ktx)
    implementation(libs.localbroadcastmanager)
    compileOnly(libs.facebook.infer.annotation)

    implementation(libs.retrofit)
    implementation(libs.retrofit.converter)

    implementation(libs.okhttp3)
    implementation(libs.okhttp3.logging)

    implementation(libs.moshi)
    // Removed moshi-codegen KAPT processor - deprecated for Kotlin 2.x
    // Moshi will use reflection-based adapters instead

    implementation(libs.fresco)
    implementation(libs.fresco.imagepipeline)

    implementation(libs.room.runtime)
    ksp(libs.room.compiler)
    implementation(libs.room.ktx)

    implementation(libs.dagger)
    ksp(libs.dagger.compiler)

    implementation(libs.media3.exoplayer)
    implementation(libs.media3.ui)
    implementation(libs.media3.session)
    implementation(libs.media3.datasource)
    implementation(libs.media3.cast)

    /*
     * Local Tests
     */
    testImplementation(libs.dagger)
    kspTest(libs.dagger.compiler)

    testImplementation(libs.junit)
    testImplementation(libs.mockk)
    testImplementation(libs.mockito.kotlin)
    testImplementation(libs.hamcrest)
    testImplementation(libs.truth)
    testImplementation(libs.coroutines.test)
    testImplementation(libs.androidx.arch.core.testing)
    testImplementation(libs.androidx.test.core)
    testImplementation(libs.robolectric)
    testImplementation(libs.room.testing)

    /*
     * Instrumented Tests
     */
    androidTestImplementation(libs.dagger)
    kspAndroidTest(libs.dagger.compiler)

    androidTestImplementation(libs.junit)
    androidTestImplementation(libs.truth)
    androidTestImplementation(libs.mockk.android)
    androidTestImplementation(libs.coroutines.test)
    androidTestImplementation(libs.espresso.core)
    androidTestImplementation(libs.espresso.contrib)
    androidTestImplementation(libs.androidx.test.runner)
    androidTestImplementation(libs.androidx.test.rules)
    androidTestImplementation(libs.androidx.test.ext.junit)
    androidTestImplementation(libs.androidx.test.ext.junit.ktx)
    androidTestImplementation(libs.room.testing)
    androidTestImplementation(libs.screengrab)
}

// tasks.matching { it.name.contains("DebugAndroidTest") && !it.name.contains("Lint") }.configureEach {
//     enabled = false
// }
