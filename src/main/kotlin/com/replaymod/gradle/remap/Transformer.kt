package com.replaymod.gradle.remap

import com.replaymod.gradle.remap.classpath.ClasspathTransformerManager
import com.replaymod.gradle.remap.classpath.CustomClsStubReading
import com.replaymod.gradle.remap.classpath.desynthesizeTransformer
import com.replaymod.gradle.remap.classpath.keepInnerClassIndexTransformer
import org.cadixdev.lorenz.MappingSet
import org.cadixdev.lorenz.io.MappingFormats
import org.cadixdev.lorenz.model.MethodMapping
import org.jetbrains.kotlin.cli.common.CLIConfigurationKeys
import org.jetbrains.kotlin.cli.common.config.KotlinSourceRoot
import org.jetbrains.kotlin.cli.common.messages.MessageRenderer
import org.jetbrains.kotlin.cli.common.messages.PrintingMessageCollector
import org.jetbrains.kotlin.cli.jvm.compiler.EnvironmentConfigFiles
import org.jetbrains.kotlin.cli.jvm.compiler.KotlinCoreEnvironment
import org.jetbrains.kotlin.cli.jvm.compiler.setupIdeaStandaloneExecution
import org.jetbrains.kotlin.cli.jvm.config.JavaSourceRoot
import org.jetbrains.kotlin.cli.jvm.config.JvmClasspathRoot
import org.jetbrains.kotlin.cli.jvm.modules.CoreJrtFileSystem
import org.jetbrains.kotlin.com.intellij.codeInsight.CustomExceptionHandler
import org.jetbrains.kotlin.com.intellij.ide.highlighter.JavaClassFileType
import org.jetbrains.kotlin.com.intellij.mock.MockProject
import org.jetbrains.kotlin.com.intellij.openapi.Disposable
import org.jetbrains.kotlin.com.intellij.openapi.extensions.ExtensionPoint
import org.jetbrains.kotlin.com.intellij.openapi.extensions.Extensions
import org.jetbrains.kotlin.com.intellij.openapi.util.Disposer
import org.jetbrains.kotlin.com.intellij.openapi.vfs.StandardFileSystems
import org.jetbrains.kotlin.com.intellij.openapi.vfs.VirtualFileManager
import org.jetbrains.kotlin.com.intellij.openapi.vfs.local.CoreLocalFileSystem
import org.jetbrains.kotlin.com.intellij.psi.PsiManager
import org.jetbrains.kotlin.com.intellij.psi.PsiMethod
import org.jetbrains.kotlin.com.intellij.psi.compiled.ClassFileDecompilers
import org.jetbrains.kotlin.com.intellij.psi.impl.compiled.ClassFileStubBuilder
import org.jetbrains.kotlin.com.intellij.psi.impl.java.stubs.impl.PsiJavaFileStubImpl
import org.jetbrains.kotlin.com.intellij.psi.stubs.BinaryFileStubBuilders
import org.jetbrains.kotlin.com.intellij.psi.stubs.Stub
import org.jetbrains.kotlin.com.intellij.util.cls.ClsFormatException
import org.jetbrains.kotlin.com.intellij.util.indexing.FileContent
import org.jetbrains.kotlin.config.CommonConfigurationKeys
import org.jetbrains.kotlin.config.CompilerConfiguration
import org.jetbrains.kotlin.config.JVMConfigurationKeys
import org.jetbrains.kotlin.psi.KtFile
import org.jetbrains.kotlin.utils.PathUtil
import java.io.*
import java.net.URI
import java.nio.charset.StandardCharsets
import java.nio.file.*
import java.util.*
import java.util.stream.Collectors
import java.util.zip.ZipFile
import java.util.zip.ZipOutputStream

class Transformer(private val map: MappingSet) {
    var classpath: Collection<Path> = listOf()
    var remappedClasspath: Array<String>? = null
    var jdkHome: File = File(System.getProperty("java.home"))
    var remappedJdkHome: File? = null
    var patternAnnotation: String? = null
    var manageImports = false

    @Throws(IOException::class)
    fun remap(sources: Map<Path, Map<String, () -> InputStream>>, writer: (Path, String) -> OutputStream): Map<Pair<Path, String>, List<Pair<Int, String>>> = remap(sources, emptyMap(), writer)


