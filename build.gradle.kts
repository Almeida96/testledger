plugins {
    kotlin("jvm") version "2.4.21"
    kotlin("plugin.serialization") version "2.4.21"
    application
    jacoco
}

group = "io.github.almeida96"
version = "0.3.0"

repositories { mavenCentral() }

dependencies {
    implementation("io.modelcontextprotocol:kotlin-sdk-server:0.16.0")
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.11.0")
    implementation("org.xerial:sqlite-jdbc:3.54.1.0")
    runtimeOnly("org.slf4j:slf4j-nop:2.0.17")
    testImplementation(kotlin("test"))
    testImplementation("org.junit.jupiter:junit-jupiter:6.1.3")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher:6.1.3")
}

kotlin { jvmToolchain(21) }
application { mainClass = "io.github.almeida96.testledger.MainKt" }
tasks.test { useJUnitPlatform(); finalizedBy(tasks.jacocoTestReport) }
jacoco { toolVersion = "0.8.14" }
tasks.jacocoTestReport { reports { xml.required = true; html.required = true } }
distributions {
    main {
        contents {
            from("README.md", "LICENSE", "CHANGELOG.md", "CONTRIBUTING.md")
            from("examples") { into("examples") }
            from("docs") { into("docs") }
            from("test") { into("test") }
            from("package.json", "pnpm-lock.yaml")
        }
    }
}
dependencyLocking { lockAllConfigurations() }
