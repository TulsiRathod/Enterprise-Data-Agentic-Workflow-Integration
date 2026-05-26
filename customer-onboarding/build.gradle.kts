plugins {
    id("org.springframework.boot")
}

dependencies {
    implementation(project(":customer-onboarding-schema"))
    implementation(project(":integrations-crm-legacy"))

    implementation("org.springframework.boot:spring-boot-starter-web")
    implementation("org.springframework.boot:spring-boot-starter-validation")
    implementation("org.springframework.boot:spring-boot-starter-actuator")
    implementation("org.springframework.boot:spring-boot-starter-data-mongodb")

    implementation("org.springframework.statemachine:spring-statemachine-core:4.0.1")

    implementation("io.github.resilience4j:resilience4j-spring-boot3:2.2.0")
    implementation("io.github.resilience4j:resilience4j-reactor:2.2.0")

    implementation("org.redisson:redisson-spring-boot-starter:3.36.0")

    implementation("com.hubspot.jinjava:jinjava:2.7.4")

    implementation("software.amazon.awssdk:s3:2.28.0")
    implementation("software.amazon.awssdk:sqs:2.28.0")

    // Stand-in for the platform's ai-proxy-utils gRPC client. The interface
    // is defined in this module so production wiring just swaps the impl.
    implementation("com.squareup.okhttp3:okhttp:4.12.0")

    // OpenTelemetry — observability per platform standard
    implementation("io.opentelemetry:opentelemetry-api:1.43.0")
}

tasks.named<org.springframework.boot.gradle.tasks.bundling.BootJar>("bootJar") {
    enabled = true
}

tasks.named<Jar>("jar") {
    enabled = false
}
