package com.replaymod.gradle.remap.generic

import com.replaymod.gradle.remap.util.TestData
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test

class TestGenericJavaCode {
    @Test
    fun `remaps methods that have synthetic bridges that change the return type`() {
        TestData.remap("""
            @org.spongepowered.asm.mixin.Mixin(a.pkg.A.class)
            abstract class MixinA {
                private void test() {
                    final a.pkg.A value = null;
                    value.aGeneratedSynthetic();
                }
            }
        """.trimIndent()) shouldBe """
            @org.spongepowered.asm.mixin.Mixin(b.pkg.B.class)
            abstract class MixinA {
                private void test() {
                    final b.pkg.B value = null;
                    value.bGeneratedSynthetic();
                }
            }
        """.trimIndent()
    }
}
