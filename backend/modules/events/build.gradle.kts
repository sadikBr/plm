// Infrastructure module: relays the transactional outbox to Kafka (ADR 0008).
plugins {
    id("plm.module")
}

dependencies {
    implementation(libs.spring.boot.starter.kafka)

    testImplementation(libs.testcontainers.redpanda)
}
