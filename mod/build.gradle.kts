plugins {
    id("net.fabricmc.fabric-loom") version "1.17.5"
    `java-library`
}

val minecraftVersion = property("minecraft_version") as String
val loaderVersion = property("loader_version") as String
val fabricApiVersion = property("fabric_api_version") as String

base {
    archivesName.set("${property("archives_base_name")}-mc$minecraftVersion")
}

java {
    toolchain {
        languageVersion.set(JavaLanguageVersion.of(25))
    }
    withSourcesJar()
}

dependencies {
    minecraft("com.mojang:minecraft:$minecraftVersion")
    implementation("net.fabricmc:fabric-loader:$loaderVersion")
    implementation("net.fabricmc.fabric-api:fabric-api:$fabricApiVersion")

    api(project(":api"))
    implementation(project(":core"))
    include(project(":api"))
    include(project(":core"))

    testImplementation("org.junit.jupiter:junit-jupiter:5.11.4")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

tasks.processResources {
    inputs.property("version", project.version)
    filesMatching("fabric.mod.json") {
        expand("version" to project.version)
    }
}

tasks.withType<JavaCompile>().configureEach {
    options.encoding = "UTF-8"
    options.release.set(25)
}

tasks.test {
    dependsOn(":core:buildNativeMetalCore")
    useJUnitPlatform()
    jvmArgs("--enable-native-access=ALL-UNNAMED")
    val coreDylib = project(":core").layout.buildDirectory.file("cmake-native/libmetality_native.dylib")
    systemProperty("metality.native.dylib", coreDylib.get().asFile.absolutePath)
    systemProperty("metality.example.pack", rootProject.file("sdk/examples/aurora-tbdr").absolutePath)
    systemProperty("metality.legacy.example.pack", rootProject.file("sdk/examples/iris-aurora-legacy").absolutePath)
    systemProperty("metality.meteor.std.pack", rootProject.file("shaderpacks/meteor-dusk").absolutePath)
    systemProperty("metality.meteor.rt.pack", rootProject.file("shaderpacks/meteor-zenith").absolutePath)
    systemProperty("metality.meteor.dusk.pack", rootProject.file("shaderpacks/meteor-dusk").absolutePath)
    systemProperty("metality.meteor.zenith.pack", rootProject.file("shaderpacks/meteor-zenith").absolutePath)
    systemProperty("metality.api.classes", project(":api").layout.buildDirectory.dir("classes/java/main").get().asFile.absolutePath)
    systemProperty("metality.core.classes", project(":core").layout.buildDirectory.dir("classes/java/main").get().asFile.absolutePath)
}

loom {
    runs {
        named("client") {
            vmArgs(
                "--enable-native-access=ALL-UNNAMED",
                "-Dmetality.enableEdr=true",
                "-Dmetality.enableRenderPearlBackend=true"
            )
        }
    }
}
