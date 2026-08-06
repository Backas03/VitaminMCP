import com.github.jengelman.gradle.plugins.shadow.tasks.ShadowJar

plugins {
    id("vitaminmcp.java-conventions")
    id("com.gradleup.shadow")
}

/** A self-contained jar that something launches with `java -jar`. */
tasks.named<ShadowJar>("shadowJar") {
    archiveClassifier = ""
    archiveVersion = ""
    mergeServiceFiles()

    exclude("module-info.class")
    exclude("META-INF/versions/*/module-info.class")

    exclude("META-INF/*.SF")
    exclude("META-INF/*.DSA")
    exclude("META-INF/*.RSA")
}

tasks.named("build") {
    dependsOn(tasks.named("shadowJar"))
}
