package com.replaymod.gradle.remap

import org.cadixdev.bombe.type.MethodDescriptor
import org.cadixdev.bombe.type.signature.MethodSignature
import org.jetbrains.kotlin.com.intellij.openapi.util.text.StringUtil
import org.jetbrains.kotlin.com.intellij.psi.*
import org.jetbrains.kotlin.com.intellij.psi.util.ClassUtil
import org.jetbrains.kotlin.com.intellij.psi.util.PsiTreeUtil
import org.jetbrains.kotlin.com.intellij.psi.util.TypeConversionUtil

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

fun PsiMethod.findAllSuperMethods() = findAllSuperMethods(this, mutableSetOf(this), null)

private fun findAllSuperMethods(
    method: PsiMethod,
    set: MutableSet<PsiMethod>,
    guard: MutableSet<PsiMethod>?
): Iterator<PsiMethod> = iterator {
    if (guard != null && !guard.add(method)) return@iterator
    val supers = method.findSuperMethods()

    if (set.add(method)) {
        yield(method)
    }

    var useGuard = guard
    for (superMethod in supers) {
        if (useGuard == null) {
            useGuard = mutableSetOf(method)
        }
        yieldAll(findAllSuperMethods(superMethod, set, useGuard))
    }
}

fun PsiJavaCodeReferenceElement.smartMultiResolve(): PsiElement? {
    val result = multiResolve(false)
    if (result.isEmpty()) {
        return null
    }
    if (result.size == 1) {
        return result[0].element
    }
    val methods = result.mapNotNull { it.element as? PsiMethod }
    if (methods.size < result.size) {
        return null // Only do heuristic search on methods
    }
    val nonSynthetic = methods.filter {
        !it.modifierList.hasModifierProperty(PsiModifier.STRICTFP)
    }
    if (nonSynthetic.size == 1) {
        return nonSynthetic[0]
    }
    val heuristicSearch = nonSynthetic.ifEmpty { methods }
    var lowestMethodSoFar = heuristicSearch[0]
    var lowestReturnSoFar = lowestMethodSoFar.returnType
    for (i in 1..heuristicSearch.lastIndex) {
        val method = heuristicSearch[i]
        val returnType = method.returnType ?: continue
        if (lowestReturnSoFar == null) {
            lowestMethodSoFar = method
            lowestReturnSoFar = returnType
            continue
        }
        if (lowestReturnSoFar.isAssignableFrom(returnType) && lowestReturnSoFar != returnType) {
            lowestMethodSoFar = method
            lowestReturnSoFar = returnType
            continue
        }
        // In the future, maybe also analyze based on number of supertypes/hierarchy depth?
    }
    return lowestMethodSoFar
}

fun PsiMethod.matchesDescriptor(method: PsiMethod): Boolean {
    if (TypeConversionUtil.erasure(returnType) != TypeConversionUtil.erasure(method.returnType)) {
        return false
    }
    val parameters = parameterList.parameters
    val otherParameters = method.parameterList.parameters
    if (parameters.size != otherParameters.size) {
        return false
    }
    for (i in parameters.indices) {
        if (TypeConversionUtil.erasure(parameters[i].type) != TypeConversionUtil.erasure(otherParameters[i].type)) {
            return false
        }
    }
    return true
}

internal object PsiUtils {
    fun getSignature(method: PsiMethod): MethodSignature = MethodSignature(method.name, getDescriptor(method))

    private fun getDescriptor(method: PsiMethod): MethodDescriptor {
        return MethodDescriptor.of(ClassUtil.getAsmMethodSignature(method))
    }
}
