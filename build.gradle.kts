import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    kotlin("jvm") version "2.3.0"
    id("org.jetbrains.compose") version "1.10.0"
    id("org.jetbrains.kotlin.plugin.compose") version "2.3.0"
}

group = "ai.rever.boss.plugin.dynamic"

// Single source of truth for the plugin version. processResources syncs this
// into plugin.json at build time - never hand-edit the manifest version.
version = "0.1.0"

java {
    toolchain {
        languageVersion.set(JavaLanguageVersion.of(17))
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_17)
    }
}

repositories {
    google()
    mavenCentral()
    maven("https://maven.pkg.jetbrains.space/public/p/compose/dev")
}

// ---------------------------------------------------------------------------
// boss-plugin-api resolution
//
// The API is compileOnly: the BOSS host classloader provides it at runtime, so
// it must NOT be bundled into the plugin jar.
//
// Upstream RISA plugins point compileOnly at a sibling ../boss-plugin-api
// checkout (boss-plugins docs/creating-a-plugin.md section 6). That assumes the
// whole RISA workspace is cloned side by side, which an external contributor
// does not have, so this repo downloads the published release jar into libs/
// instead. Same artifact, same compileOnly semantics, one code path for both
// local builds and CI.
// ---------------------------------------------------------------------------
val bossPluginApiVersion = "1.0.90"
val bossPluginApiJar = layout.projectDirectory.file(
    "libs/boss-plugin-api-$bossPluginApiVersion.jar"
).asFile

val downloadBossPluginApi = tasks.register("downloadBossPluginApi") {
    description = "Downloads the boss-plugin-api jar this plugin compiles against."
    group = "build setup"
    outputs.file(bossPluginApiJar)
    onlyIf { !bossPluginApiJar.exists() }
    doLast {
        bossPluginApiJar.parentFile.mkdirs()
        val url = "https://github.com/risa-labs-inc/boss-plugin-api/releases/download/" +
            "v$bossPluginApiVersion/boss-plugin-api-$bossPluginApiVersion.jar"
        logger.lifecycle("Downloading boss-plugin-api $bossPluginApiVersion")
        uri(url).toURL().openStream().use { input ->
            bossPluginApiJar.outputStream().use { output -> input.copyTo(output) }
        }
    }
}

// builtBy so any compile/test task that needs the jar triggers the download.
val bossApi: FileCollection = files(bossPluginApiJar).builtBy(downloadBossPluginApi)

dependencies {
    // Host-provided at runtime - never bundled.
    compileOnly(bossApi)
    // Tests run outside the host, so the API has to be on the test classpath.
    testImplementation(bossApi)

    implementation(compose.desktop.currentOs)
    implementation(compose.runtime)
    implementation(compose.ui)
    implementation(compose.foundation)
    implementation(compose.material)

    implementation("br.com.devsrsouza.compose.icons:feather:1.1.1")

    implementation("com.arkivanov.decompose:decompose:3.3.0")
    implementation("com.arkivanov.essenty:lifecycle:2.5.0")

    // Versions match what the BOSS host ships (gradle/libs.versions.toml).
    // kotlinx.coroutines and kotlinx.serialization are parent-first shared
    // packages (PluginClassLoader.defaultSharedPackages), so the host's copies
    // win at runtime regardless of what a plugin declares - compiling against
    // the same versions keeps that honest.
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.11.0")
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.11.0")

    testImplementation(kotlin("test"))
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.11.0")
}

tasks.test {
    useJUnitPlatform()
}

// The default :jar task also runs under `build`. Classify it so its archive can
// never collide with buildPluginJar's, and so the host's system-plugin
// downloader skips it (*-thin.jar).
tasks.jar {
    archiveClassifier.set("thin")
}

// The loadable plugin artifact: compiled classes + manifest resources only.
tasks.register<Jar>("buildPluginJar") {
    archiveFileName.set("boss-plugin-bottest-$version.jar")
    duplicatesStrategy = DuplicatesStrategy.EXCLUDE

    manifest {
        attributes(
            "Implementation-Title" to "BOSS Bot Test Plugin",
            "Implementation-Version" to version,
            "Main-Class" to "ai.rever.boss.plugin.dynamic.bottest.BotTestDynamicPlugin"
        )
    }

    from(sourceSets.main.get().output)
    from("src/main/resources")
}

// Guarded version sync (boss-plugins PLUGIN_DEVELOPMENT.md section 11).
// Without inputs.property, a version-only bump leaves this task UP-TO-DATE and
// ships a stale plugin.json whose version disagrees with the jar name.
tasks.named<ProcessResources>("processResources") {
    inputs.property("pluginVersion", version)
    filesMatching("**/plugin.json") {
        filter { it.replace(Regex("\"version\":\\s*\"[^\"]*\""), "\"version\": \"$version\"") }
    }
}

tasks.build {
    dependsOn("buildPluginJar")
}
