dependencies {
    implementation(project(":common"))
    implementation(project(":identity"))
    implementation(project(":inventory"))
    implementation(project(":booking"))
    implementation(libs.postgres)
    implementation(libs.flyway.core)
    implementation(libs.flyway.postgres)
}
