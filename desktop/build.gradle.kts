import org.jetbrains.kotlin.gradle.dsl.JvmTarget

// Command-line test harness: runs :core on photos and session folders on a
// computer. Not a product. `./gradlew :desktop:installDist` builds
// desktop/build/install/rangetarget/bin/rangetarget.
plugins {
    alias(libs.plugins.kotlin.jvm)
    application
}

java {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
}

kotlin {
    compilerOptions { jvmTarget.set(JvmTarget.JVM_17) }
}

dependencies {
    implementation(project(":core"))
    implementation(libs.opencv.desktop) // OpenCV Java API + bundled natives

    testImplementation(platform(libs.junit.bom))
    testImplementation(libs.junit.jupiter)
    testRuntimeOnly(libs.junit.platform.launcher)
}

application {
    applicationName = "rangetarget"
    mainClass.set("rangetarget.desktop.MainKt")
}

tasks.test {
    useJUnitPlatform()
    systemProperty("rangetarget.repoRoot", rootDir.absolutePath)
    systemProperty("java.awt.headless", "true")
    testLogging { events("failed"); exceptionFormat = org.gradle.api.tasks.testing.logging.TestExceptionFormat.FULL }
}
