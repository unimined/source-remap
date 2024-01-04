package com.replaymod.gradle.remap

import org.jetbrains.kotlin.com.intellij.psi.PsiClass
import org.jetbrains.kotlin.com.intellij.psi.impl.compiled.ClsAnonymousClass
import org.jetbrains.kotlin.com.intellij.psi.impl.compiled.ClsClassImpl
import org.jetbrains.kotlin.com.intellij.psi.impl.java.stubs.ClsStubPsiFactory
import org.jetbrains.kotlin.com.intellij.psi.impl.java.stubs.JavaStubElementTypes
import org.jetbrains.kotlin.com.intellij.psi.impl.java.stubs.PsiClassStub
import org.jetbrains.kotlin.com.intellij.psi.impl.java.stubs.impl.PsiClassStubImpl

object CustomClsStubPsiFactory : ClsStubPsiFactory() {
    override fun createClass(stub: PsiClassStub<*>) = if (stub is PsiClassStubImpl && stub.isAnonymousInner) {
        CustomClsAnonymousClass(stub)
    } else {
        CustomClsClassImpl(stub)
    }

    class CustomClsClassImpl(stub: PsiClassStub<*>) : ClsClassImpl(stub) {
        override fun getOwnInnerClasses() = stub.getChildrenByType(JavaStubElementTypes.CLASS, PsiClass.ARRAY_FACTORY).toList()
    }

    class CustomClsAnonymousClass(stub: PsiClassStub<*>) : ClsAnonymousClass(stub) {
        override fun getOwnInnerClasses() = stub.getChildrenByType(JavaStubElementTypes.CLASS, PsiClass.ARRAY_FACTORY).toList()
    }
}
