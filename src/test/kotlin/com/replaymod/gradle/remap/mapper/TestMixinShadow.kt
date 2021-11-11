package com.replaymod.gradle.remap.mapper

import com.replaymod.gradle.remap.util.TestData
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test

class TestMixinShadow {
    @Test
    fun `remaps shadow method and references to it`() {
        TestData.remap("""
            @org.spongepowered.asm.mixin.Mixin(a.pkg.A.class)
            abstract class MixinA {
                @org.spongepowered.asm.mixin.Shadow
                protected abstract a.pkg.A getA();
                private void test() { this.getA(); }
            }
        """.trimIndent()) shouldBe """
            @org.spongepowered.asm.mixin.Mixin(b.pkg.B.class)
            abstract class MixinA {
                @org.spongepowered.asm.mixin.Shadow
                protected abstract b.pkg.B getB();
                private void test() { this.getB(); }
            }
        """.trimIndent()
    }
}