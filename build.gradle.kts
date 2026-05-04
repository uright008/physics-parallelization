plugins {
    id("net.fabricmc.fabric-loom")
    `maven-publish`
}

version = project.property("mod_version") as String
group = providers.gradleProperty("maven_group").get()

repositories {
    mavenLocal()
}

loom {
    mods {
        register("physics-parallelization") {
            sourceSet(sourceSets.main.get())
        }
    }
}

dependencies {
    minecraft("com.mojang:minecraft:${providers.gradleProperty("minecraft_version").get()}")

    // parallel-core (local: publishToMavenLocal first)
    implementation("io.github.uright008.pc:parallel-core:${providers.gradleProperty("parallel_core_version").get()}")
    implementation("net.fabricmc:fabric-loader:${providers.gradleProperty("loader_version").get()}")
    implementation("net.fabricmc.fabric-api:fabric-api:${providers.gradleProperty("fabric_api_version").get()}")
}

tasks.processResources {
    val version = version
    inputs.property("version", version)

    filesMatching("fabric.mod.json") {
        expand("version" to version)
    }
}

tasks.withType<JavaCompile>().configureEach {
    options.release = 25
}

java {
    withSourcesJar()
    sourceCompatibility = JavaVersion.VERSION_25
    targetCompatibility = JavaVersion.VERSION_25
}

tasks.jar {
    val projectName = project.name
    inputs.property("projectName", projectName)

    from("LICENSE") {
        rename { "${it}_$projectName" }
    }
}
