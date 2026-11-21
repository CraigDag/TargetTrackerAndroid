package rangetarget.desktop

import org.opencv.core.Mat
import org.opencv.imgcodecs.Imgcodecs
import rangetarget.core.RangeTarget
import rangetarget.core.debug.RingOverlay
import rangetarget.core.geometry.CardGeometry
import java.io.File
import kotlin.system.exitProcess

private const val USAGE = """Usage: rangetarget <command> [options]

Commands:
  version                      Show the tool, core and OpenCV versions.
  geometry [--out FILE]        Print the card geometry as JSON (or write it to FILE).
  smoke <photo> [--out DIR]    Load a photo, draw the ideal rings centred on it and
                               write the result to DIR (default out/smoke/).
  help                         Show this message.
"""

/** Thrown for a bad command line; the message is shown to the user. */
class UsageException(message: String) : Exception(message)

fun main(args: Array<String>) {
    val code = try {
        run(args.toList())
    } catch (e: UsageException) {
        System.err.println("Error: ${e.message}\n")
        System.err.print(USAGE)
        2
    }
    exitProcess(code)
}

/** Runs one command and returns the process exit code. Split from [main] for tests. */
fun run(args: List<String>, out: (String) -> Unit = ::println): Int {
    val command = args.firstOrNull() ?: "help"
    val rest = args.drop(1)
    return when (command) {
        "version", "--version" -> { out(versionText()); 0 }
        "geometry" -> geometry(rest, out)
        "smoke" -> smoke(rest, out)
        "help", "--help", "-h" -> { out(USAGE.trimEnd()); 0 }
        else -> throw UsageException("Unknown command '$command'")
    }
}

fun versionText(): String = buildString {
    appendLine("rangetarget (desktop) ${RangeTarget.VERSION}")
    appendLine("core ${RangeTarget.VERSION}")
    appendLine("OpenCV ${OpenCvNatives.version()}")
    append("Java ${System.getProperty("java.version")}")
}

private fun geometry(args: List<String>, out: (String) -> Unit): Int {
    val opts = Options.parse(args, valued = setOf("--out"))
    usage(opts.positional.isEmpty()) { "geometry takes no file arguments" }
    val json = CardGeometry.exportJson()
    val target = opts.values["--out"]
    if (target == null) out(json.trimEnd()) else {
        File(target).apply { parentFile?.mkdirs() }.writeText(json)
        out("Wrote $target")
    }
    return 0
}

private fun smoke(args: List<String>, out: (String) -> Unit): Int {
    val opts = Options.parse(args, valued = setOf("--out"))
    val photo = opts.positional.singleOrNull()
        ?: throw UsageException("smoke needs exactly one photo")
    val outDir = File(opts.values["--out"] ?: "out/smoke")
    val input = File(photo)
    if (!input.isFile) throw UsageException("No such file: $photo")

    OpenCvNatives.load()
    val image: Mat = Imgcodecs.imread(input.path, Imgcodecs.IMREAD_COLOR)
    if (image.empty()) {
        System.err.println("Error: OpenCV could not read $photo as an image")
        return 1
    }
    val placement = RingOverlay.centredGuess(image.cols(), image.rows())
    RingOverlay.draw(image, placement, label = "Ideal rings, centred guess (not fitted)")

    outDir.mkdirs()
    val result = File(outDir, input.nameWithoutExtension + "_rings.jpg")
    if (!Imgcodecs.imwrite(result.path, image)) {
        System.err.println("Error: could not write ${result.path}")
        return 1
    }
    out("Read ${input.name}: ${image.cols()} x ${image.rows()} px")
    out("Drew ideal rings at %.2f px/mm, centred (no card detection yet)".format(placement.pxPerMm))
    out("Wrote ${result.path}")
    image.release()
    return 0
}

/** Minimal option parsing: `--name value` pairs plus positional arguments. */
data class Options(val positional: List<String>, val values: Map<String, String>) {
    companion object {
        fun parse(args: List<String>, valued: Set<String>): Options {
            val positional = mutableListOf<String>()
            val values = mutableMapOf<String, String>()
            var i = 0
            while (i < args.size) {
                val a = args[i]
                when {
                    a in valued -> {
                        values[a] = args.getOrNull(i + 1) ?: throw UsageException("$a needs a value")
                        i += 2
                    }
                    a.startsWith("--") -> throw UsageException("Unknown option $a")
                    else -> { positional += a; i++ }
                }
            }
            return Options(positional, values)
        }
    }
}

private fun usage(condition: Boolean, message: () -> String) {
    if (!condition) throw UsageException(message())
}
