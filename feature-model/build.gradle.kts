plugins {
    id("linecode.convention")
}

android {
    namespace = "cn.lineai.ai"
}

dependencies {
    implementation(project(":core-model"))
    implementation(project(":core-api"))
    implementation(project(":core-security"))
    implementation(project(":data"))
    api(libs.json)
    testImplementation(libs.junit)
    testImplementation(libs.json)
    testImplementation(project(":feature-tool"))
}