    @Throws(IOException::class)
    fun remap(sources: Map<Path, Map<String, () -> InputStream>>, processedSources: Map<String, () -> InputStream>, writer: (Path, String) -> OutputStream): Map<Pair<Path, String>, List<Pair<Int, String>>> {
        val tmpDir = Files.createTempDirectory("remap")
        val processedTmpDir = Files.createTempDirectory("remap-processed")
        val disposable = Disposer.newDisposable()
        try {
            for ((root, unit) in sources.flatMap { entry -> entry.value.entries.map { entry.key to it } }) {
                val unitName = unit.key
                val source = unit.value
                val path = tmpDir.resolve(root.fileName).resolve(unitName)
                Files.createDirectories(path.parent)
                Files.copy(source(), path, StandardCopyOption.REPLACE_EXISTING)

                val processedSource = processedSources[unitName] ?: source
                val processedPath = processedTmpDir.resolve(unitName)
                Files.createDirectories(processedPath.parent)
                Files.copy(processedSource(), processedPath, StandardCopyOption.REPLACE_EXISTING)
            }

            val config = CompilerConfiguration()
            config.put(CommonConfigurationKeys.MODULE_NAME, "main")
            config.setupJdk(jdkHome)
            config.addAll(CLIConfigurationKeys.CONTENT_ROOTS, sources.keys.map { root -> JavaSourceRoot(tmpDir.resolve(root.fileName).toFile(), "") })
            config.addAll(CLIConfigurationKeys.CONTENT_ROOTS, sources.keys.map { root -> KotlinSourceRoot(tmpDir.resolve(root.fileName).toAbsolutePath().toString(), false, null) })
            config.addAll(CLIConfigurationKeys.CONTENT_ROOTS, classpath.map { JvmClasspathRoot(it.toFile()) }) // This can be extended to use Paths fully, but there's no need atm
            config.put(CLIConfigurationKeys.MESSAGE_COLLECTOR_KEY, PrintingMessageCollector(System.err, MessageRenderer.GRADLE_STYLE, true))

            // Our PsiMapper only works with the PSI tree elements, not with the faster (but kotlin-specific) classes
            config.put(JVMConfigurationKeys.USE_PSI_CLASS_FILES_READING, true)

            setupIdeaStandaloneExecution()
            val appEnv = KotlinCoreEnvironment.getOrCreateApplicationEnvironmentForProduction(disposable, config)
            ClasspathTransformerManager.transformers += listOf(desynthesizeTransformer, keepInnerClassIndexTransformer)

            BinaryFileStubBuilders.INSTANCE.findSingle(JavaClassFileType.INSTANCE)?.let {
                BinaryFileStubBuilders.INSTANCE.removeExplicitExtension(JavaClassFileType.INSTANCE, it)
            }
            BinaryFileStubBuilders.INSTANCE.addExplicitExtension(
                JavaClassFileType.INSTANCE,
                object : ClassFileStubBuilder() {
                    override fun buildStubTree(
                        fileContent: FileContent,
                        decompiler: ClassFileDecompilers.Full?
                    ): Stub? {
                        return if (decompiler == null) null else fileContent.file.computeWithPreloadedContentHint(fileContent.content) {
                            try {
                                val stub = CustomClsStubReading.buildFileStub(
                                    fileContent.file, ClasspathTransformerManager.transform(fileContent.content)
                                )
                                if (stub is PsiJavaFileStubImpl) {
                                    stub.psiFactory = CustomClsStubPsiFactory
                                }
                                stub
                            } catch (_: ClsFormatException) {
                                null
                            }
                        }
                    }
                },
                disposable
            )

            val projectEnv = KotlinCoreEnvironment.ProjectEnvironment(disposable, appEnv, config)
            val environment = KotlinCoreEnvironment.createForProduction(
                projectEnv, config, EnvironmentConfigFiles.JVM_CONFIG_FILES
            )
            val rootArea = Extensions.getRootArea()
            synchronized(rootArea) {
                if (!rootArea.hasExtensionPoint(CustomExceptionHandler.KEY)) {
                    rootArea.registerExtensionPoint(CustomExceptionHandler.KEY.name, CustomExceptionHandler::class.java.name, ExtensionPoint.Kind.INTERFACE)
                }
            }

            val project = environment.project as MockProject
            val psiManager = PsiManager.getInstance(project)
            val vfs = VirtualFileManager.getInstance().getFileSystem(StandardFileSystems.FILE_PROTOCOL) as CoreLocalFileSystem
            val virtualFiles = sources.entries.flatMap { entry -> entry.value.map { it.key to vfs.findFileByIoFile(tmpDir.resolve(entry.key.fileName).resolve(it.key).toFile())!! } }.toMap()
            val psiFiles = virtualFiles.mapValues { psiManager.findFile(it.value)!! }
            val ktFiles = psiFiles.values.filterIsInstance<KtFile>()

            val analysis = analyze1923(environment, ktFiles)

            val remappedEnv = remappedClasspath?.let {
                setupRemappedProject(disposable, it, processedTmpDir)
            }

            val patterns = patternAnnotation?.let { annotationFQN ->
                val patterns = PsiPatterns(annotationFQN)
                val annotationName = annotationFQN.substring(annotationFQN.lastIndexOf('.') + 1)
                for ((root, unit) in sources.flatMap { entry -> entry.value.entries.map { entry.key to it } }) {
                    val unitName = unit.key
                    val source = unit.value().readBytes().decodeToString()
                    if (!source.contains(annotationName)) continue
                    try {
                        val patternFile = vfs.findFileByIoFile(tmpDir.resolve(root.fileName).resolve(unitName).toFile())!!
                        val patternPsiFile = psiManager.findFile(patternFile)!!
                        patterns.read(patternPsiFile, processedSources[unitName]!!().readBytes().decodeToString())
                    } catch (e: Exception) {
                        throw RuntimeException("Failed to read patterns from file \"$unitName\".", e)
                    }
                }
                patterns
            }

            val autoImports = if (manageImports && remappedEnv != null) {
                AutoImports(remappedEnv)
            } else {
                null
            }

            val results = mutableMapOf<Pair<Path, String>, List<Pair<Int, String>>>()
            val methodCache = mutableMapOf<PsiMethod, Optional<MethodMapping>>()
            for ((root, unit) in sources.flatMap { entry -> entry.value.entries.map { entry.key to it } }) {
                val unitName = unit.key
                val file = vfs.findFileByIoFile(tmpDir.resolve(root.fileName).resolve(unitName).toFile())!!
                val psiFile = psiManager.findFile(file)!!

                var (text, errors) = try {
                    PsiMapper(map, remappedEnv?.project, psiFile, analysis.bindingContext, patterns, methodCache).remapFile()
                } catch (e: Exception) {
                    throw RuntimeException("Failed to map file \"$unitName\".", e)
                }

                if (autoImports != null && "/* remap: no-manage-imports */" !in text) {
                    val processedText = processedSources[unitName]?.let { it().readBytes().decodeToString() } ?: text
                    text = autoImports.apply(psiFile, text, processedText)
                }

                // write text to output path
                writer(root, unitName).use { os ->
                    os.write(text.toByteArray(StandardCharsets.UTF_8))
                }

                results[root to unitName] = errors
            }
            return results
        } finally {
            Files.walk(tmpDir).sorted(Comparator.reverseOrder()).forEach { Files.delete(it) }
            Files.walk(processedTmpDir).sorted(Comparator.reverseOrder()).forEach { Files.delete(it) }
            Disposer.dispose(disposable)
        }
    }

