package com.replaymod.gradle.remap

import net.fabricmc.mappingio.MappedElementKind
import net.fabricmc.mappingio.MappingFlag
import net.fabricmc.mappingio.MappingReader
import net.fabricmc.mappingio.MappingVisitor
import net.fabricmc.mappingio.format.MappingFormat
import net.sourceforge.argparse4j.ArgumentParsers
import net.sourceforge.argparse4j.annotation.Arg
import net.sourceforge.argparse4j.ext.java7.PathArgumentType
import net.sourceforge.argparse4j.impl.Arguments
import net.sourceforge.argparse4j.inf.Argument
import net.sourceforge.argparse4j.inf.ArgumentParser
import net.sourceforge.argparse4j.inf.ArgumentParserException
import net.sourceforge.argparse4j.inf.ArgumentType
import org.cadixdev.lorenz.MappingSet
import org.cadixdev.lorenz.model.ClassMapping
import org.cadixdev.lorenz.model.FieldMapping
import org.cadixdev.lorenz.model.MethodMapping
import org.cadixdev.lorenz.model.MethodParameterMapping
import java.io.*
import java.net.URI
import java.nio.file.*
import java.util.stream.Collectors
import java.util.zip.ZipFile
import java.util.zip.ZipOutputStream
import kotlin.system.exitProcess
import kotlin.time.ExperimentalTime
import kotlin.time.measureTime

private object PathListArgumentType : ArgumentType<List<Path>> {
    override fun convert(parser: ArgumentParser, arg: Argument, value: String): List<Path> {
        try {
            return value.splitToSequence(File.pathSeparatorChar)
                .map(Paths::get)
                .onEach {
                    if (!Files.isReadable(it)) {
                        throw ArgumentParserException("Cannot read $it", parser, arg)
                    }
                }
                .toList()
        } catch (e: InvalidPathException) {
            throw ArgumentParserException(e.message, e, parser, arg)
        }
    }
}

private class MappingsReadException(message: String) : Exception(message)

private fun readMappings(path: Path, targetNamespace: String?): MappingSet {
    val result = MappingSet.create()
    MappingReader.read(path, object : MappingVisitor {
        var targetNs: Int = 0

        var currentClass: ClassMapping<*, *>? = null
        var currentField: FieldMapping? = null
        var currentMethod: MethodMapping? = null
        var currentParameter: MethodParameterMapping? = null

        override fun getFlags() = setOf(MappingFlag.NEEDS_SRC_METHOD_DESC)

        override fun visitNamespaces(srcNamespace: String, dstNamespaces: List<String>) {
            if (targetNamespace == null) {
                if (dstNamespaces.size != 1) {
                    throw MappingsReadException(
                        "Cannot infer target namespace with multiple possibilities: ${dstNamespaces.joinToString()}"
                    )
                }
                return
            }
            targetNs = dstNamespaces.indexOf(targetNamespace)
            if (targetNs == -1) {
                throw MappingsReadException("Could not find target namespace in: ${dstNamespaces.joinToString()}")
            }
        }

        override fun visitClass(srcName: String): Boolean {
            currentClass = result.getOrCreateClassMapping(srcName)
            currentField = null
            currentMethod = null
            currentParameter = null
            return true
        }

        override fun visitField(srcName: String, srcDesc: String?): Boolean {
            currentField = if (srcDesc != null) {
                currentClass?.getOrCreateFieldMapping(srcName, srcDesc)
                    ?: throw MappingsReadException("No owning class found for field $srcName:$srcDesc")
            } else {
                currentClass?.getOrCreateFieldMapping(srcName)
                    ?: throw MappingsReadException("No owning class found for field $srcName")
            }
            currentMethod = null
            currentParameter = null
            return true
        }

        override fun visitMethod(srcName: String, srcDesc: String?): Boolean {
            if (srcDesc == null) {
                throw MappingsReadException("No source descriptor found for method ${currentClass?.deobfuscatedName}.$srcName")
            }
            currentField = null
            currentMethod = currentClass?.getOrCreateMethodMapping(srcName, srcDesc)
                ?: throw MappingsReadException("No owning class found for field $srcName$srcDesc")
            currentParameter = null
            return true
        }

        override fun visitMethodArg(argPosition: Int, lvIndex: Int, srcName: String?): Boolean {
            currentParameter = currentMethod?.getOrCreateParameterMapping(lvIndex)
                ?: throw MappingsReadException("No owning method found for parameter $srcName at index $lvIndex")
            return true
        }

        override fun visitDstName(targetKind: MappedElementKind, namespace: Int, name: String) {
            if (namespace != targetNs) return
            when (targetKind) {
                MappedElementKind.CLASS -> currentClass?.setDeobfuscatedName(name)
                    ?: throw MappingsReadException("No source name found for class $name")
                MappedElementKind.FIELD -> currentField?.setDeobfuscatedName(name)
                    ?: throw MappingsReadException("No source name found for field ${currentClass?.deobfuscatedName}.$name")
                MappedElementKind.METHOD -> currentMethod?.setDeobfuscatedName(name)
                    ?: throw MappingsReadException("No source name found for method ${currentClass?.deobfuscatedName}.$name")
                MappedElementKind.METHOD_ARG -> currentParameter?.setDeobfuscatedName(name)
                    ?: throw MappingsReadException(
                        "No source index found for parameter " +
                            "${currentClass?.deobfuscatedName}.${currentMethod?.deobfuscatedName}.$name"
                    )
                MappedElementKind.METHOD_VAR -> Unit // Not supported by lorenz
            }
        }

        override fun visitMethodVar(
            lvtRowIndex: Int, lvIndex: Int, startOpIdx: Int, endOpIdx: Int, srcName: String?
        ) = false // Not supported by lorenz

        // No need. Maybe if this was called for ProGuard we'd print it.
        override fun visitComment(targetKind: MappedElementKind, comment: String) = Unit
    })
    return result
}

