package com.replaymod.gradle.remap.classpath

import org.jetbrains.org.objectweb.asm.ClassReader
import org.jetbrains.org.objectweb.asm.ClassVisitor
import org.jetbrains.org.objectweb.asm.ClassWriter

object ClasspathTransformerManager {
    val transformers = mutableListOf<ClasspathTransformer>()

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
