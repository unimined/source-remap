import org.jetbrains.kotlin.gradle.tasks.KotlinCompile

plugins {
    kotlin("jvm") version "1.5.21"
    `maven-publish`
    application
    id("com.github.johnrengelman.shadow") version "7.1.2"

}

java {
    sourceCompatibility = JavaVersion.VERSION_1_8
    targetCompatibility = JavaVersion.VERSION_1_8
}

application {
    mainClass.set("com.replaymod.gradle.remap.MainKt")
}

base {
    archivesName.set("source-remap")
}

tasks.withType<JavaCompile>().configureEach {
    if (JavaVersion.current().isJava9Compatible) {
        options.release.set(8)
    }
}

tasks.withType<KotlinCompile>().configureEach {
    kotlinOptions.jvmTarget = "1.8"
}

group = "xyz.wagyourtail.unimined"
version = "1.0.2"
version = if (project.hasProperty("version_snapshot")) version as String + "-SNAPSHOT" else version as String

base {
    archivesName.set("source-remap")
}

repositories {
    mavenCentral()
    maven("https://repo.spongepowered.org/repository/maven-public/")
}

val testA by sourceSets.creating
val testB by sourceSets.creating

kotlinVersion("1.5.21", isPrimaryVersion = true)
kotlinVersion("1.6.20")


dependencies {
    shadow(api("org.jetbrains.kotlin:kotlin-compiler-embeddable:1.5.21")!!)
    shadow(implementation(kotlin("stdlib"))!!)
    shadow(api("org.cadixdev:lorenz:0.5.0")!!)
    runtimeOnly("net.java.dev.jna:jna:5.10.0") // don't strictly need this but IDEA spams log without

    shadow(implementation("net.sourceforge.argparse4j:argparse4j:0.9.0")!!)

    testImplementation("org.junit.jupiter:junit-jupiter:5.7.1")
    testImplementation("io.kotest:kotest-assertions-core:4.6.3")

    testRuntimeOnly(testA.output)
    testRuntimeOnly(testB.output)
    testRuntimeOnly("org.spongepowered:mixin:0.8.4")
}

tasks.jar {
    manifest {
        attributes(
            "Implementation-Title" to base.archivesName.get(),
            "Implementation-Version" to project.version,
            "Main-Class" to application.mainClass.get()
        )
    }
}

tasks.shadowJar {

    configurations = listOf(
        project.configurations.shadow.get()
    )

    manifest {
        attributes(
            "Implementation-Title" to base.archivesName.get(),
            "Implementation-Version" to project.version,
            "Main-Class" to application.mainClass.get()
        )
    }
}

tasks.test {
    useJUnitPlatform()
}

fun kotlinVersion(version: String, isPrimaryVersion: Boolean = false) {
    val name = version.replace(".", "")

    val sourceSet = sourceSets.create("kotlin$name")

    val testClasspath = configurations.create("kotlin${name}TestClasspath") {
        extendsFrom(configurations.testRuntimeClasspath.get())
        extendsFrom(configurations[sourceSet.compileOnlyConfigurationName])
    }

    dependencies {
        implementation(sourceSet.output)
        sourceSet.compileOnlyConfigurationName("org.jetbrains.kotlin:kotlin-compiler-embeddable:$version")
    }

    tasks.jar {
        from(sourceSet.output)
    }

    if (!isPrimaryVersion) {
        val testTask = tasks.register("testKotlin$name", Test::class) {
            useJUnitPlatform()
            testClassesDirs = sourceSets.test.get().output.classesDirs
            classpath = testClasspath + sourceSets.test.get().output + sourceSets.main.get().output
        }
        tasks.check { dependsOn(testTask) }
    }
}

publishing {
    repositories {
        maven {
            name = "WagYourMaven"
            url = if (project.hasProperty("version_snapshot")) {
                project.uri("https://maven.wagyourtail.xyz/snapshots/")
            } else {
                project.uri("https://maven.wagyourtail.xyz/releases/")
            }
            credentials {
                username = project.findProperty("mvn.user") as String? ?: System.getenv("USERNAME")
                password = project.findProperty("mvn.key") as String? ?: System.getenv("TOKEN")
            }
        }
    }
    publications {
        create("maven", MavenPublication::class) {
            from(components["java"])
        }
    }
}