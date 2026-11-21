package rangetarget.core.opencvapi

import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import java.io.File

/**
 * Proves that every OpenCV call core makes, or is planned to make, exists in
 * both OpenCV builds: the desktop one (openpnp bundle) and the official
 * Android SDK, at the same version. Runs on the JVM by reading the Android
 * SDK's Java classes; the Android natives themselves are proven in Phase 6.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class OpenCvApiTest {
    private lateinit var desktop: JarIndex
    private lateinit var android: JarIndex

    private fun prop(name: String): String =
        System.getProperty(name) ?: error("System property $name not set; run through Gradle")

    @BeforeAll
    fun load() {
        desktop = JarIndex(File(prop("rangetarget.desktopOpenCvJar")))
        android = JarIndex(File(prop("rangetarget.androidOpenCvJar")))
    }

    @Test
    fun `both OpenCV jars are readable`() {
        assertTrue(desktop.hasClass("org/opencv/core/Mat"), "desktop jar has no Mat")
        assertTrue(android.hasClass("org/opencv/core/Mat"), "android jar has no Mat")
    }

    @Test
    fun `every OpenCV call in core exists on Android`() {
        val dirs = prop("rangetarget.coreClassesDirs").split(File.pathSeparator).map(::File).filter { it.isDirectory }
        val calls = openCvCallsIn(dirs)
        assertTrue(calls.isNotEmpty(), "found no OpenCV calls in core; is the scan broken?")
        val missing = calls.filterNot { android.hasMethod(it.owner, it.name, it.descriptor) }
        assertTrue(missing.isEmpty(), "Core calls OpenCV methods the Android SDK lacks:\n" + missing.joinToString("\n"))
    }

    @Test
    fun `planned OpenCV calls exist on both platforms with the same overloads`() {
        val planned = javaClass.getResource("/opencv-planned-api.txt")!!.readText().lines()
            .map { it.trim() }.filter { it.isNotEmpty() && !it.startsWith("#") }
        val problems = mutableListOf<String>()
        for (entry in planned) {
            val (cls, method) = entry.split("#")
            val owner = cls.replace('.', '/')
            val onDesktop = desktop.overloads(owner, method)
            val onAndroid = android.overloads(owner, method)
            when {
                onDesktop.isEmpty() -> problems += "$entry: not in the desktop build"
                onAndroid.isEmpty() -> problems += "$entry: not in the Android build"
                !onAndroid.containsAll(onDesktop) ->
                    problems += "$entry: Android lacks overloads ${onDesktop - onAndroid}"
            }
        }
        assertTrue(problems.isEmpty(), problems.joinToString("\n"))
    }
}