@Suppress("OPT_IN_IS_NOT_ENABLED")
@OptIn(ExperimentalTime::class)
fun main(vararg args: String) {
    val parser = ArgumentParsers.newFor("source-remap")
        .fromFilePrefix("@")
        .build()
        .description("Remaps Java/Kotlin source files, including Mixins")
    parser.addArgument("-cp", "--classpath")
        .help("The context classpath")
        .type(PathListArgumentType)
        .action(Arguments.append())
        .setDefault(mutableListOf<List<Path>>())
    parser.addArgument("-m", "--mappings")
        .help("A mappings file. The supported formats are: ${MappingFormat.values().joinToString { it.name }}.")
        .type(PathArgumentType().verifyExists())
        .action(Arguments.append())
        .required(true)
    parser.addArgument("-t", "--target-namespace")
        .help("The target namespace to map to. Useful for tiny mappings where multiple potential targets could exist.")
        .metavar("NAMESPACE")
    parser.addArgument("--reverse")
        .help("Use reverse mappings. This makes -t specify the source mappings.")
        .action(Arguments.storeTrue())
    parser.addArgument("-r", "--remap")
        .help("The jars/directories to remap. If a specified input doesn't exist, it will be skipped.")
        .type(PathArgumentType())
        .nargs(2)
        .metavar("INPUT", "OUTPUT")
        .action(Arguments.append())
        .required(true)

    val parsedArgs = object {
        @set:Arg(dest = "classpath")
        var classpath: List<List<Path>> = listOf()

        @set:Arg(dest = "mappings")
        var mappings: List<Path> = listOf()

        @set:Arg(dest = "reverse")
        var reverse: Boolean = false

        @set:Arg(dest = "target_namespace")
        var targetNamespace: String? = null

        @set:Arg(dest = "remap")
        var remap: List<List<Path>> = listOf()
    }

    try {
        parser.parseArgs(args, parsedArgs)
    } catch (e: ArgumentParserException) {
        parser.handleError(e)
        exitProcess(1)
    }
    val classpath = parsedArgs.classpath.asSequence().flatMap { it }.toSet()
    val remap = parsedArgs.remap.asSequence().filter { Files.exists(it[0]) }.associate { it[0] to it[1] }

    var mappings = try {
        parsedArgs.mappings.asSequence()
            .map { readMappings(it, parsedArgs.targetNamespace) }
            .reduce(MappingSet::merge)
    } catch (e: MappingsReadException) {
        System.err.println(e.message)
        exitProcess(1)
    } catch (e: IOException) {
        System.err.println("Failed to read mappings: ${e.message}")
        exitProcess(1)
    }
    if (parsedArgs.reverse) {
        mappings = mappings.reverse()
    }

    println("Running remapper with classpath:")
    println(classpath.joinToString(File.pathSeparator))
    println("Inputs:")
    println(remap.keys.joinToString(File.pathSeparator))
    println("Outputs:")
    println(remap.values.joinToString(File.pathSeparator))

    val time = measureTime {
        runTransformer(mappings, classpath, remap)
    }
    println("Finished running remapper in $time")
}

