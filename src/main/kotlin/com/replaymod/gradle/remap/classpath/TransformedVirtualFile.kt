package com.replaymod.gradle.remap.classpath

import org.jetbrains.kotlin.com.intellij.openapi.vfs.VirtualFile
import java.io.InputStream

class TransformedVirtualFile(private val target: VirtualFile) : VirtualFile() {
    private var transformedCache: ByteArray? = null

    override fun getName() = target.name

    override fun getFileSystem() = TransformedVirtualFileSystem

    override fun getPath() = target.path

    override fun isWritable() = false

    override fun isDirectory() = target.isDirectory

    override fun isValid() = true

    override fun getParent() = target.parent?.let(::TransformedVirtualFile)

    override fun getChildren(): Array<VirtualFile> {
        val children = target.children
        return Array(children.size) { TransformedVirtualFile(children[it]) }
    }

    override fun getOutputStream(requestor: Any?, newModificationStamp: Long, newTimeStamp: Long): Nothing
        = throw UnsupportedOperationException()

    private fun maybeTransform(hint: ByteArray? = null): ByteArray {
        if (transformedCache != null) {
            return transformedCache!!
        }
        val result = TransformedVirtualFileSystem.transform(hint ?: target.contentsToByteArray())
        transformedCache = result
        return result
    }

    override fun contentsToByteArray(): ByteArray {
        val result = target.contentsToByteArray()
        if (!name.endsWith(".class")) {
            return result
        }
        return maybeTransform(result)
    }

    override fun getModificationStamp() = target.modificationStamp

    override fun getTimeStamp() = target.timeStamp

    override fun getLength(): Long {
        if (!name.endsWith(".class")) {
            return target.length
        }
        return maybeTransform().size.toLong()
    }

    override fun refresh(asynchronous: Boolean, recursive: Boolean, postRunnable: Runnable?) = Unit

    override fun getInputStream(): InputStream {
        if (!name.endsWith(".class")) {
            return target.inputStream
        }
        return maybeTransform().inputStream()
    }

    override fun equals(other: Any?) = other is TransformedVirtualFile && target == other.target

    override fun hashCode() = target.hashCode()
}
