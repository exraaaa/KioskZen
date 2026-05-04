import com.android.build.gradle.internal.api.ApkVariantOutputImpl
import java.io.FileInputStream
import java.net.HttpURLConnection
import java.net.URL
import java.util.Properties
import java.util.concurrent.TimeUnit

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

enum class ReleaseBumpType {
    AUTO,
    PATCH,
    MINOR,
    MAJOR;

    companion object {
        fun fromRaw(raw: String): ReleaseBumpType {
            return values().firstOrNull { it.name.equals(raw.trim(), ignoreCase = true) } ?: AUTO
        }
    }
}

data class SemVer(
    val major: Int,
    val minor: Int,
    val patch: Int
) {
    fun bump(type: ReleaseBumpType): SemVer {
        return when (type) {
            ReleaseBumpType.AUTO -> copy(patch = patch + 1)
            ReleaseBumpType.PATCH -> copy(patch = patch + 1)
            ReleaseBumpType.MINOR -> copy(minor = minor + 1, patch = 0)
            ReleaseBumpType.MAJOR -> copy(major = major + 1, minor = 0, patch = 0)
        }
    }

    override fun toString(): String = "$major.$minor.$patch"

    companion object {
        private val SEMVER_REGEX = Regex("""^\s*[vV]?(\d+)\.(\d+)\.(\d+)(?:[-+].*)?\s*$""")

        fun parse(raw: String): SemVer? {
            val match = SEMVER_REGEX.matchEntire(raw) ?: return null
            return SemVer(
                major = match.groupValues[1].toInt(),
                minor = match.groupValues[2].toInt(),
                patch = match.groupValues[3].toInt()
            )
        }
    }
}

fun Project.stringProperty(name: String): String? {
    return findProperty(name)
        ?.toString()
        ?.trim()
        ?.takeIf { it.isNotEmpty() }
}