private fun runTransformer(mappings: MappingSet, classpath: Collection<Path>, remap: Map<Path, Path>) {
    System.setProperty("idea.use.native.fs.for.win", "false")
    val transformer = Transformer(mappings)

    transformer.classpath = classpath

    val closeLater = mutableListOf<Closeable>()

    try {
        val sources: Map<Path, Map<String, () -> InputStream>> = remap.keys.associateWith { root ->
            if (Files.isDirectory(root)) {
                scanFolder(root)
            } else if (Files.isRegularFile(root)) {
                if (root.toString().endsWith(".java") || root.toString().endsWith(".kt")) {
                    mapOf(root.fileName.toString() to { Files.newInputStream(root) })
                } else if (root.toString().endsWith(".jar") || root.toString().endsWith(".zip")) {
                    val jar = root.toFile()
                    val zip = ZipFile(jar)
                    closeLater.add(zip)
                    zip.entries().asSequence().filter { !it.isDirectory && (it.name.endsWith(".java") || it.name.endsWith(".kt")) }.associate { entry ->
                        entry.name to { zip.getInputStream(entry) }
                    }
                } else {
                    throw IllegalArgumentException("Input root must be a directory, jar, or source file. unsupported type: ${root.fileName}")
                }
            } else {
                throw IllegalArgumentException("Input root must be a directory, jar, or source file. unsupported type: ${root.fileName}")
            }
        }

        // delete existing output files
        for (output in remap.values) {
            if (Files.isDirectory(output)) {
                Files.walk(output).sorted(Comparator.reverseOrder()).forEach { Files.delete(it) }
            } else if (Files.isRegularFile(output)) {
                Files.delete(output)
            }
        }

        val results = transformer.remap(sources) { root, unit ->
            if (root.toString().endsWith(".jar") || root.toString().endsWith(".zip")) {
                val outputPath = remap.getValue(root)
                if (outputPath.toString().endsWith(".jar") || outputPath.toString().endsWith(".zip")) {
                    val jar = outputPath.toFile()
                    if (!jar.exists()) {
                        val zos = ZipOutputStream(FileOutputStream(jar))
                        zos.close()
                    }
                    val fs = outputPath.openZipFileSystem(mapOf("create" to false))
                    closeLater.add(fs)
                    Files.newOutputStream(
                        fs.getPath(unit),
                        StandardOpenOption.CREATE,
                        StandardOpenOption.TRUNCATE_EXISTING
                    )
                } else if (outputPath.endsWith(".java") || outputPath.endsWith(".kt")) {
                    Files.newOutputStream(outputPath, StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING)
                } else {
                    Files.newOutputStream(
                        outputPath.resolve(unit),
                        StandardOpenOption.CREATE,
                        StandardOpenOption.TRUNCATE_EXISTING
                    )
                }
            }
            val output = remap.getValue(root).resolve(unit)
            Files.createDirectories(output.parent)
            Files.newOutputStream(output, StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING)
        }

        for ((root, unitName) in sources.entries.flatMap { entry -> entry.value.keys.map { entry.key to it } }) {
            val errors = results[root to unitName]!!
            if (errors.isNotEmpty()) {
                println("Errors encountered remapping: ${root.fileName}/$unitName")
                for ((line, message) in errors) {
                    println("    $line: $message")
                }
            }
        }

    } finally {
        for (closeable in closeLater) {
            closeable.close()
        }
    }
}

private fun Path.openZipFileSystem(args: Map<String, *> = mapOf<String, Any>()): FileSystem {
    if (!Files.exists(this) && args["create"] == true) {
        ZipOutputStream(Files.newOutputStream(this)).use { stream ->
            stream.closeEntry()
        }
    }
    return FileSystems.newFileSystem(URI.create("jar:${toUri()}"), args, null)
}

private fun scanFolder(folder: Path): Map<String, () -> InputStream> {
    return Files.walk(folder)
        .filter { Files.isRegularFile(it) }
        .filter { it.toString().endsWith(".java") || it.toString().endsWith(".kt") }
        .map { folder.relativize(it).toString() to { Files.newInputStream(it) } }
        .collect(Collectors.toList()).toMap()
}
