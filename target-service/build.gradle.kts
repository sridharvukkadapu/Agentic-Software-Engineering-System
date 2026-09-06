import org.springframework.boot.gradle.tasks.bundling.BootJar

plugins {
    java
    id("org.springframework.boot") version "3.3.4"
    id("io.spring.dependency-management") version "1.1.6"
}

group = "com.schwab.urlshortener"
version = "0.1.0"

java {
    toolchain {
        languageVersion = JavaLanguageVersion.of(17)
    }
}

repositories {
    mavenCentral()
}

dependencies {
    implementation("org.springframework.boot:spring-boot-starter-web")
    implementation("org.springframework.boot:spring-boot-starter-data-jpa")
    implementation("org.springframework.boot:spring-boot-starter-validation")
    implementation("org.flywaydb:flyway-core")
    implementation("org.flywaydb:flyway-database-postgresql")
    runtimeOnly("org.postgresql:postgresql")

    testImplementation("org.springframework.boot:spring-boot-starter-test")
    testRuntimeOnly("com.h2database:h2")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

tasks.withType<Test> {
    useJUnitPlatform()

    // The orchestrator's TestExecutor parses this task's console output line by line to
    // attribute PASSED/FAILED outcomes to acceptance criteria (by matching the criterion
    // id inside each test method's name). Gradle prints nothing per test by default, only
    // a final BUILD SUCCESSFUL/FAILED summary, so without these events every criterion
    // would silently get zero evidence even when every test genuinely passed.
    testLogging {
        events("passed", "failed", "skipped")
    }
}

tasks.named<BootJar>("bootJar") {
    archiveFileName.set("target-service.jar")
}
