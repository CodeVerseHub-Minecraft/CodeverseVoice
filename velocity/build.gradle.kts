plugins {
    java
    id("com.gradleup.shadow") version "9.6.0"
}

dependencies {
    implementation(project(":common"))

    compileOnly("com.velocitypowered:velocity-api:4.0.0")
    annotationProcessor("com.velocitypowered:velocity-api:4.0.0")

    implementation("com.zaxxer:HikariCP:7.1.0")
    implementation("com.github.ben-manes.caffeine:caffeine:3.2.4")
    implementation("io.lettuce:lettuce-core:7.6.0.RELEASE")
    implementation("com.google.code.gson:gson:2.11.0")
}

tasks.shadowJar {
    duplicatesStrategy = DuplicatesStrategy.INCLUDE
    archiveBaseName.set("CodeverseVoice-Velocity")
    archiveClassifier.set("")
    archiveVersion.set(project.version.toString())

    relocate("com.zaxxer.hikari", "net.codeverse.voice.libs.hikari")
    relocate("com.github.benmanes.caffeine", "net.codeverse.voice.libs.caffeine")
    relocate("io.lettuce", "net.codeverse.voice.libs.lettuce")
    relocate("com.google.gson", "net.codeverse.voice.libs.gson")

    mergeServiceFiles()
    exclude("META-INF/versions/*/OSGI-INF/**")
    exclude("META-INF/io.netty.versions.properties")
    exclude("META-INF/*.SF")
    exclude("META-INF/*.DSA")
    exclude("META-INF/*.RSA")
    exclude("module-info.class")
    exclude("META-INF/versions/*/module-info.class")
}

tasks.build {
    dependsOn(tasks.shadowJar)
}
