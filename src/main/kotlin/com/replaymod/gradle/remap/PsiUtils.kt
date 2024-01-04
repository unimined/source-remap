package com.replaymod.gradle.remap

import org.cadixdev.bombe.type.MethodDescriptor
import org.cadixdev.bombe.type.signature.MethodSignature
import org.jetbrains.kotlin.com.intellij.openapi.util.text.StringUtil
import org.jetbrains.kotlin.com.intellij.psi.*
import org.jetbrains.kotlin.com.intellij.psi.util.ClassUtil
import org.jetbrains.kotlin.com.intellij.psi.util.PsiTreeUtil

internal val PsiClass.dollarQualifiedName: String? get() {
    val parent = PsiTreeUtil.getParentOfType<PsiClass>(this, PsiClass::class.java) ?: return qualifiedName
    val parentName = parent.dollarQualifiedName ?: return qualifiedName
    val selfName = name ?: return qualifiedName
    return "$parentName$$selfName"
}

internal val PsiNameValuePair.resolvedLiteralValue: Pair<PsiLiteralExpression, String>?
    get () = value?.resolvedLiteralValue

private val PsiElement.resolvedLiteralValue: Pair<PsiLiteralExpression, String>? get () {
    var value: PsiElement? = this
    while (value is PsiReferenceExpression) {
        val resolved = value.resolve()
        value = when (resolved) {
            is PsiField -> resolved.initializer
            else -> resolved
        }
    }
    val literal = value as? PsiLiteralExpression ?: return null
    return Pair(literal, StringUtil.unquoteString(literal.text))
}

internal val PsiAnnotationMemberValue.resolvedLiteralValues: List<Pair<PsiLiteralExpression, String>>
    get () = when (this) {
        is PsiArrayInitializerMemberValue -> initializers.mapNotNull { it.resolvedLiteralValue }
        else -> listOfNotNull(resolvedLiteralValue)
    }

internal inline fun <T> Array<T>.moreThan(n: Int, predicate: (T) -> Boolean): Boolean {
    require(n >= 0)
    var count = 0
    for (t in this) {
        if (predicate(t) && ++count > n) {
            return true
        }
    }
    return false
}

internal object PsiUtils {
    fun getSignature(method: PsiMethod): MethodSignature = MethodSignature(method.name, getDescriptor(method))

    private fun getDescriptor(method: PsiMethod): MethodDescriptor {
        return MethodDescriptor.of(ClassUtil.getAsmMethodSignature(method))
    }
}
