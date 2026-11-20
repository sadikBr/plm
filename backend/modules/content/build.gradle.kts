plugins {
    id("plm.module")
}

dependencies {
    implementation(project(":backend:modules:access"))
    implementation(project(":backend:modules:versioning"))
    implementation(project(":backend:modules:jobs"))
    implementation(libs.slf4j.api)
    implementation(libs.awssdk.s3) {
        // The JDK's own HTTP client is enough; keep Netty and Apache out.
        exclude(group = "software.amazon.awssdk", module = "netty-nio-client")
        exclude(group = "software.amazon.awssdk", module = "apache-client")
    }
    implementation(libs.awssdk.url.connection.client)
    implementation(libs.pdfbox)

    testImplementation(project(":backend:modules:types"))
    testImplementation(libs.pdfbox)
}
