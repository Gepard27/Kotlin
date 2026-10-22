dependencies {
    implementation(project(":common"))
    implementation(project(":identity"))
    implementation(project(":inventory"))
    implementation(project(":booking"))
    implementation(libs.jackson)
    implementation(libs.slf4j.api)
}
