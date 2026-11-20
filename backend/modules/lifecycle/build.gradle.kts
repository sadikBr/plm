plugins {
    id("plm.module")
}

dependencies {
    // Lifecycles are assigned per type and inherited along the type hierarchy.
    implementation(project(":backend:modules:types"))
}