    private fun CompilerConfiguration.setupJdk(jdkHome: File) {
        put(JVMConfigurationKeys.JDK_HOME, jdkHome)

        if (!CoreJrtFileSystem.isModularJdk(jdkHome)) {
            val roots = PathUtil.getJdkClassesRoots(jdkHome).map { JvmClasspathRoot(it, true) }
            addAll(CLIConfigurationKeys.CONTENT_ROOTS, 0, roots)
        }
    }

    private fun setupRemappedProject(disposable: Disposable, classpath: Array<String>, sourceRoot: Path): KotlinCoreEnvironment {
        val config = CompilerConfiguration()
        (remappedJdkHome ?: jdkHome).let { config.setupJdk(it) }
        config.put(CommonConfigurationKeys.MODULE_NAME, "main")
        config.addAll(CLIConfigurationKeys.CONTENT_ROOTS, classpath.map { JvmClasspathRoot(File(it)) })
        if (manageImports) {
            config.add(CLIConfigurationKeys.CONTENT_ROOTS, JavaSourceRoot(sourceRoot.toFile(), ""))
        }
        config.put(CLIConfigurationKeys.MESSAGE_COLLECTOR_KEY, PrintingMessageCollector(System.err, MessageRenderer.GRADLE_STYLE, true))

        val environment = KotlinCoreEnvironment.createForProduction(
            disposable,
            config,
            EnvironmentConfigFiles.JVM_CONFIG_FILES
        )
        analyze1923(environment, emptyList())
        return environment
    }


    companion object {

        fun scanFolder(folder: Path): Map<String, () -> InputStream> {
            return Files.walk(folder)
                .filter { Files.isRegularFile(it) }
                .filter { it.toString().endsWith(".java") || it.toString().endsWith(".kt") }
                .map { it.toString() to { Files.newInputStream(it) } }
                .collect(Collectors.toList()).toMap()
        }

        // <mappings.srg> <classpath> <inputPaths> <outputPaths>
        @Throws(IOException::class)
        @JvmStatic
        fun main(args: Array<String>) {
            val mappings: MappingSet = MappingFormats.SRG.read(File(args[0]).toPath())
            val classpath = args[1].split(File.pathSeparatorChar)
            val inputs = args[2].split(File.pathSeparatorChar).map { File(it).toPath() }
            val outputs = args[3].split(File.pathSeparatorChar).map { File(it).toPath() }
            val transformer = Transformer(mappings)

            transformer.classpath = classpath.map(Paths::get)

            val closeLater = mutableListOf<Closeable>()

            try {

                val sources: Map<Path, Map<String, () -> InputStream>> = inputs.associateWith { root ->
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
                for (output in outputs) {
                    if (Files.isDirectory(output)) {
                        Files.walk(output).sorted(Comparator.reverseOrder()).forEach { Files.delete(it) }
                    } else if (Files.isRegularFile(output)) {
                        Files.delete(output)
                    }
                }

                val results = transformer.remap(sources) { root, unit ->
                    if (root.toString().endsWith(".jar") || root.toString().endsWith(".zip")) {
                        val outputPath = outputs[inputs.indexOf(root)]
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
                    val output = outputs[inputs.indexOf(root)].resolve(unit)
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

    }

}
