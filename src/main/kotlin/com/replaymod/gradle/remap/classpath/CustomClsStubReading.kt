package com.replaymod.gradle.remap.classpath

import com.replaymod.gradle.remap.classpath.CustomClsStubReading.FileContentPair.content
import com.replaymod.gradle.remap.classpath.CustomClsStubReading.FileContentPair.file
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

private typealias InternalFileContentPair = org.jetbrains.kotlin.com.intellij.openapi.util.Pair<VirtualFile, ClassReader>

object CustomClsStubReading {
    object FileContentPair {
        @JvmStatic
        private val internalClass = Class.forName("org.jetbrains.kotlin.com.intellij.psi.impl.compiled.ClsFileImpl\$FileContentPair")
        @JvmStatic
        private val constructor = internalClass.getDeclaredConstructor(VirtualFile::class.java, ClassReader::class.java)

        init {
            constructor.isAccessible = true
        }

        @JvmStatic
        operator fun invoke(file: VirtualFile, content: ClassReader): InternalFileContentPair {
            @Suppress("UNCHECKED_CAST")
            return constructor.newInstance(file, content) as InternalFileContentPair
        }

        @JvmStatic
        val InternalFileContentPair.file: VirtualFile get() = first

        @JvmStatic
        val InternalFileContentPair.content: ClassReader get() = second
    }

    object InnerClassStrategy : InnerClassSourceStrategy<InternalFileContentPair> {
        override fun findInnerClass(innerName: String, outerClass: InternalFileContentPair): InternalFileContentPair? {
            val baseName = outerClass.file.nameWithoutExtension
            val dir = outerClass.file.parent!!
            val innerClass = dir.findChild("$baseName$$innerName.class")
            if (innerClass != null) {
                return FileContentPair(innerClass, ClasspathTransformerManager.transform(outerClass.content))
            }
            return null
        }

        override fun accept(innerClass: InternalFileContentPair, visitor: StubBuildingVisitor<InternalFileContentPair>) {
            try {
                innerClass.content.accept(visitor, ClassReader.SKIP_FRAMES)
            } catch (_: Exception) {
            }
        }
    }

    fun buildFileStub(file: VirtualFile, reader: ClassReader): PsiJavaFileStub? {
        try {
            if (ClassFileViewProvider.isInnerClass(file, reader.b)) {
                return null
            }

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
                    val source = FileContentPair(file, reader)
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
