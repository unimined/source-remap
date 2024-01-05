package com.replaymod.gradle.remap.classpath

import org.jetbrains.org.objectweb.asm.ClassVisitor
import org.jetbrains.org.objectweb.asm.MethodVisitor
import org.jetbrains.org.objectweb.asm.ModuleVisitor
import org.jetbrains.org.objectweb.asm.Opcodes

val desynthesizeTransformer = ClasspathTransformer { parent ->
    fun desynthesize(access: Int) = access and Opcodes.ACC_SYNTHETIC.inv()

    object : ClassVisitor(Opcodes.ASM9, parent) {
        override fun visit(
            version: Int,
            access: Int,
            name: String?,
            signature: String?,
            superName: String?,
            interfaces: Array<out String>?
        ) = super.visit(version, desynthesize(access), name, signature, superName, interfaces)

        override fun visitField(
            access: Int,
            name: String?,
            descriptor: String?,
            signature: String?,
            value: Any?
        ) = super.visitField(desynthesize(access), name, descriptor, signature, value)

        override fun visitMethod(
            access: Int,
            name: String?,
            descriptor: String?,
            signature: String?,
            exceptions: Array<out String>?
        ): MethodVisitor {
            var newAccess = desynthesize(access)
            // We store the synthetic flag as strictfp so that smartMultiResolve can detect if a method was initially synthetic
            newAccess = if ((access and Opcodes.ACC_SYNTHETIC) != 0) {
                newAccess or Opcodes.ACC_STRICT
            } else {
                newAccess and Opcodes.ACC_STRICT.inv()
            }
            return object : MethodVisitor(api, super.visitMethod(newAccess, name, descriptor, signature, exceptions)) {
                override fun visitParameter(name: String?, access: Int) =
                    super.visitParameter(name, desynthesize(access))
            }
        }

        override fun visitModule(name: String?, access: Int, version: String?) =
            object : ModuleVisitor(api, super.visitModule(name, desynthesize(access), version)) {
                override fun visitExport(packaze: String?, access: Int, vararg modules: String?) =
                    super.visitExport(packaze, desynthesize(access), *modules)

                override fun visitOpen(packaze: String?, access: Int, vararg modules: String?) =
                    super.visitOpen(packaze, desynthesize(access), *modules)

                override fun visitRequire(module: String?, access: Int, version: String?) =
                    super.visitRequire(module, desynthesize(access), version)
            }

        override fun visitInnerClass(name: String?, outerName: String?, innerName: String?, access: Int) =
            super.visitInnerClass(name, outerName, innerName, desynthesize(access))
    }
}
