package com.replaymod.gradle.remap.util

import com.replaymod.gradle.remap.Transformer
import com.replaymod.gradle.remap.legacy.LegacyMappingSetModelFactory
import org.cadixdev.lorenz.MappingSet
import java.io.ByteArrayOutputStream
import java.io.File
import java.net.URL
import java.nio.file.Path
import java.nio.file.Paths
import kotlin.io.path.absolutePathString

object TestData {
    private val mappingsPath = Paths.get(javaClass.getResource("/mappings.srg")!!.toURI())
    private val mappings = mappingsPath.readMappings().let { mappings ->
        val legacyCopy = MappingSet.create(LegacyMappingSetModelFactory())
        mappings.topLevelClassMappings.forEach { it.copy(legacyCopy) }
        legacyCopy
    }

    init {
        System.setProperty("idea.use.native.fs.for.win", "false")
    }

    val transformer = Transformer(mappings).apply {
        fun findClasspathEntry(cls: String): Path {
            val classFilePath = "/${cls.replace('.', '/')}.class"
            val url = javaClass.getResource(classFilePath)
                ?: throw RuntimeException("Failed to find $cls on classpath.")

            return when {
                url.protocol == "jar" && url.file.endsWith("!$classFilePath") -> {
                    Paths.get(URL(url.file.removeSuffix("!$classFilePath")).toURI())
                }
                url.protocol == "file" && url.file.endsWith(classFilePath) -> {
                    var path = Paths.get(url.toURI())
                    repeat(cls.count { it == '.' } + 1) {
                        path = path.parent
                    }
                    path
                }
                else -> {
                    throw RuntimeException("Do not know how to turn $url into classpath entry.")
                }
            }
        }
        jdkHome = File(System.getProperty("java.home"))
        if (jdkHome.endsWith("jre")) {
            jdkHome = jdkHome.parentFile
        }
        classpath = listOf(
            findClasspathEntry("org.spongepowered.asm.mixin.Mixin"),
            findClasspathEntry("a.pkg.A"),
            findClasspathEntry("AMarkerKt"),
        )
        remappedClasspath = arrayOf(
            findClasspathEntry("org.spongepowered.asm.mixin.Mixin").absolutePathString(),
            findClasspathEntry("b.pkg.B").absolutePathString(),
            findClasspathEntry("BMarkerKt").absolutePathString(),
        )
        patternAnnotation = "remap.Pattern"
        manageImports = true
    }

    fun remap(content: String): String =
        remap("test.java", content)
    fun remap(fileName: String, content: String): String =
        remap(fileName, content, "", "")
    fun remap(content: String, patternsBefore: String, patternsAfter: String): String =
        remap("test.java", content, patternsBefore, patternsAfter)
    fun remap(fileName: String, content: String, patternsBefore: String, patternsAfter: String): String {
        val path = Paths.get(".")
        val outputs = mutableMapOf<String, ByteArrayOutputStream>()
        val results = transformer.remap(mapOf(
            path to mapOf(
                fileName to { content.byteInputStream() },
                "pattern.java" to { "class Patterns {\n$patternsBefore\n}".byteInputStream() },
            )
        ), mapOf(
            "pattern.java" to { "class Patterns {\n$patternsAfter\n}".byteInputStream() },
        )) {
            _, name -> outputs.getOrPut(name) { ByteArrayOutputStream() }
        }
        for ((fileInfo, errors) in results) {
            if (errors.isEmpty()) continue
            println("${fileInfo.second} had ${errors.size} errors:")
            for ((line, message) in errors) {
                println("  $line: $message")
            }
        }
        return outputs[fileName]!!.toString()
    }
    fun remapWithErrors(content: String): Pair<String, List<Pair<Int, String>>> {
        val path = Paths.get(".")
        val outputs = mutableMapOf<String, ByteArrayOutputStream>()
        val errors = transformer.remap(mapOf(path to mapOf("test.java" to { content.byteInputStream() }))) { _, name -> outputs.getOrPut(name) { ByteArrayOutputStream() } }[path to "test.java"]!!
        return outputs["test.java"]!!.toString() to errors
    }

    fun remapKt(content: String): String {
        val path = Paths.get(".")
        val outputs = mutableMapOf<String, ByteArrayOutputStream>()
        transformer.remap(mapOf(path to mapOf("test.kt" to { content.byteInputStream() }))) {
            _, name -> outputs.getOrPut(name) { ByteArrayOutputStream() }
        }
        return outputs["test.kt"]!!.toString()
    }

    fun remapKt(content: Map<String, String>): Map<String, String> {
        val path = Paths.get(".")
        val outputs = mutableMapOf<String, ByteArrayOutputStream>()
        transformer.remap(mapOf(path to content.mapValues { { it.value.byteInputStream() } })) {
                _, name -> outputs.getOrPut(name) { ByteArrayOutputStream() }
        }
        return outputs.mapValues { it.value.toString() }
    }

    fun remapKtWithErrors(content: String): Pair<String, List<Pair<Int, String>>> {
        val path = Paths.get(".")
        val outputs = mutableMapOf<String, ByteArrayOutputStream>()
        val errors = transformer.remap(mapOf(path to mapOf("test.kt" to { content.byteInputStream() }))) {
            _, name -> outputs.getOrPut(name) { ByteArrayOutputStream() }
        }[path to "test.kt"]!!
        return outputs["test.kt"]!!.toString() to errors
    }

    fun remapKtWithErrors(content: Map<String, String>): Map<String, Pair<String, List<Pair<Int, String>>>> {
        val path = Paths.get(".")
        val outputs = mutableMapOf<String, ByteArrayOutputStream>()
        val errors = transformer.remap(mapOf(path to content.mapValues { { it.value.byteInputStream() } })) {
            _, name -> outputs.getOrPut(name) { ByteArrayOutputStream() }
        }
        return outputs.mapValues { it.value.toString() to errors.getValue(path to it.key) }
    }

}