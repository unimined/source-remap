package com.replaymod.gradle.remap.classpath

import org.jetbrains.kotlin.cli.common.messages.CompilerMessageSeverity
import org.jetbrains.kotlin.cli.jvm.compiler.KotlinCoreApplicationEnvironment
import org.jetbrains.kotlin.cli.jvm.compiler.report
import org.jetbrains.kotlin.cli.jvm.config.*
import org.jetbrains.kotlin.com.intellij.openapi.vfs.VirtualFile
import org.jetbrains.kotlin.config.CompilerConfiguration
import java.io.File

class TransformedClasspath(
    private val config: CompilerConfiguration,
    private val appEnv: KotlinCoreApplicationEnvironment
) {
    fun contentRootToVirtualFile(root: JvmContentRootBase) = when (root) {
        is JvmClasspathRoot -> if (root.file.isFile) findJarRoot(root.file) else findExistingRoot(root, "Classpath entry")
        is JvmModulePathRoot -> if (root.file.isFile) findJarRoot(root.file) else findExistingRoot(root, "Java module root")
        is JavaSourceRoot -> findExistingRoot(root, "Java source root")
        is VirtualJvmClasspathRoot -> root.file
        else -> throw IllegalStateException("Unexpected root: $root")
    }

    private fun findLocalFile(path: String) = appEnv.localFileSystem.findFileByPath(path)

    private fun findExistingRoot(root: JvmContentRoot, rootDescription: String): VirtualFile? {
        return findLocalFile(root.file.absolutePath).also {
            if (it == null) {
                config.report(
                    CompilerMessageSeverity.STRONG_WARNING,
                    "$rootDescription points to a non-existent location: ${root.file}"
                )
            }
        }
    }

    private fun findJarRoot(file: File) = appEnv.jarFileSystem.findFileByPath("$file!/")
}
