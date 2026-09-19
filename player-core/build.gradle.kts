plugins {
    alias(libs.plugins.android.library)
    // Kotlin is provided by AGP 9's built-in Kotlin support, exactly as in :core. The library
    // plugin must NOT be applied with a version from here — it is declared in the root build file.
    alias(libs.plugins.compose.compiler)
    `maven-publish`
}

// Module identity. The publication below reuses these, but they must be set on the project itself:
// a consuming app that includes this repo as a composite build substitutes the published artifact
// for this project by matching group:name, and it can only do that if the project declares them.
group = "tv.own.owntv"
version = rootProject.extra["coreVersion"] as String

android {
    namespace = "tv.own.owntv.playercore"
    compileSdk {
        version = release(37)
    }

    defaultConfig {
        minSdk = 26

        // GStreamer AudioMix is built as a native backend. The workflow supplies
        // gstreamerRoot pointing at the official Android SDK; local builds without
        // that property are intentionally allowed to skip this backend later.
        externalNativeBuild {
            cmake {
                arguments("-DGSTREAMER_ROOT_ANDROID=" + (
                    providers.gradleProperty("gstreamerRoot").orNull
                        ?: System.getenv("GSTREAMER_ROOT_ANDROID")
                        ?: ""
                ))
            }
        }
        consumerProguardFiles("consumer-rules.pro")
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    externalNativeBuild {
        cmake {
            path = file("src/main/cpp/CMakeLists.txt")
            version = "3.22.1"
        }
    }

    buildFeatures {
        // Compose *runtime* only, same rule as :core — the engine holds Compose state that the HUD
        // observes, but it renders nothing. No ui, no foundation, no androidx.tv.
        compose = true
    }

    testOptions {
        // Mirrors :app and :core — code under test touches android.util.Log / SystemClock and must
        // get defaults rather than "not mocked" crashes.
        unitTests.isReturnDefaultValues = true
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    // Same as :core — release only, sources included.
    publishing {
        singleVariant("release") {
            withSourcesJar()
        }
    }

    lint {
        abortOnError = true
        warningsAsErrors = false
        checkDependencies = false
        // Media3's player API surface is almost entirely @UnstableApi; this engine is built on it,
        // so the check fires across most of the module and carries no signal. Opting in
        // file-by-file would only move the same acknowledgement into a dozen annotations.
        disable += "UnsafeOptInUsageError"
        // See :core — developer-local file, never committed, absent in CI.
        disable += "PropertyEscape"
    }
}

// Same version as :core, deliberately. Two artifacts that must be used together should not have
// numbers that can disagree — that only creates combinations nobody ever built.
publishing {
    publications {
        register<MavenPublication>("release") {
            groupId = "tv.own.owntv"
            artifactId = "player-core"
            version = project.version as String
            afterEvaluate { from(components["release"]) }
        }
    }
    repositories {
        maven {
            name = "GitHubPackages"
            url = uri("https://maven.pkg.github.com/ahXN00/OwnTV_Core")
            credentials {
                username = providers.gradleProperty("gpr.user")
                    .orElse(providers.environmentVariable("GITHUB_ACTOR")).orNull
                password = providers.gradleProperty("gpr.token")
                    .orElse(providers.environmentVariable("GITHUB_TOKEN")).orNull
            }
        }
    }
}

dependencies {
    // The engine reads settings, playback preferences and DB entities, and reports into core's
    // hooks. The arrow points this way only: :core never sees :player-core.
    implementation(project(":core"))

    implementation(libs.androidx.core.ktx)
    implementation(libs.kotlinx.coroutines.android)

    // Playback — libmpv (FFmpeg) plus the Media3/ExoPlayer path used for VOD, image subtitles and
    // the Live/hero preview panes. Same set :app carried before the split.
    //
    // `api`, not `implementation`: OwnTVPlayer's supertype is MPVLib.EventObserver, so a consumer
    // that merely names the player type needs libmpv on its compile classpath. Hiding it here made
    // the module unusable until the app redeclared the same dependency by hand — which the TV app
    // happens to do for historical reasons, so nobody noticed until the mobile app was built. This
    // also pins both apps to ONE libmpv version; two hosts each declaring their own could package
    // two builds of the same native library.
    api(libs.libmpv)
    implementation(libs.androidx.media3.exoplayer)
    implementation(libs.androidx.media3.exoplayer.hls)
    implementation(libs.androidx.media3.exoplayer.dash)
    implementation(libs.androidx.media3.ui)
    implementation(libs.androidx.media3.datasource.okhttp)

    implementation(libs.okhttp)

    implementation(platform(libs.koin.bom))
    implementation(libs.koin.android)

    // Compose RUNTIME ONLY, via the same BOM :app uses. Deliberately not ui/foundation/material,
    // not androidx.tv.*, not navigation, not coil.
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.runtime)

    testImplementation(libs.junit)
}
