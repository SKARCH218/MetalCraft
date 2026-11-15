import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import org.jetbrains.kotlin.gradle.tasks.KotlinCompile

plugins {
    kotlin("jvm") version "2.4.21"
    id("net.fabricmc.fabric-loom") version "1.19-SNAPSHOT"
    id("maven-publish")
}

version = project.property("mod_version") as String
group = project.property("maven_group") as String

base {
    archivesName.set(project.property("archives_base_name") as String)
}

val targetJavaVersion = 25
java {
    toolchain.languageVersion = JavaLanguageVersion.of(targetJavaVersion)
    // Loom will automatically attach sourcesJar to a RemapSourcesJar task and to the "build" task
    // if it is present.
    // If you remove this line, sources will not be generated.
    withSourcesJar()
}

loom {
    splitEnvironmentSourceSets()

    mods {
        register("metalcraft") {
            sourceSet("main")
            sourceSet("client")
        }
    }

    runs {
        named("client") {
            // FFM downcalls are restricted methods; mod classes live in the unnamed module under Knot.
            vmArg("--enable-native-access=ALL-UNNAMED")
            // Ported shader packs live in the project folder (git-ignored), not in the game folder.
            vmArg("-Dmetalcraft.shaderpacks.dir=${layout.projectDirectory.dir("shaderpacks").asFile}")
        }
    }
}

// Automated test harness (CLAUDE.md 6-1). Creates the `gametest` source set and the `runClientGameTest` task.
fabricApi {
    configureTests {
        createSourceSet = true
        modId = "metalcraft-gametest"
        enableGameTests = false
        enableClientGameTests = true
        eula = true
        // Every run starts from an empty game directory so options and worlds never leak between runs.
        clearRunDirectory = true
    }
}

// Harness switches are Gradle properties (-Pname=value) forwarded as JVM system properties.
val harnessProperties = listOf(
    "metalcraft.backend",
    "metalcraft.test.outputDir",
    "metalcraft.test.benchmark",
    "metalcraft.test.scenes",
    "metalcraft.test.width",
    "metalcraft.test.irisPack",
    "metalcraft.test.renderDistance",
    "metalcraft.test.fov",
    "metalcraft.test.height",
    "metalcraft.test.worldCache",
    "metalcraft.debug.timing",
    "metalcraft.shaderpack",
    "metalcraft.upscale",
    "metalcraft.renderScale",
    "metalcraft.debug.jitterSignX",
    "metalcraft.debug.jitterSignY",
    "metalcraft.debug.motionSign",
)

loom {
    runs {
        named("clientGameTest") {
            vmArg("--enable-native-access=ALL-UNNAMED")
            vmArg("-Dmetalcraft.shaderpacks.dir=${layout.projectDirectory.dir("shaderpacks").asFile}")
            // The gametest API pins the game framebuffer to these values regardless of the real window size.
            // -Pmetalcraft.test.width / height override the size, e.g. to measure at a display's full resolution.
            programArgs(
                "--width", providers.gradleProperty("metalcraft.test.width").orNull ?: "1920",
                "--height", providers.gradleProperty("metalcraft.test.height").orNull ?: "1200",
            )
            harnessProperties.forEach { name ->
                providers.gradleProperty(name).orNull?.let { vmArg("-D$name=$it") }
            }
        }
    }
}

// Porting aid (CLAUDE.md section 4, M7): turns an Iris/OptiFine shader pack's GLSL into the Metal Shading Language
// of a MetalCraft pack with the shaderc and SPIRV-Cross that ship with the game. Not part of the mod jar.
//   ./gradlew convertShaderPack -Ppack.source=<pack>/shaders -Ppack.output=shaderpacks/<name> -Ppack.port=src/tools/resources/ports/<name>.json
val tools: SourceSet by sourceSets.creating {
    compileClasspath += sourceSets["client"].compileClasspath
    runtimeClasspath += sourceSets["client"].runtimeClasspath
}

tasks.register<JavaExec>("convertShaderPack") {
    group = "metalcraft"
    description = "Converts a GLSL shader pack into a MetalCraft pack (porting aid)."
    classpath = tools.runtimeClasspath
    mainClass.set("dev.skarch.metalcraft.tools.ShaderPackConverterKt")
    workingDir = layout.projectDirectory.asFile
    args(
        providers.gradleProperty("pack.source").orNull ?: "",
        providers.gradleProperty("pack.output").orNull ?: "",
        providers.gradleProperty("pack.port").orNull ?: "",
    )
}

