import java.net.URI
import java.io.File
import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

val signing = Properties().apply {
    val path = System.getenv("DISCUZ_SIGNING_PROPERTIES")
    if (!path.isNullOrBlank()) File(path).reader(Charsets.UTF_8).use { load(it) }
}
val site = Properties().apply {
    val source = rootProject.file(System.getenv("DISCUZ_SITE_PROPERTIES") ?: "site.properties")
    if (source.isFile) source.reader(Charsets.UTF_8).use { load(it) }
}
fun setting(key: String, default: String = "") =
    providers.gradleProperty(key).orNull ?: site.getProperty(key) ?: signing.getProperty(key) ?: default
fun javaString(value: String) = "\"" + value.replace("\\", "\\\\").replace("\"", "\\\"")
    .replace("\n", "\\n").replace("\r", "\\r") + "\""

val appName = setting("appName", "社区")
require(appName.isNotBlank()) { "appName must not be blank" }
val exportWebBranding by tasks.registering {
    val output = layout.buildDirectory.file("public-branding.json")
    inputs.property("appName", appName)
    outputs.file(output)
    doLast {
        output.get().asFile.apply {
            parentFile.mkdirs()
            writeText(groovy.json.JsonOutput.toJson(mapOf("appName" to appName)), Charsets.UTF_8)
        }
    }
}

val siteUrl = setting("siteUrl").trim()
val siteUrls = (listOf(siteUrl) + setting("siteUrls").split(Regex("[,\\s]+"))).filter { it.isNotBlank() }.distinct()
require(siteUrls.size <= 8) { "Configure at most 8 site URLs" }
val packageName = setting("applicationId", "com.discuz.community")
require(packageName.matches(Regex("[a-zA-Z][a-zA-Z0-9_]*(\\.[a-zA-Z][a-zA-Z0-9_]*)+"))) {
    "applicationId must be a valid Android application ID"
}
for (address in siteUrls) {
    val uri = URI(address)
    require(uri.scheme in setOf("http", "https") && !uri.host.isNullOrEmpty()
        && uri.userInfo == null && uri.query == null && uri.fragment == null) {
        "siteUrl/siteUrls must contain HTTP(S) forum URLs without credentials, query or fragment; separate siteUrls with ASCII commas, without quotes or brackets"
    }
    require(uri.port in -1..65535 && uri.port != 0 && !uri.rawPath.orEmpty().contains(Regex("%(2f|5c|25)", RegexOption.IGNORE_CASE))
        && !uri.path.orEmpty().contains('\\') && uri.path.orEmpty().split('/').none { it == "." || it == ".." }
        && uri.path.orEmpty().none { it.code < 32 || it.code == 127 }) {
        "siteUrl/siteUrls contains an invalid port or path"
    }
}
val validateReleaseSite by tasks.registering {
    doLast {
        require(setting("storeFile").isNotEmpty() && rootProject.file(setting("storeFile")).isFile
            && setting("storePassword").isNotEmpty() && setting("keyPassword").isNotEmpty()) {
            "Release requires a signing key and passwords (DISCUZ_SIGNING_PROPERTIES or local site.properties)"
        }
        require(siteUrls.isNotEmpty() && siteUrls.all { URI(it).scheme == "https" }) {
            "Release requires HTTPS for every siteUrl/siteUrls endpoint"
        }
    }
}
tasks.configureEach {
    if (name == "preReleaseBuild") dependsOn(validateReleaseSite)
}

android {
    namespace = "com.discuz.mobile"
    compileSdk = 35
    defaultConfig {
        applicationId = packageName
        minSdk = 26
        targetSdk = 35
        versionCode = setting("versionCode", "17").toInt().also { require(it > 0) }
        versionName = setting("versionName", "1.12.0-rc.8")
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        resValue("string", "app_name", appName)
        buildConfigField("String", "SITE_URL", javaString(siteUrl))
        buildConfigField("String", "SITE_URLS", javaString(siteUrls.joinToString("\n")))
    }
    signingConfigs {
        if (setting("storeFile").isNotEmpty()) {
            create("release") {
                storeFile = rootProject.file(setting("storeFile"))
                storePassword = setting("storePassword")
                keyAlias = setting("keyAlias", "release")
                keyPassword = setting("keyPassword")
            }
        }
    }
    buildTypes {
        debug {
            applicationIdSuffix = ".debug"
            versionNameSuffix = "-debug"
        }
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            signingConfig = signingConfigs.findByName("release")
        }
    }
    buildFeatures { buildConfig = true }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
}

dependencies {
    implementation("androidx.core:core-ktx:1.16.0")
    implementation("androidx.webkit:webkit:1.13.0")
    implementation("com.journeyapps:zxing-android-embedded:4.3.0")
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.json:json:20240303")
    androidTestImplementation("androidx.test:runner:1.6.2")
    androidTestImplementation("androidx.test:core:1.6.1")
    androidTestImplementation("androidx.test.ext:junit:1.2.1")
}
