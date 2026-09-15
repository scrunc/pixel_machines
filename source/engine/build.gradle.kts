plugins {
    `java-library`
}

group = "dev.servereer"
version = "0.1.0"

repositories {
    mavenCentral()
    maven("https://repo.papermc.io/repository/maven-public/")
    maven("https://repo.codemc.io/repository/maven-releases/")
    maven("https://maven.enginehub.org/repo/")
    maven("https://maven.maxhenkel.de/repository/public/")
}

dependencies {
    compileOnly("io.papermc.paper:paper-api:1.21.11-R0.1-SNAPSHOT")   // 1.21.11: PlayerInputEvent (the claw reads WASD like ArcadeCab)
    // Server-provided packetevents (plugin `depend`), compiled against the exact
    // runtime version (2.12.1) so there is no API drift. Not shaded — a second
    // packetevents instance would conflict with the server's packet injection.
    compileOnly("com.github.retrooper:packetevents-spigot:2.12.1")
    // WorldEdit (soft-depend): /mc scanfarm reads a region's blocks/chests.
    // compileOnly — the scan commands no-op if WorldEdit/FAWE isn't installed.
    // Pulled in WITHOUT its dependency graph: WorldEdit pins guava/gson/fastutil "strictly" to versions
    // older than paper-api 1.21.11 asks for, and since every one of these is compileOnly (the server
    // supplies them all at runtime) the constraint fight is pure noise.
    compileOnly("com.sk89q.worldedit:worldedit-bukkit:7.3.6") { isTransitive = false }
    compileOnly("com.sk89q.worldedit:worldedit-core:7.3.6") { isTransitive = false }
    // Simple Voice Chat server API (soft-depend): jukebox/music audio. compileOnly —
    // playback degrades gracefully to silent if the SVC plugin isn't installed.
    compileOnly("de.maxhenkel.voicechat:voicechat-api:2.6.20")
}

java {
    toolchain {
        languageVersion = JavaLanguageVersion.of(21)
    }
}

tasks.processResources {
    inputs.property("version", version)
    filesMatching("plugin.yml") {
        expand("version" to version)
    }
}

tasks.jar {
    archiveBaseName.set("MachineConstruct")
}
