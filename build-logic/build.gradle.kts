plugins {
    `kotlin-dsl`
}

java {

    toolchain {
        languageVersion = JavaLanguageVersion.of(21)
    }
}

dependencies {

    implementation("com.gradleup.shadow:shadow-gradle-plugin:8.3.0")
}
