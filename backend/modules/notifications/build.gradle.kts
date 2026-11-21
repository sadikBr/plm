plugins {
    id("plm.module")
}

dependencies {
    implementation(project(":backend:modules:identity"))
    implementation(libs.spring.boot.starter.mail)
    implementation(libs.slf4j.api)
}
