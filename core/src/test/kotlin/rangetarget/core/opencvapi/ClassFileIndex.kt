package rangetarget.core.opencvapi

import java.io.DataInputStream
import java.io.File
import java.io.InputStream
import java.util.zip.ZipFile

/** A method or field reference found in, or declared by, a class file. */
data class MemberRef(val owner: String, val name: String, val descriptor: String) {
    override fun toString() = "${owner.replace('/', '.')}#$name$descriptor"
}

/** What a class declares: its superclass and its methods (name + descriptor). */
class ClassInfo(val name: String, val superName: String?, val methods: Set<Pair<String, String>>)

/**
 * Just enough class-file parsing to (a) list the OpenCV members core's compiled
 * code refers to and (b) list the members an OpenCV jar declares. Test-only.
 */
object ClassFileReader {
    class Parsed(val info: ClassInfo, val references: List<MemberRef>, val classRefs: Set<String>)

    fun parse(input: InputStream): Parsed {
        val d = DataInputStream(input.buffered())
        require(d.readInt() == 0xCAFEBABE.toInt()) { "Not a class file" }
        d.readUnsignedShort(); d.readUnsignedShort() // minor, major
        val count = d.readUnsignedShort()
        val utf8 = arrayOfNulls<String>(count)
        val classIdx = IntArray(count)
        val natIdx = Array(count) { IntArray(2) }
        val refIdx = Array(count) { IntArray(2) }
        val isRef = BooleanArray(count)
        var i = 1
        while (i < count) {
            when (val tag = d.readUnsignedByte()) {
                1 -> utf8[i] = d.readUTF()
                3, 4 -> d.readInt()
                5, 6 -> { d.readLong(); i++ }
                7 -> classIdx[i] = d.readUnsignedShort()
                8, 16, 19, 20 -> d.readUnsignedShort()
                9, 10, 11 -> { refIdx[i][0] = d.readUnsignedShort(); refIdx[i][1] = d.readUnsignedShort(); isRef[i] = true }
                12 -> { natIdx[i][0] = d.readUnsignedShort(); natIdx[i][1] = d.readUnsignedShort() }
                15 -> { d.readUnsignedByte(); d.readUnsignedShort() }
                17, 18 -> { d.readUnsignedShort(); d.readUnsignedShort() }
                else -> error("Unknown constant pool tag $tag")
            }
            i++
        }
        fun className(idx: Int) = utf8[classIdx[idx]]!!
        val refs = (1 until count).filter { isRef[it] }.map {
            val nat = natIdx[refIdx[it][1]]
            MemberRef(className(refIdx[it][0]), utf8[nat[0]]!!, utf8[nat[1]]!!)
        }
        val classRefs = (1 until count).filter { classIdx[it] != 0 }.map { className(it) }.toSet()
        d.readUnsignedShort() // access flags
        val thisName = className(d.readUnsignedShort())
        val superIdx = d.readUnsignedShort()
        val superName = if (superIdx == 0) null else className(superIdx)
        repeat(d.readUnsignedShort()) { d.readUnsignedShort() } // interfaces
        fun skipMembers(collect: MutableSet<Pair<String, String>>?) {
            repeat(d.readUnsignedShort()) {
                d.readUnsignedShort()
                val name = utf8[d.readUnsignedShort()]!!
                val desc = utf8[d.readUnsignedShort()]!!
                collect?.add(name to desc)
                repeat(d.readUnsignedShort()) { d.readUnsignedShort(); d.skipNBytes(d.readInt().toLong()) }
            }
        }
        skipMembers(null) // fields
        val methods = mutableSetOf<Pair<String, String>>()
        skipMembers(methods)
        return Parsed(ClassInfo(thisName, superName, methods), refs, classRefs)
    }
}

/** All classes in a jar, so members can be looked up including inherited ones. */
class JarIndex(jar: File) {
    val classes: Map<String, ClassInfo> = ZipFile(jar).use { zip ->
        zip.entries().asSequence()
            .filter { it.name.endsWith(".class") && it.name.startsWith("org/opencv/") }
            .map { e -> zip.getInputStream(e).use { ClassFileReader.parse(it).info } }
            .associateBy { it.name }
    }

    fun hasClass(owner: String) = owner in classes

    /** True if [owner] or one of its OpenCV superclasses declares the method. */
    fun hasMethod(owner: String, name: String, descriptor: String): Boolean {
        var c = classes[owner]
        while (c != null) {
            if ((name to descriptor) in c.methods) return true
            c = c.superName?.let { classes[it] }
        }
        return false
    }

    fun overloads(owner: String, name: String): Set<String> {
        val out = mutableSetOf<String>()
        var c = classes[owner]
        while (c != null) {
            c.methods.filter { it.first == name }.forEach { out += it.second }
            c = c.superName?.let { classes[it] }
        }
        return out
    }
}

/** Every OpenCV method core's compiled classes call. */
fun openCvCallsIn(classDirs: List<File>): Set<MemberRef> =
    classDirs.flatMap { dir -> dir.walkTopDown().filter { it.extension == "class" }.toList() }
        .flatMap { f -> f.inputStream().use { ClassFileReader.parse(it).references } }
        .filter { it.owner.startsWith("org/opencv/") && it.descriptor.startsWith("(") }
        .toSet()

/** Every class name (internal form, e.g. "java/awt/Image") core's compiled classes refer to. */
fun classRefsIn(classDirs: List<File>): Set<String> =
    classDirs.flatMap { dir -> dir.walkTopDown().filter { it.extension == "class" }.toList() }
        .flatMap { f -> f.inputStream().use { ClassFileReader.parse(it).classRefs } }
        .toSet()
