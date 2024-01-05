package com.replaymod.gradle.remap.misc

import com.replaymod.gradle.remap.util.TestData
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test

class TestMiscJavaCode {
    @Test
    fun `remaps methods that have synthetic bridges that change the return type`() {
        TestData.remap("""
            class Test {
                void test() {
                    final a.pkg.A value = null;
                    value.aGeneratedSynthetic();
                }
            }
        """.trimIndent()) shouldBe """
            class Test {
                void test() {
                    final b.pkg.B value = null;
                    value.bGeneratedSynthetic();
                }
            }
        """.trimIndent()
    }

    @Test
    fun `remaps methods that are called on the return value of another method when using synthetic bridges that change the return type`() {
        TestData.remap("""
            class Test {
                void test() {
                    final a.pkg.A value = null;
                    value.aGeneratedSynthetic().aGeneratedSynthetic().aGeneratedSynthetic();
                }
            }
        """.trimIndent()) shouldBe """
            class Test {
                void test() {
                    final b.pkg.B value = null;
                    value.bGeneratedSynthetic().bGeneratedSynthetic().bGeneratedSynthetic();
                }
            }
        """.trimIndent()
    }

    @Test
    fun `remaps synthetic bridges that change the return type inside inner classes inside inner classes`() {
        TestData.remap("""
            class Test {
                void test() {
                    a.pkg.A.InnerC.InnerD value = null;
                    value.getA();
                }
            }
        """.trimIndent()) shouldBe """
            class Test {
                void test() {
                    b.pkg.B.InnerC.InnerD value = null;
                    value.getB();
                }
            }
        """.trimIndent()
    }
}
