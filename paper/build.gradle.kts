plugins {
    java
    id("com.gradleup.shadow") version "9.6.0"
}

dependencies {
    implementation(project(":common"))

    // Runtime provided by CodeverseExtension, which is a hard dependency in
    // plugin.yml so Paper both guarantees it enables first and grants this
    // plugin access to its classes. Shipping our own copy would give this
    // plugin a private registry that nothing else on the server can read.
    compileOnly("com.github.CodeVerseHub-Minecraft.CodeverseAPI:api:0.3.0")
    compileOnly("com.github.CodeVerseHub-Minecraft.CodeverseAPI:jdbc:0.3.0")
    implementation("com.github.CodeVerseHub-Minecraft:CodeverseUpdater:v0.1.4")

    compileOnly("io.papermc.paper:paper-api:26.2.build.65-beta")
    compileOnly("de.maxhenkel.voicechat:voicechat-api:2.6.20")
    compileOnly("net.luckperms:api:5.5")
    compileOnly("me.clip:placeholderapi:2.11.6")

    implementation("com.zaxxer:HikariCP:7.1.0")
    implementation("com.github.ben-manes.caffeine:caffeine:3.2.4")
    implementation("io.lettuce:lettuce-core:7.6.0.RELEASE")
    implementation("com.google.code.gson:gson:2.11.0")
}

tasks.processResources {
    val pluginVersion = project.version.toString()
    inputs.property("pluginVersion", pluginVersion)

    filesMatching("plugin.yml") {
        expand("version" to pluginVersion)
    }
}

tasks.shadowJar {
    duplicatesStrategy = DuplicatesStrategy.INCLUDE
    archiveBaseName.set("CodeverseVoice-Paper")
    archiveClassifier.set("")
    archiveVersion.set(project.version.toString())

    relocate("com.zaxxer.hikari", "net.codeverse.voice.libs.hikari")
    relocate("com.github.benmanes.caffeine", "net.codeverse.voice.libs.caffeine")
    relocate("io.lettuce", "net.codeverse.voice.libs.lettuce")
    // Netty is relocated along with Lettuce rather than excluded or left alone,
    // and the reason is subtle enough to be worth stating. Netty keeps its
    // ChannelOption and AttributeKey constants in a static ConstantPool keyed
    // by name. Sharing the platform's Netty means sharing that pool, and
    // Lettuce registers names the platform has already taken, which throws
    // ExceptionInInitializerError the moment Redis is touched. Excluding Netty
    // instead only moves the collision, because Lettuce still needs it. Giving
    // Lettuce its own relocated copy gives it its own pool, so the two cannot
    // collide at all.
    relocate("io.netty", "net.codeverse.voice.libs.netty")

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
