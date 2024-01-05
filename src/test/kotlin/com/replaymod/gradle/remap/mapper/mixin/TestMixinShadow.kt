package com.replaymod.gradle.remap.mapper.mixin

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


    @Test
    fun `resolve shadow names in anonymous classes`() {
        TestData.remap("""
            @org.spongepowered.asm.mixin.Mixin(targets = "a.pkg.A$2")
            abstract class MixinA {
                @org.spongepowered.asm.mixin.Shadow
                protected abstract void aMethodAnon();
                private void test() { this.aMethodAnon(); }
            }
        """.trimIndent()) shouldBe """
            @org.spongepowered.asm.mixin.Mixin(targets = "b.pkg.B$2")
            abstract class MixinA {
                @org.spongepowered.asm.mixin.Shadow
                protected abstract void bMethodAnon();
                private void test() { this.bMethodAnon(); }
            }
        """.trimIndent()
    }

    @Test
    fun `automatically add this(dot) for conflicts with local variables`() {
        TestData.remap("""
            @org.spongepowered.asm.mixin.Mixin(a.pkg.A.class)
            abstract class MixinA {
                @org.spongepowered.asm.mixin.Shadow
                private a.pkg.A a;
                @org.spongepowered.asm.mixin.Shadow
                private int aField;
                private void test() {
                    a = null;
                    int bField = 0;
                    aField = 1;
                    bField = 1;
                }
            }
        """.trimIndent()) shouldBe """
            @org.spongepowered.asm.mixin.Mixin(b.pkg.B.class)
            abstract class MixinA {
                @org.spongepowered.asm.mixin.Shadow
                private b.pkg.B b;
                @org.spongepowered.asm.mixin.Shadow
                private int bField;
                private void test() {
                    b = null;
                    int bField = 0;
                    this.bField = 1;
                    bField = 1;
                }
            }
        """.trimIndent()
    }

    @Test
    fun `remaps shadowed methods with array arguments`() {
        TestData.remap("""
            @org.spongepowered.asm.mixin.Mixin(a.pkg.A.class)
            abstract class MixinA {
                @org.spongepowered.asm.mixin.Shadow
                private int aMethodWithAnArrayArgument(a.pkg.AInterface[] arguments);
                private void test() {
                    aMethodWithAnArrayArgument(new a.pkg.AInterface[1]);
                }
            }
        """.trimIndent()) shouldBe """
            @org.spongepowered.asm.mixin.Mixin(b.pkg.B.class)
            abstract class MixinA {
                @org.spongepowered.asm.mixin.Shadow
                private int bMethodWithAnArrayArgument(b.pkg.BInterface[] arguments);
                private void test() {
                    bMethodWithAnArrayArgument(new b.pkg.BInterface[1]);
                }
            }
        """.trimIndent()
    }

    @Test
    fun `remaps local classes inside inner classes`() {
        TestData.remap("""
            @org.spongepowered.asm.mixin.Mixin(targets = "a.pkg.A${'$'}InnerE${'$'}1ALocalClass")
            class MixinA {
                @org.spongepowered.asm.mixin.Shadow
                private void aMethod();
                private void test() {
                    aMethod();
                }
            }
        """.trimIndent()) shouldBe """
            @org.spongepowered.asm.mixin.Mixin(targets = "b.pkg.B${'$'}InnerE${'$'}1BLocalClass")
            class MixinA {
                @org.spongepowered.asm.mixin.Shadow
                private void bMethod();
                private void test() {
                    bMethod();
                }
            }
        """.trimIndent()
    }
}