package rangetarget.core.opencvapi

import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.io.File

/** Core must run unchanged on Android: no Android-only or desktop-only classes. */
class PortabilityTest {
    private val forbidden = listOf("android/", "java/awt/", "javax/imageio/", "javax/swing/", "nu/pattern/")

    @Test
    fun `core uses no Android-only or desktop-only classes`() {
        val dirs = System.getProperty("rangetarget.coreClassesDirs")!!
            .split(File.pathSeparator).map(::File).filter { it.isDirectory }
        val refs = classRefsIn(dirs)
        assertTrue(refs.isNotEmpty(), "scan found no classes")
        val bad = refs.filter { r -> forbidden.any { r.removePrefix("[").removePrefix("L").startsWith(it) } }
        assertTrue(bad.isEmpty(), "Core refers to platform-specific classes: $bad")
    }
}
