plugins {
    id("plm.module")
}

// The query engine behind reports (the report builder): it knows no module's tables itself. Each
// module contributes how its objects, links and fields are stored (QueryContributor).
dependencies {
    implementation(project(":backend:modules:identity"))
    implementation(project(":backend:modules:access"))
    implementation(project(":backend:modules:types"))
}
