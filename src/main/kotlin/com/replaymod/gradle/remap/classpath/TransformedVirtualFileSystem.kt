package com.replaymod.gradle.remap.classpath

import org.jetbrains.kotlin.com.intellij.core.CoreApplicationEnvironment
import org.jetbrains.kotlin.com.intellij.openapi.vfs.DeprecatedVirtualFileSystem
import org.jetbrains.kotlin.com.intellij.openapi.vfs.VirtualFileSystem
import org.jetbrains.kotlin.com.intellij.util.KeyedLazyInstanceEP
import org.jetbrains.org.objectweb.asm.ClassReader
import org.jetbrains.org.objectweb.asm.ClassVisitor
import org.jetbrains.org.objectweb.asm.ClassWriter

object TransformedVirtualFileSystem : DeprecatedVirtualFileSystem() /* AbstractVirtualFileSystem? */ {
    val keyedInstance = object : KeyedLazyInstanceEP<VirtualFileSystem>() {
        init {
            key = protocol
            implementationClass = TransformedVirtualFileSystem::class.java.name
        }

        override fun getInstance() = TransformedVirtualFileSystem
    }

    lateinit var applicationEnvironment: CoreApplicationEnvironment

    val transformers = mutableListOf<ClasspathTransformer>()

    override fun getProtocol() = "remap-transformed"

    override fun findFileByPath(path: String) =
        if ("!/" in path) {
            applicationEnvironment.jarFileSystem
        } else {
            applicationEnvironment.localFileSystem
        }.findFileByPath(path)?.let(::TransformedVirtualFile)

    override fun refresh(asynchronous: Boolean) = Unit

    override fun refreshAndFindFileByPath(path: String) = findFileByPath(path)

    fun transform(input: ByteArray): ByteArray {
        if (transformers.isEmpty()) {
            return input
        }

        var computeFlags = 0
        var copyConstantPool = true
        for (transformer in transformers) {
            computeFlags = computeFlags or transformer.computeFlags
            copyConstantPool = copyConstantPool && transformer.canCopyConstantPool
        }

        val reader = ClassReader(input)
        val writer = ClassWriter(if (copyConstantPool) reader else null, computeFlags)

        var visitor: ClassVisitor = writer
        for (transformer in transformers.asReversed()) {
            visitor = transformer.visitor(visitor) ?: continue
        }
        if (visitor === writer) {
            return input
        }

        reader.accept(visitor, 0)
        return writer.toByteArray()
    }
}