val nativeDir = layout.projectDirectory.dir("native")

val buildNative by tasks.registering(Exec::class) {
    group = "build"
    description = "Builds libMetalCraftNative.dylib with SwiftPM."
    workingDir = nativeDir.asFile
    commandLine("/usr/bin/swift", "build", "-c", "release", "--arch", "arm64")
    inputs.file(nativeDir.file("Package.swift"))
    inputs.dir(nativeDir.dir("Sources"))
    // SwiftPM keeps `.build/release` as a symlink to wherever the active build system puts its products.
    outputs.file(nativeDir.file(".build/release/libMetalCraftNative.dylib"))
}

tasks.named<Copy>("processClientResources") {
    from(buildNative) {
        into("natives/metalcraft/macos-arm64")
    }
}


repositories {
    // Add repositories to retrieve artifacts from in here.
    // You should only use this when depending on other mods because
    // Loom adds the essential maven repositories to download Minecraft and libraries from automatically.
    // See https://docs.gradle.org/current/userguide/declaring_repositories.html
    // for more information about repositories.
    exclusiveContent {
        forRepository { maven("https://api.modrinth.com/maven") { name = "Modrinth" } }
        filter { includeGroup("maven.modrinth") }
    }
}

dependencies {
    // To change the versions see the gradle.properties file
    minecraft("com.mojang:minecraft:${project.property("minecraft_version")}")
    implementation("net.fabricmc:fabric-loader:${project.property("loader_version")}")
    implementation("net.fabricmc:fabric-language-kotlin:${project.property("kotlin_loader_version")}")

    implementation("net.fabricmc.fabric-api:fabric-api:${project.property("fabric_version")}")

    // Mods MetalCraft has to work with (CLAUDE.md section 1). They are only put on the run classpath, and only
    // on request (-Pmetalcraft.sodium=true or scripts/run-tests.sh --sodium): MetalCraft does not compile against them.
    // Create Fly: the mod MetalCraft is built to run well (CLAUDE.md section 3-7), -Pmetalcraft.create=true.
    if (providers.gradleProperty("metalcraft.create").orNull == "true") {
        runtimeOnly("maven.modrinth:create-fly:${project.property("create_version")}")
    }
    val withIris = providers.gradleProperty("metalcraft.iris").orNull == "true"
    if (providers.gradleProperty("metalcraft.sodium").orNull == "true" || withIris) {
        runtimeOnly("maven.modrinth:sodium:${project.property("sodium_version")}")
    }
    // Iris (with Sodium, which it needs) only ever runs next to MetalCraft to give reference pictures and frame
    // rates of the original shader packs (scripts/run-tests.sh --iris). MetalCraft turns its renderer off then.
    if (withIris) {
        runtimeOnly("maven.modrinth:iris:${project.property("iris_version")}")
    }
}

tasks.processResources {
    inputs.property("version", project.version)
    inputs.property("minecraft_version", project.property("minecraft_version"))
    inputs.property("loader_version", project.property("loader_version"))
    filteringCharset = "UTF-8"

    filesMatching("fabric.mod.json") {
        expand(
            "version" to project.version,
            "minecraft_version" to project.property("minecraft_version") as String,
            "loader_version" to project.property("loader_version") as String,
            "kotlin_loader_version" to project.property("kotlin_loader_version") as String
        )
    }
}

tasks.withType<JavaCompile>().configureEach {
    // ensure that the encoding is set to UTF-8, no matter what the system default is
    // this fixes some edge cases with special characters not displaying correctly
    // see http://yodaconditions.net/blog/fix-for-java-file-encoding-problems-with-gradle.html
    // If Javadoc is generated, this must be specified in that task too.
    options.encoding = "UTF-8"
    options.release.set(targetJavaVersion)
}

tasks.withType<KotlinCompile>().configureEach {
    compilerOptions.jvmTarget.set(JvmTarget.fromTarget(targetJavaVersion.toString()))
}

tasks.jar {
    from("LICENSE") {
        rename { "${it}_${project.base.archivesName.get()}" }
    }
}

// configure the maven publication
publishing {
    publications {
        create<MavenPublication>("mavenJava") {
            artifactId = project.property("archives_base_name") as String
            from(components["java"])
        }
    }

    // See https://docs.gradle.org/current/userguide/publishing_maven.html for information on how to set up publishing.
    repositories {
        // Add repositories to publish to here.
        // Notice: This block does NOT have the same function as the block in the top level.
        // The repositories here will be used for publishing your artifact, not for
        // retrieving dependencies.
    }
}
