import org.jetbrains.kotlin.gradle.tasks.KotlinCompile


buildscript {
    val kotlinVersion = "1.7.10"

    dependencies {
        classpath("org.jetbrains.kotlin:kotlin-serialization:$kotlinVersion")
    }
}

plugins {
    kotlin("jvm") version "1.7.10"
    kotlin("plugin.serialization") version "1.7.10"
    application
}

group = "wisp.zetacore"
version = "1.0-SNAPSHOT"

repositories {
    mavenCentral()
}

dependencies {
    // API
    val ktorVersion = "2.1.0"
    implementation("io.ktor:ktor-client-core:$ktorVersion")
    implementation("io.ktor:ktor-client-cio:$ktorVersion")
    implementation("io.ktor:ktor-client-logging:$ktorVersion")
    implementation("io.ktor:ktor-serialization-kotlinx-json:$ktorVersion")

    implementation("com.discord4j:discord4j-core:3.2.3")
}


tasks.withType<KotlinCompile> {
    kotlinOptions.jvmTarget = "11"
}

application {
    mainClass.set("MainKt")
}