fun fetchGitHubReleaseTags(repoSlug: String, token: String?): Set<String> {
    val connection = (URL("https://api.github.com/repos/${repoSlug.trim()}/releases?per_page=100")
        .openConnection() as HttpURLConnection).apply {
        requestMethod = "GET"
        connectTimeout = 6_000
        readTimeout = 10_000
        instanceFollowRedirects = true
        setRequestProperty("Accept", "application/vnd.github+json")
        setRequestProperty("User-Agent", "KioskZen-Gradle-Versioning")
        if (!token.isNullOrBlank()) {
            setRequestProperty("Authorization", "Bearer $token")
        }
    }

    return runCatching {
        val code = connection.responseCode
        val stream = if (code in 200..299) connection.inputStream else connection.errorStream
        val raw = stream?.bufferedReader()?.use { it.readText() }.orEmpty()
        if (code !in 200..299) {
            error("GitHub API returned HTTP $code")
        }

        // We only need tag_name fields from the releases payload.
        Regex(""""tag_name"\s*:\s*"([^"]+)"""")
            .findAll(raw)
            .map { it.groupValues[1].trim() }
            .filter { it.isNotBlank() }
            .toSet()
    }.getOrElse { error ->
        println("Version auto-bump skipped: ${error.message}")
        emptySet()
    }.also {
        connection.disconnect()
    }
}

fun runGitCommand(project: Project, args: List<String>): String? {
    return runCatching {
        val process = ProcessBuilder(args)
            .directory(project.rootDir)
            .redirectErrorStream(true)
            .start()
        val finished = process.waitFor(4, TimeUnit.SECONDS)
        if (!finished || process.exitValue() != 0) {
            return null
        }
        process.inputStream.bufferedReader().use { it.readText().trim() }
    }.getOrNull()
}

data class GitChangeStats(
    val filesChanged: Int,
    val linesChanged: Int
)

fun readGitChangeStats(project: Project): GitChangeStats {
    val diffNumStat = runGitCommand(project, listOf("git", "diff", "--numstat", "HEAD~1", "HEAD")).orEmpty()
    var files = 0
    var lines = 0
    diffNumStat.lineSequence()
        .map { it.trim() }
        .filter { it.isNotBlank() }
        .forEach { line ->
            val parts = line.split('\t')
            if (parts.size >= 3) {
                files += 1
                val added = parts[0].toIntOrNull() ?: 0
                val deleted = parts[1].toIntOrNull() ?: 0
                lines += added + deleted
            }
        }
    return GitChangeStats(filesChanged = files, linesChanged = lines)
}

fun resolveAutoBumpType(project: Project): ReleaseBumpType {
    val commitMessage = runGitCommand(project, listOf("git", "log", "-1", "--pretty=%B"))
        ?.lowercase()
        .orEmpty()

    val breakingRegex = Regex("""(^|\s)[a-z]+!:""")
    val hasBreakingMarker = "breaking change" in commitMessage || breakingRegex.containsMatchIn(commitMessage)
    if (hasBreakingMarker) {
        return ReleaseBumpType.MAJOR
    }

    val stats = readGitChangeStats(project)
    val looksLikeFeature = commitMessage.lineSequence()
        .any { line ->
            val normalized = line.trim()
            normalized.startsWith("feat", ignoreCase = true) ||
                normalized.startsWith("feature", ignoreCase = true) ||
                normalized.contains(" feature", ignoreCase = true)
        }
    val isLargeUpdate = stats.filesChanged >= 8 || stats.linesChanged >= 250
    return if (looksLikeFeature || isLargeUpdate) ReleaseBumpType.MINOR else ReleaseBumpType.PATCH
}

fun normalizeTag(tag: String): String {
    return tag.trim().removePrefix("v").removePrefix("V")
}

fun resolveReleaseSemVer(
    base: SemVer,
    bumpType: ReleaseBumpType,
    existingTags: Set<String>
): SemVer {
    if (existingTags.isEmpty()) return base

    val normalized = existingTags.map(::normalizeTag).toSet()
    var candidate = base
    while (normalized.contains(candidate.toString())) {
        candidate = candidate.bump(bumpType)
    }
    return candidate
}

fun semVerToVersionCode(version: SemVer): Int {
    // Format MMmmpp where major/minor/patch are decimal groups.
    // Keeps monotonic order for semantic version progression.
    return version.major * 10_000 + version.minor * 100 + version.patch
}

val baseVersionName = project.stringProperty("baseVersionName") ?: "1.2.2"
val baseSemVer = SemVer.parse(baseVersionName)
    ?: error("baseVersionName must be semantic (x.y.z). Got: $baseVersionName")
val releaseRepoSlug = project.stringProperty("releaseRepo") ?: "exraaaa/KioskZen"
val configuredReleaseBumpType = ReleaseBumpType.fromRaw(project.stringProperty("releaseBumpType") ?: "auto")
val autoBumpReleaseVersion =
    project.stringProperty("autoBumpReleaseVersion")?.toBooleanStrictOrNull() ?: true
val githubToken = project.stringProperty("githubToken")
    ?: System.getenv("GITHUB_TOKEN")?.trim()?.takeIf { it.isNotEmpty() }
val requestedTasks = gradle.startParameter.taskNames
val isReleaseBuildRequested = requestedTasks.any { task ->
    task.contains("release", ignoreCase = true) || task.contains("publish", ignoreCase = true)
}
val debugApplicationIdSuffixRaw = project.stringProperty("debugApplicationIdSuffix").orEmpty()
val debugApplicationIdSuffix = debugApplicationIdSuffixRaw
    .trim()
    .takeIf { it.isNotEmpty() }
    ?.let { suffix -> if (suffix.startsWith(".")) suffix else ".$suffix" }
val effectiveReleaseBumpType = when (configuredReleaseBumpType) {
    ReleaseBumpType.AUTO -> resolveAutoBumpType(project)
    else -> configuredReleaseBumpType
}

val resolvedReleaseSemVer = if (autoBumpReleaseVersion && isReleaseBuildRequested) {
    val tags = fetchGitHubReleaseTags(releaseRepoSlug, githubToken)
    resolveReleaseSemVer(baseSemVer, effectiveReleaseBumpType, tags)
} else {
    baseSemVer
}
val resolvedVersionName = resolvedReleaseSemVer.toString()
val resolvedVersionCode = semVerToVersionCode(resolvedReleaseSemVer)

println(
    "Resolved app version -> name=$resolvedVersionName code=$resolvedVersionCode " +
        "(base=$baseVersionName bump=$effectiveReleaseBumpType configured=$configuredReleaseBumpType " +
        "auto=$autoBumpReleaseVersion repo=$releaseRepoSlug)"
)

val signingPropertiesFile = rootProject.file("keystore/signing.properties")
val signingProperties = Properties().apply {
    if (signingPropertiesFile.exists()) {
        FileInputStream(signingPropertiesFile).use { load(it) }
    }
}
val hasReleaseSigning = listOf(
    "storeFile",
    "storePassword",
    "keyAlias",
    "keyPassword"
).all { key -> !signingProperties.getProperty(key).isNullOrBlank() }

if (!hasReleaseSigning) {
    println("Release signing is not configured. Fill keystore/signing.properties to create installable release APKs.")
}

android {
    namespace = "com.zenpanel.kiosk"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.zenpanel.kiosk"
        minSdk = 26
        targetSdk = 36
        versionCode = resolvedVersionCode
        versionName = resolvedVersionName

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    signingConfigs {
        create("release") {
            if (hasReleaseSigning) {
                storeFile = rootProject.file(signingProperties.getProperty("storeFile"))
                storePassword = signingProperties.getProperty("storePassword")
                keyAlias = signingProperties.getProperty("keyAlias")
                keyPassword = signingProperties.getProperty("keyPassword")
                enableV1Signing = true
                enableV2Signing = true
                enableV3Signing = true
                enableV4Signing = true
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            if (hasReleaseSigning) {
                signingConfig = signingConfigs.getByName("release")
            }
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
        debug {
            isMinifyEnabled = false
            if (!debugApplicationIdSuffix.isNullOrBlank()) {
                applicationIdSuffix = debugApplicationIdSuffix
                versionNameSuffix = "-debug"
            }
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
        viewBinding = true
    }

    // Keep debug artifacts as the default names, but produce a release APK name
    // that includes versionName and updates automatically when versionName changes.
    applicationVariants.all {
        val variant = this
        outputs.all {
            if (variant.buildType.name == "release") {
                val output = this as ApkVariantOutputImpl
                val resolvedVersionName = variant.versionName ?: "0.0.0"
                output.outputFileName = "KioskZen-v$resolvedVersionName-release.apk"
            }
        }
    }
}

dependencies {
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("androidx.activity:activity-ktx:1.9.2")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.6")
    implementation("androidx.camera:camera-core:1.4.1")
    implementation("androidx.camera:camera-camera2:1.4.1")
    implementation("androidx.camera:camera-lifecycle:1.4.1")
    implementation("com.google.android.material:material:1.12.0")
    implementation("com.google.mlkit:face-detection:16.1.7")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.9.0")
    implementation("org.nanohttpd:nanohttpd:2.3.1")

    // GeckoView (Mozilla engine).
    implementation("org.mozilla.geckoview:geckoview:147.0.20260212191108")
}
