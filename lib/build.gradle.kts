/*
 * This file was generated by the Gradle 'init' task.
 *
 * This generated file contains a sample Kotlin library project to get you started.
 * For more details take a look at the 'Building Java & JVM projects' chapter in the Gradle
 * User Manual available at https://docs.gradle.org/7.2/userguide/building_java_projects.html
 */

import org.jetbrains.dokka.gradle.DokkaTask

version = "0.0.1"

plugins {
    // Apply the org.jetbrains.kotlin.jvm Plugin to add support for Kotlin.
    id("org.jetbrains.kotlin.jvm")
    id("org.jetbrains.dokka")
    id("org.jlleitschuh.gradle.ktlint")

    kotlin("plugin.serialization")

    // Apply the java-library plugin for API and implementation separation.
    `java-library`
}

val dokkaHtmlJava by tasks.register("dokkaHtmlJava", DokkaTask::class) {
    dokkaSourceSets.create("dokkaHtmlJava") {
        dependencies {
            plugins("org.jetbrains.dokka:kotlin-as-java-plugin:1.7.10")
        }
    }
}

tasks.jar {
    manifest {
        attributes(
            mapOf(
                "Implementation-Title" to project.name,
                "Implementation-Version" to project.version
            )
        )
    }
}

val buildCryptoCpp = task<Exec>("BuildCryptoCpp") {
    commandLine("${project.projectDir}/build_crypto_cpp.sh")
}

tasks.test {
    dependsOn(buildCryptoCpp)

    useJUnitPlatform()

    systemProperty("java.library.path", file("$buildDir/libs/shared").absolutePath)

    testLogging {
        events("PASSED", "SKIPPED", "FAILED")
        showStandardStreams = true
    }
}

ktlint {
    disabledRules.set(setOf("no-wildcard-imports"))
}

tasks.jar {
    dependsOn(buildCryptoCpp)
    // This will ignore files that are not there
    listOf("so", "dylib", "dll").forEach {
        from(
            file("file:$buildDir/libs/shared/libcrypto_jni.$it").absolutePath
        )
    }
}

dependencies {
    // Align versions of all Kotlin components
    implementation(platform("org.jetbrains.kotlin:kotlin-bom"))

    // Use the Kotlin JDK 8 standard library.
    implementation("org.jetbrains.kotlin:kotlin-stdlib-jdk8")

    // This dependency is used internally, and not exposed to consumers on their own compile classpath.
    implementation("com.google.guava:guava:30.1.1-jre")

    // Use the JUnit test library.
    testImplementation("org.junit.jupiter:junit-jupiter-params:5.8.2")
    testImplementation("org.junit.jupiter:junit-jupiter-engine:5.8.2")

    // This dependency is exported to consumers, that is to say found on their compile classpath.
    api("org.apache.commons:commons-math3:3.6.1")

    // Crypto provider
    // https://mvnrepository.com/artifact/org.bouncycastle/bcprov-jdk15on
    implementation("org.bouncycastle:bcprov-jdk15on:1.70")

    implementation("com.squareup.okhttp3:okhttp:4.10.0")

    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.3.3")
}
