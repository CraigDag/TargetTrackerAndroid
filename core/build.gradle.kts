import org.jetbrains.kotlin.gradle.dsl.JvmTarget

// The analysis engine. Plain Kotlin/JVM so the same code runs on a computer
// (via :desktop) and inside the Android app later, unchanged.
// Rule: only the OpenCV Java API (org.opencv.*), never android.* or java.awt.
plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.kotlin.serialization)
    `java-library`
}

java {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
}

kotlin {
    compilerOptions { jvmTarget.set(JvmTarget.JVM_17) }
}

// The official OpenCV Android SDK at the same version, used only by the API
// check test to prove every OpenCV call core makes also exists on Android.
val androidOpenCv: Configuration by configurations.creating {
    isCanBeConsumed = false
    isCanBeResolved = true
    isTransitive = false
}

dependencies {
    api(libs.kotlinx.serialization.json)
    // Compile against the OpenCV Java API only. Each platform supplies the
    // actual library and natives: :desktop the openpnp bundle, :android the SDK.
    compileOnly(libs.opencv.desktop)

    testImplementation(libs.opencv.desktop)
    testImplementation(platform(libs.junit.bom))
    testImplementation(libs.junit.jupiter)
    testRuntimeOnly(libs.junit.platform.launcher)

    androidOpenCv("org.opencv:opencv:${libs.versions.opencv.get()}@aar")
}

val extractAndroidOpenCvClasses by tasks.registering(Sync::class) {
    from({ androidOpenCv.map { zipTree(it) } }) { include("classes.jar") }
    into(layout.buildDirectory.dir("android-opencv"))
}

tasks.test {
    useJUnitPlatform()
    dependsOn(extractAndroidOpenCvClasses)
    val androidJar = layout.buildDirectory.file("android-opencv/classes.jar")
    inputs.file(androidJar)
    systemProperty("rangetarget.androidOpenCvJar", androidJar.get().asFile.absolutePath)
    systemProperty("rangetarget.repoRoot", rootDir.absolutePath)
    systemProperty("java.awt.headless", "true")
    testLogging { events("failed"); exceptionFormat = org.gradle.api.tasks.testing.logging.TestExceptionFormat.FULL }
}

// Paths the OpenCV API check test reads: core's own compiled classes (to find
// every OpenCV call core makes) and the desktop OpenCV jar.
val desktopOpenCvJar = configurations.testRuntimeClasspath.map { cp ->
    cp.filter { it.name.startsWith("opencv-") && it.extension == "jar" }
}
tasks.test {
    val mainClasses = sourceSets.main.get().output.classesDirs
    inputs.files(mainClasses)
    systemProperty("rangetarget.coreClassesDirs", mainClasses.asPath)
    jvmArgumentProviders += CommandLineArgumentProvider {
        listOf("-Drangetarget.desktopOpenCvJar=" + desktopOpenCvJar.get().singleFile.absolutePath)
    }
}
