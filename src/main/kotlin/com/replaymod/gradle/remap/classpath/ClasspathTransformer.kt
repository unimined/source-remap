package com.replaymod.gradle.remap.classpath

import org.jetbrains.org.objectweb.asm.ClassVisitor

class ClasspathTransformer(
    val computeFlags: Int = 0,
    val canCopyConstantPool: Boolean = true,
    val visitor: (parent: ClassVisitor) -> ClassVisitor?
)
