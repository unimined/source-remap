package com.replaymod.gradle.remap.misc

import com.replaymod.gradle.remap.util.TestData
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test

class TestMiscJavaCode {
    @Test
    fun `remaps methods that have synthetic bridges that change the return type`() {
        TestData.remap("""
            public class Test {
                public static void test() {
                    final a.pkg.A value = null;
                    value.aGeneratedSynthetic();
                }
            }
        """.trimIndent()) shouldBe """
            public class Test {
                public static void test() {
                    final b.pkg.B value = null;
                    value.bGeneratedSynthetic();
                }
            }
        """.trimIndent()
    }
}
