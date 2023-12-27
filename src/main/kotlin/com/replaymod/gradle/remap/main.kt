package com.replaymod.gradle.remap

import net.sourceforge.argparse4j.ArgumentParsers
import net.sourceforge.argparse4j.annotation.Arg
import net.sourceforge.argparse4j.ext.java7.PathArgumentType
import net.sourceforge.argparse4j.impl.Arguments
import net.sourceforge.argparse4j.inf.Argument
import net.sourceforge.argparse4j.inf.ArgumentParser
import net.sourceforge.argparse4j.inf.ArgumentParserException
import net.sourceforge.argparse4j.inf.ArgumentType
import org.cadixdev.lorenz.MappingSet
import org.cadixdev.lorenz.io.MappingFormats
import java.io.Closeable
import java.io.File
import java.io.FileOutputStream
import java.io.InputStream
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

private object MappingSetArgumentType : ArgumentType<MappingSet> {
    private val formatByExtension = mapOf(
        "srg" to MappingFormats.SRG,
        "csrg" to MappingFormats.CSRG,
        "tsrg" to MappingFormats.TSRG
    )
    private val inner = PathArgumentType().verifyIsFile().verifyCanRead()

    override fun convert(parser: ArgumentParser, arg: Argument, value: String): MappingSet {
        val extension = value.substringAfterLast('.', "").lowercase()
        val format = formatByExtension[extension] ?: throw ArgumentParserException(
            "Unsupported mapping extension .$extension. Supported: ${formatByExtension.keys.joinToString { ".$it" }}.",
            parser, arg
        )
        val path = inner.convert(parser, arg, value)
        try {
            return format.read(path)
        } catch (e: Exception) {
            throw ArgumentParserException(e.message, e, parser, arg)
        }
    }
}

@Suppress("OPT_IN_IS_NOT_ENABLED")
@OptIn(ExperimentalTime::class)
fun main(vararg args: String) {
    val parser = ArgumentParsers.newFor("source-remap")
        .fromFilePrefix("@")
        .build()
        .defaultHelp(true)
        .description("Remaps Java/Kotlin sources")
    parser.addArgument("-cp", "--classpath")
        .type(PathListArgumentType)
        .action(Arguments.append())
        .setDefault(mutableListOf<List<Path>>())
    parser.addArgument("-r", "--remap")
        .type(PathArgumentType())
        .nargs(2)
        .metavar("INPUT", "OUTPUT")
        .action(Arguments.append())
        .required(true)
    parser.addArgument("mappings")
        .type(MappingSetArgumentType)

    val parsedArgs = object {
        @set:Arg(dest = "classpath")
        var classpath: List<List<Path>> = listOf()

        @set:Arg(dest = "remap")
        var remap: List<List<Path>> = listOf()

        @set:Arg(dest = "mappings")
        var mappings: MappingSet = MappingSet.create()
    }

    try {
        parser.parseArgs(args, parsedArgs)
    } catch (e: ArgumentParserException) {
        parser.handleError(e)
        exitProcess(1)
    }
    val classpath = parsedArgs.classpath.asSequence().flatMap { it }.toSet()
    val remap = parsedArgs.remap.asSequence().filter { Files.exists(it[0]) }.associate { it[0] to it[1] }
    val mappings = parsedArgs.mappings

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
    val transformer = Transformer(mappings)

    transformer.classpath = classpath.map(Path::toString).toTypedArray()

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
