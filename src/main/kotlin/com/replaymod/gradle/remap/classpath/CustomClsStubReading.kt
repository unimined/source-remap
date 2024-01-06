package com.replaymod.gradle.remap.classpath

import org.jetbrains.kotlin.com.intellij.openapi.progress.ProcessCanceledException
import org.jetbrains.kotlin.com.intellij.openapi.vfs.VirtualFile
import org.jetbrains.kotlin.com.intellij.pom.java.LanguageLevel
import org.jetbrains.kotlin.com.intellij.psi.ClassFileViewProvider
import org.jetbrains.kotlin.com.intellij.psi.impl.compiled.*
import org.jetbrains.kotlin.com.intellij.psi.impl.java.stubs.PsiJavaFileStub
import org.jetbrains.kotlin.com.intellij.psi.impl.java.stubs.impl.PsiJavaFileStubImpl
import org.jetbrains.kotlin.com.intellij.util.BitUtil
import org.jetbrains.kotlin.com.intellij.util.cls.ClsFormatException
import org.jetbrains.org.objectweb.asm.ClassReader
import org.jetbrains.org.objectweb.asm.Opcodes
import java.io.IOException

object CustomClsStubReading {
    class FileContentPair(val file: VirtualFile, val content: ByteArray) {
        override fun toString() = file.toString()
    }

    object InnerClassStrategy : InnerClassSourceStrategy<FileContentPair> {
        override fun findInnerClass(innerName: String, outerClass: FileContentPair): FileContentPair? {
            val baseName = outerClass.file.nameWithoutExtension
            val dir = outerClass.file.parent!!
            val innerClass = dir.findChild("$baseName$$innerName.class")
            if (innerClass != null) {
                try {
                    val origBytes = innerClass.contentsToByteArray(false)
                    return FileContentPair(innerClass, ClasspathTransformerManager.transform(origBytes))
                } catch (_: IOException) {
                }
            }
            return null
        }

        override fun accept(innerClass: FileContentPair, visitor: StubBuildingVisitor<FileContentPair>) {
            try {
                ClassReader(innerClass.content).accept(visitor, ClassReader.SKIP_FRAMES)
            } catch (_: Exception) {
            }
        }
    }

    fun buildFileStub(file: VirtualFile, bytes: ByteArray): PsiJavaFileStub? {
        try {
            if (ClassFileViewProvider.isInnerClass(file, bytes)) {
                return null
            }

            val reader = ClassReader(bytes)
            val className = file.nameWithoutExtension
            val internalName = reader.className
            val module = internalName == "module-info" && BitUtil.isSet(reader.access, Opcodes.ACC_MODULE)
            var level = ClsParsingUtil.getJdkVersionByBytecode(reader.readUnsignedShort(6))?.maxLanguageLevel
            if (
                level != null && level.isAtLeast(LanguageLevel.JDK_11) &&
                ClsParsingUtil.isPreviewLevel(reader.readUnsignedShort(4))
            ) {
                level = level.previewLevel ?: LanguageLevel.HIGHEST
            }

            if (module) {
                val stub = PsiJavaFileStubImpl(null, "", level, true)
                val visitor = ModuleStubBuildingVisitor(stub)
                reader.accept(visitor, ClassReader.SKIP_FRAMES)
                if (visitor.result != null) {
                    return stub
                }
            } else {
                val stub = PsiJavaFileStubImpl(
                    null,
                    internalName.substringBeforeLast('/', "").replace('/', '.'),
                    level, true
                )
                try {
                    val source = FileContentPair(file, bytes)
                    val visitor = StubBuildingVisitor(source, InnerClassStrategy, stub, 0, className)
                    reader.accept(visitor, ClassReader.SKIP_FRAMES)
                    if (visitor.result != null) {
                        return stub
                    }
                } catch (_: OutOfOrderInnerClassException) {
                }
            }

            return null
        } catch (e: ProcessCanceledException) {
            return null
        } catch (e: Throwable) {
            throw ClsFormatException("${file.path}: ${e.message}", e)
        }
    }
}
