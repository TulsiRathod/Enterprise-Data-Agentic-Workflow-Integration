dependencies {
    api(project(":customer-onboarding:schema"))

    implementation("org.springframework.boot:spring-boot-starter-web")
    implementation("io.github.resilience4j:resilience4j-spring-boot3:2.2.0")
    implementation("io.github.resilience4j:resilience4j-annotations:2.2.0")
}
