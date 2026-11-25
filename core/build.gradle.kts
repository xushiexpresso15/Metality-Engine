plugins {
    `java-library`
}

val cmakeBin = if (file("/opt/homebrew/bin/cmake").exists()) "/opt/homebrew/bin/cmake" else "cmake"

java {
    toolchain {
        languageVersion.set(JavaLanguageVersion.of(25))
    }
    withSourcesJar()
}

repositories {
    mavenCentral()
}

dependencies {
    api(project(":api"))
    implementation("org.slf4j:slf4j-api:2.0.17")

    testImplementation("org.junit.jupiter:junit-jupiter:5.11.4")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

val nativeSrcDir = rootProject.file("native")
val nativeBuildDir = layout.buildDirectory.dir("cmake-native")

val configureNativeMetalCore by tasks.registering(Exec::class) {
    inputs.dir(nativeSrcDir.resolve("include"))
    inputs.dir(nativeSrcDir.resolve("src"))
    inputs.file(nativeSrcDir.resolve("CMakeLists.txt"))
    outputs.dir(nativeBuildDir)

    commandLine(
        cmakeBin,
        "-S", nativeSrcDir.absolutePath,
        "-B", nativeBuildDir.get().asFile.absolutePath,
        "-DCMAKE_BUILD_TYPE=Release"
    )
}

val buildNativeMetalCore by tasks.registering(Exec::class) {
    dependsOn(configureNativeMetalCore)
    inputs.dir(nativeSrcDir.resolve("include"))
    inputs.dir(nativeSrcDir.resolve("src"))
    outputs.file(nativeBuildDir.map { it.file("libmetality_native.dylib") })

    commandLine(
        cmakeBin,
        "--build", nativeBuildDir.get().asFile.absolutePath,
        "--target", "metality_native",
        "--parallel", "8"
    )
}

tasks.processResources {
    dependsOn(buildNativeMetalCore)
    from(nativeBuildDir.map { it.file("libmetality_native.dylib") }) {
        into("natives/macos-arm64")
    }
}

tasks.withType<JavaCompile>().configureEach {
    options.encoding = "UTF-8"
    options.release.set(25)
}

tasks.test {
    dependsOn(buildNativeMetalCore)
    useJUnitPlatform()
    jvmArgs("--enable-native-access=ALL-UNNAMED")
    systemProperty("metality.native.dylib", nativeBuildDir.get().file("libmetality_native.dylib").asFile.absolutePath)
    systemProperty("metality.example.pack", rootProject.file("sdk/examples/aurora-tbdr").absolutePath)
    systemProperty("metality.legacy.example.pack", rootProject.file("sdk/examples/iris-aurora-legacy").absolutePath)
    systemProperty("metality.meteor.std.pack", rootProject.file("shaderpacks/meteor-dusk").absolutePath)
    systemProperty("metality.meteor.rt.pack", rootProject.file("shaderpacks/meteor-zenith").absolutePath)
    systemProperty("metality.meteor.dusk.pack", rootProject.file("shaderpacks/meteor-dusk").absolutePath)
    systemProperty("metality.meteor.zenith.pack", rootProject.file("shaderpacks/meteor-zenith").absolutePath)
}
