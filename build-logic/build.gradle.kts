plugins {
    `kotlin-dsl`
}

java {
    // Matches the toolchain the convention plugins hand to every product module, which
    // keeps the Kotlin DSL's jvmTarget aligned with it.
    toolchain {
        languageVersion = JavaLanguageVersion.of(21)
    }
}

dependencies {
    // Needed so `vitaminmcp.shadow-conventions` can apply the plugin by id without a version.
    implementation("com.gradleup.shadow:shadow-gradle-plugin:8.3.0")
}
