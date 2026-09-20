import java.io.ByteArrayOutputStream
import javax.inject.Inject
import org.gradle.process.ExecOperations

plugins {
    alias(libs.plugins.android.library)
    // Kotlin is provided by AGP 9's built-in Kotlin support, exactly as in the apps.
    alias(libs.plugins.compose.compiler)
    alias(libs.plugins.ksp)
    `maven-publish`
}

// Module identity. The publication below reuses these, but they must be set on the project itself:
// a consuming app that includes this repo as a composite build substitutes the published artifact
// for this project by matching group:name, and it can only do that if the project declares them.
group = "tv.own.owntv"
version = rootProject.extra["coreVersion"] as String

android {
    namespace = "tv.own.owntv.core"
    compileSdk {
        version = release(37)
    }

    defaultConfig {
        minSdk = 26
        consumerProguardFiles("consumer-rules.pro")
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    buildFeatures {
        // Seven core files use Compose *runtime* only (Immutable, remember, LaunchedEffect,
        // CompositionLocalProvider). No ui, no foundation, no androidx.tv — see the dependency
        // block below, and the "core stays UI-framework-neutral" invariant.
        compose = true
        buildConfig = true
    }

    testOptions {
        // Mirrors :app — code under test touches android.util.Log / SystemClock and must get
        // defaults rather than "not mocked" crashes.
        unitTests.isReturnDefaultValues = true
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    // Only the release variant is published. Sources travel with it: an app developer stepping into
    // a sync bug should land in core's real source, not in decompiled bytecode.
    publishing {
        singleVariant("release") {
            withSourcesJar()
        }
    }

    // Mirrors the TV app's lint block, because the resources it was written for now live here.
    lint {
        // CI gates on this, so an error must mean something.
        abortOnError = true
        warningsAsErrors = false
        // A counted sentence must use Android plural resources; keep this invariant fatal so a new
        // extraction cannot reintroduce English-only quantity wording.
        fatal += "PluralsCandidate"
        checkDependencies = false
        // local.properties is developer-local and never committed (its Windows SDK path can't be
        // escaped without breaking the local tooling that writes it). CI has no such file at all.
        disable += "PropertyEscape"
        // en-rGB is an intentional partial regional override of the canonical en-US source; its
        // omitted keys fall back to values/ and must not make every default string a lint error.
        disable += "MissingTranslation"
    }
}

// OwnTVDatabaseMigrationTest opens each shipped schema from assets to replay every migration, so
// `core/schemas/` has to be packaged with the instrumentation APK. Attached through the Variant API
// rather than `sourceSets["androidTest"]`, which throws a ClassCastException in an AGP 9 library.
androidComponents {
    onVariants { variant ->
        variant.androidTest?.sources?.assets?.addStaticSourceDirectory("$projectDir/schemas")
    }
}

// The @Database and its exported schemas (`core/schemas/`) both live here.
ksp {
    arg("room.schemaLocation", "$projectDir/schemas")
}

publishing {
    publications {
        register<MavenPublication>("release") {
            groupId = "tv.own.owntv"
            artifactId = "core"
            version = project.version as String
            afterEvaluate { from(components["release"]) }
        }
    }
    repositories {
        maven {
            name = "GitHubPackages"
            url = uri("https://maven.pkg.github.com/ahXN00/OwnTV_Core")
            // Never in the repo — these live in ~/.gradle/gradle.properties locally, and come from
            // repository secrets in CI.
            credentials {
                username = providers.gradleProperty("gpr.user")
                    .orElse(providers.environmentVariable("GITHUB_ACTOR")).orNull
                password = providers.gradleProperty("gpr.token")
                    .orElse(providers.environmentVariable("GITHUB_TOKEN")).orNull
            }
        }
    }
}

// The same check CI runs, moved onto the developer's own machine, so the failure arrives seconds
// after writing the string instead of minutes after pushing it.
//
// Deliberately NOT offered: any flag that records the literal and turns the build green. A red build
// means the string moves to strings_*.xml or is declared technical — those are the only two exits.
abstract class VerifyI18nLiterals : DefaultTask() {
    @get:InputFiles
    @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val kotlinSources: ConfigurableFileCollection

    /** The checker and its two reviewed manifests: edit any of them and the verdict may change. */
    @get:InputFiles
    @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val toolInputs: ConfigurableFileCollection

    @get:Internal
    abstract val repoRoot: DirectoryProperty

    @get:OutputFile
    abstract val stamp: RegularFileProperty

    @get:Inject
    abstract val execOps: ExecOperations

    private fun interpreter(): String? = listOf("python", "python3").firstOrNull { candidate ->
        runCatching {
            execOps.exec {
                commandLine(candidate, "--version")
                isIgnoreExitValue = true
                standardOutput = ByteArrayOutputStream()
                errorOutput = ByteArrayOutputStream()
            }.exitValue == 0
        }.getOrDefault(false)
    }

    @TaskAction
    fun verify() {
        val python = interpreter()
        if (python == null) {
            // Failing here would block anyone without Python from building at all. Warn loudly
            // instead — CI still enforces it, so the worst case is a late failure, not a missed one.
            logger.warn(
                "\n  WARNING: Python was not found, so the hardcoded-text check did not run." +
                    "\n  Install Python 3 to catch untranslatable text before pushing; CI will still catch it.\n",
            )
            stamp.get().asFile.writeText("skipped: no python interpreter\n")
            return
        }
        val output = ByteArrayOutputStream()
        val result = execOps.exec {
            workingDir = repoRoot.get().asFile
            commandLine(python, "tools/i18n/check_hardcoded_strings.py", "verify", "--bootstrap")
            environment("PYTHONIOENCODING", "utf-8")
            isIgnoreExitValue = true
            standardOutput = output
            errorOutput = output
        }
        if (result.exitValue != 0) {
            logger.error(output.toString(Charsets.UTF_8))
            throw GradleException("Hardcoded text check failed — see the report above.")
        }
        stamp.get().asFile.writeText("ok\n")
    }
}

val verifyI18nLiterals = tasks.register<VerifyI18nLiterals>("verifyI18nLiterals") {
    group = "verification"
    description = "Fails the build on user-visible text left hardcoded in Kotlin."
    // Every module in this repo: the checker scans them all anyway, and declaring each one here is
    // what makes the task re-run when their Kotlin changes rather than staying wrongly UP-TO-DATE.
    // A new module needs a line here AND an entry in the checker's SRC_ROOTS AND its own line in
    // README.md's validator list — three registrations, not one.
    kotlinSources.from(fileTree("src/main/java") { include("**/*.kt") })
    kotlinSources.from(rootProject.fileTree("player-core/src/main/java") { include("**/*.kt") })
    toolInputs.from(
        rootProject.file("tools/i18n/check_hardcoded_strings.py"),
        rootProject.file("tools/i18n/hardcoded_baseline.txt"),
        rootProject.file("tools/i18n/safe_literals.txt"),
    )
    repoRoot.set(rootProject.layout.projectDirectory)
    stamp.set(layout.buildDirectory.file("i18n/literal-inventory.txt"))
}

// preBuild fronts every variant, so debug compile checks and release assembles are both covered.
// Inputs are declared above, so an unchanged source tree makes this UP-TO-DATE and free.
tasks.named("preBuild") { dependsOn(verifyI18nLiterals) }

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.kotlinx.coroutines.android)

    // Database (Room, via KSP) + Paging. No paging-compose — that is UI.
    implementation(libs.androidx.room.runtime)
    implementation(libs.androidx.room.ktx)
    implementation(libs.androidx.room.paging)
    ksp(libs.androidx.room.compiler)
    implementation(libs.androidx.paging.runtime)

    // Preferences + durable background sync
    implementation(libs.androidx.datastore.preferences)
    implementation(libs.androidx.work.runtime)

    // Android TV launcher integration (Watch Next / preview channels). TV-only in practice, but
    // core's LauncherIntegrationRepository is the facade the sync worker calls, so the publisher it
    // delegates to has to live here too. A phone app simply never calls those methods.
    implementation(libs.androidx.tvprovider)

    // Storage Access Framework. A phone bound for Play cannot hold MANAGE_EXTERNAL_STORAGE, so a
    // download folder of the user's own choosing is a SAF tree rather than a path — see MediaTarget.
    implementation(libs.androidx.documentfile)

    // Networking
    implementation(libs.okhttp)

    // Charset detection for non-UTF-8 local subtitles (Windows-125x, ISO-8859, etc.).
    implementation(libs.juniversalchardet)

    // QR generation for the Remote companion link.
    implementation(libs.zxing.core)

    // Dependency injection
    implementation(platform(libs.koin.bom))
    implementation(libs.koin.android)

    // Compose RUNTIME ONLY, via the same BOM :app uses. Deliberately not ui/foundation/material,
    // not androidx.tv.*, not navigation, not media3, not coil.
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.runtime)

    testImplementation(libs.junit)
    // android.jar's org.json is a stub and isReturnDefaultValues silences it; backup/restore is
    // all JSON, so the tests need the real implementation. Same reason as :app.
    testImplementation(libs.org.json)
    androidTestImplementation(libs.androidx.junit)
    // The runner named in `testInstrumentationRunner` above. Not transitive from androidx.test.ext.
    androidTestImplementation(libs.androidx.test.runner)
}
