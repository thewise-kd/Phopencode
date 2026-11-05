package io.github.thewisekd.phopencode.feature.files.logic.viewer

import java.io.DataInputStream
import java.io.File
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * Java 9 overrides `flip`, `position(int)` and the like covariantly in every `Buffer` subclass, and
 * old Android lacks those overrides: a call compiled as `CharBuffer.flip()Ljava/nio/CharBuffer;`
 * throws `NoSuchMethodError` there. This reads the compiled `ViewerDetection*.class` files and
 * fails if any method reference names such an override; calls must go through `java.nio.Buffer`.
 */
class ViewerBufferBytecodeTest {
    @Test
    fun viewerDetectionCallsNoCovariantBufferMethod() {
        val classes = classFiles()
        assertTrue(classes.isNotEmpty(), "no ViewerDetection class files found")
        val hits = classes.flatMap { (name, bytes) ->
            methodRefs(bytes).filter(::isCovariantBufferCall).map { "$name: ${it.owner}.${it.name}${it.descriptor}" }
        }
        assertTrue(hits.isEmpty(), "covariant Buffer methods, missing on old Android:\n" + hits.joinToString("\n"))
    }

    private data class MethodRef(val owner: String, val name: String, val descriptor: String)

    private fun isCovariantBufferCall(ref: MethodRef): Boolean =
        ref.owner.startsWith("java/nio/") && ref.owner.endsWith("Buffer") && ref.owner != "java/nio/Buffer" &&
            ref.name in COVARIANT && ref.descriptor.endsWith(")L${ref.owner};")

    /** `ViewerDetection.class` and its nested and lambda classes, from the test classpath. */
    private fun classFiles(): List<Pair<String, ByteArray>> {
        val main = ViewerDetection::class.java
        val simple = main.simpleName
        val found = linkedMapOf<String, ByteArray>()
        main.getResourceAsStream("$simple.class")?.use { found["$simple.class"] = it.readBytes() }
        val dir = runCatching { File(main.protectionDomain.codeSource.location.toURI()) }.getOrNull()
            ?.resolve(main.packageName.replace('.', '/'))
        dir?.listFiles { file -> file.name.startsWith("$simple$") && file.name.endsWith(".class") }
            ?.forEach { found[it.name] = it.readBytes() }
        return found.toList()
    }

    /** Every `Methodref` and `InterfaceMethodref` in the constant pool of [bytes]. */
    private fun methodRefs(bytes: ByteArray): List<MethodRef> {
        val input = DataInputStream(bytes.inputStream())
        check(input.readInt() == MAGIC) { "not a class file" }
        input.readUnsignedShort()
        input.readUnsignedShort()
        val count = input.readUnsignedShort()
        val utf8 = arrayOfNulls<String>(count)
        val refs = IntArray(count * 2)
        val tags = IntArray(count)
        var i = 1
        while (i < count) {
            val tag = input.readUnsignedByte()
            tags[i] = tag
            when (tag) {
                1 -> utf8[i] = input.readUTF()
                7, 8, 16, 19, 20 -> refs[2 * i] = input.readUnsignedShort()
                15 -> {
                    input.readUnsignedByte()
                    refs[2 * i] = input.readUnsignedShort()
                }
                3, 4 -> input.readInt()
                5, 6 -> {
                    input.readLong()
                    i++
                }
                9, 10, 11, 12, 17, 18 -> {
                    refs[2 * i] = input.readUnsignedShort()
                    refs[2 * i + 1] = input.readUnsignedShort()
                }
                else -> error("unknown constant pool tag $tag at $i")
            }
            i++
        }
        return (1 until count).filter { tags[it] == 10 || tags[it] == 11 }.map { ref ->
            val owner = utf8[refs[2 * refs[2 * ref]]]!!
            val nameAndType = refs[2 * ref + 1]
            MethodRef(owner, utf8[refs[2 * nameAndType]]!!, utf8[refs[2 * nameAndType + 1]]!!)
        }
    }

    private companion object {
        const val MAGIC = 0xCAFEBABE.toInt()
        val COVARIANT = setOf("flip", "position", "limit", "clear", "rewind", "mark", "reset")
    }
}
