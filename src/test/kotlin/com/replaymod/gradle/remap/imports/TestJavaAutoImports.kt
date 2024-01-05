package com.replaymod.gradle.remap.imports

import com.replaymod.gradle.remap.util.TestData
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Disabled
import org.junit.jupiter.api.Test
import java.io.ByteArrayOutputStream
import java.nio.file.Paths

class TestJavaAutoImports {

    @Test
    fun `should remove unused imports`() {
        TestData.remap("""
            package test;
            
            import java.util.ArrayList;
            
            class Test {
            }
        """.trimIndent()) shouldBe """
            package test;
            
            
            
            class Test {
            }
        """.trimIndent()
    }

    @Test
    fun `should add unambiguous missing imports from JDK`() {
        TestData.remap("""
            package test;
            
            
            
            class Test extends ArrayList {
            }
        """.trimIndent()) shouldBe """
            package test;
            
            import java.util.ArrayList;
            
            class Test extends ArrayList {
            }
        """.trimIndent()
    }

    @Test
    fun `should add unambiguous missing imports from remapped classpath`() {
        TestData.remap("""
            package test;
            
            
            
            class Test extends B {
            }
        """.trimIndent()) shouldBe """
            package test;
            
            import b.pkg.B;
            
            class Test extends B {
            }
        """.trimIndent()
    }

    @Test
    fun `should not add unambiguous missing imports from original classpath`() {
        TestData.remap("""
            package test;
            
            
            
            class Test extends A {
            }
        """.trimIndent()) shouldBe """
            package test;
            
            
            
            class Test extends A {
            }
        """.trimIndent()
    }

    @Test
    fun `should add unambiguous missing imports from the same source set`() {
        val original = """
            package test;
            
            
            
            class Test extends Sample {
            }
        """.trimIndent()
        val path = Paths.get(".")
        val outputs = mutableMapOf<String, ByteArrayOutputStream>()
        TestData.transformer.remap(mapOf(
            path to mapOf(
                "test.java" to { original.byteInputStream() },
                "my/Sample.java" to { "package my; public class Sample {}".byteInputStream() }
            )
        )) { _, unitName ->
            outputs[unitName] = ByteArrayOutputStream()
            outputs[unitName]!!
        }

        outputs["test.java"]!!.toString() shouldBe """
            package test;
            
            import my.Sample;
            
            class Test extends Sample {
            }
        """.trimIndent()
    }

    @Test
    fun `should preserve existing ambiguous imports`() {
        TestData.remap("""
            package test;
            
            import java.awt.List;
            
            class Test implements List {
            }
        """.trimIndent()) shouldBe """
            package test;
            
            import java.awt.List;
            
            class Test implements List {
            }
        """.trimIndent()
    }

    @Test
    fun `should add new imports in empty lines`() {
        TestData.remap("""
            package test;
            
            
            
            
            class Test extends ArrayList implements Closeable {
            }
        """.trimIndent()) shouldBe """
            package test;
            
            import java.io.Closeable;
            import java.util.ArrayList;
            
            class Test extends ArrayList implements Closeable {
            }
        """.trimIndent()
    }

    @Test
    fun `should add new imports in place of removed imports`() {
        TestData.remap("""
            package test;
            
            import test.Unused1;
            import test.Unused2;
            
            class Test extends ArrayList implements Closeable {
            }
        """.trimIndent()) shouldBe """
            package test;
            
            import java.io.Closeable;
            import java.util.ArrayList;
            
            class Test extends ArrayList implements Closeable {
            }
        """.trimIndent()
    }

    @Test
    @Disabled("Need to evaluate if this test is needed, or if it should be redesigned, now that star import checking is implemented")
    fun `preserves star imports`() {
        TestData.remap("""
            package test;
            
            import test.Unused1;
            import java.util.*;
            
            class Test extends ArrayList implements Closeable {
            }
        """.trimIndent()) shouldBe """
            package test;
            
            import java.io.Closeable;
            import java.util.*;
            
            class Test extends ArrayList implements Closeable {
            }
        """.trimIndent()
    }

    @Test
    fun `should not import classes from the current package`() {
        TestData.remap("""
            package b.pkg;
            
            class Test extends B implements BInterface {
            }
        """.trimIndent()) shouldBe """
            package b.pkg;
            
            class Test extends B implements BInterface {
            }
        """.trimIndent()
    }

    @Test
    fun `should not import self`() {
        TestData.remap("test/Test.java", """
            package test;
            
            public class Test {
                Test inner;
            }
        """.trimIndent()) shouldBe """
            package test;
            
            public class Test {
                Test inner;
            }
        """.trimIndent()
    }

    @Test
    fun `should not import java-lang package`() {
        TestData.remap("test/Test.java", """
            package test;
            
            public class Test {
                Object inner;
            }
        """.trimIndent()) shouldBe """
            package test;
            
            public class Test {
                Object inner;
            }
        """.trimIndent()
    }

    @Test
    fun `should not import own inner classes`() {
        TestData.remap("""
            package test;
            
            class Test {
                TestInner inner;
                public class TestInner {}
            }
        """.trimIndent()) shouldBe """
            package test;
            
            class Test {
                TestInner inner;
                public class TestInner {}
            }
        """.trimIndent()
    }

    @Test
    fun `should not import generic types`() {
        TestData.remap("""
            package test;
            
            class Test<B> {
                B inner;
            }
        """.trimIndent()) shouldBe """
            package test;
            
            class Test<B> {
                B inner;
            }
        """.trimIndent()
    }

    @Test
    fun `should not import variable references`() {
        TestData.remap("""
            package test;
            
            class Test {
                Object B;
                { B = null; }
                { Object BParent; BParent = B; }
            }
        """.trimIndent()) shouldBe """
            package test;
            
            class Test {
                Object B;
                { B = null; }
                { Object BParent; BParent = B; }
            }
        """.trimIndent()
    }

    @Test
    fun `should not import types that look like fields`() {
        val content = """
            package test;
            
            import a.pkg.A;
            
            class Test extends A {
                { conflictingField.method(); }
            }
        """.trimIndent()
        val path = Paths.get(".")
        val outputs = mutableMapOf<String, ByteArrayOutputStream>()
        TestData.transformer.remap(mapOf(
            path to mapOf(
            "test/Test.java" to { content.byteInputStream() },
            "c/conflictingField.java" to { "package c; public class conflictingField {}".byteInputStream() }
        ))) { _, unitName ->
            outputs[unitName] = ByteArrayOutputStream()
            outputs[unitName]!!
        }

        outputs["test/Test.java"]!!.toString() shouldBe """
            package test;
            
            import b.pkg.B;
            
            class Test extends B {
                { conflictingField.method(); }
            }
        """.trimIndent()
    }

    @Test
    @Disabled("Need to evaluate if this test is needed, or if it should be redesigned, now that static import checking is implemented")
    fun `should not touch static imports (yet)`() {
        TestData.remap("""
            package test;
            
            import test.Unused1;
            import test.Unused2;
            
            import static test.Unused.method;
            
            class Test extends ArrayList implements Closeable {
            }
        """.trimIndent()) shouldBe """
            package test;
            
            import java.io.Closeable;
            import java.util.ArrayList;
            
            import static test.Unused.method;
            
            class Test extends ArrayList implements Closeable {
            }
        """.trimIndent()
    }
}