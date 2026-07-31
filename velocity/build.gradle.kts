plugins {
    java
    id("com.gradleup.shadow") version "9.6.0"
}

dependencies {
    implementation(project(":common"))

    // On the proxy the API interfaces are provided at runtime by
    // CodeverseAuth, so this module must compile against them but never ship
    // them. Bundling a second copy would give this plugin a different
    // CodeverseApiProvider from the one CodeverseAuth registered into, and the
    // voice service would be registered where nothing could find it.
    //
    // The jdbc implementation is a different matter and does ship, through
    // common. This module constructs JdbcIdentityService directly so its
    // startup does not depend on Auth having registered first, and nothing on
    // the proxy would otherwise supply that class.
    compileOnly("com.github.CodeVerseHub-Minecraft.CodeverseAPI:api:0.3.0")
    // Declared here rather than inherited from common, which stopped exposing
    // it when it moved to compileOnly. The proxy has no CodeverseExtension, so
    // this module is still the only source of JdbcIdentityService there.
    implementation("com.github.CodeVerseHub-Minecraft.CodeverseAPI:jdbc:0.3.0")
    implementation("com.github.CodeVerseHub-Minecraft:CodeverseUpdater:v0.1.4")

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

    // The api interfaces are provided at runtime by CodeverseAuth, so dropping
    // them from this jar keeps this plugin bound to the copy Auth registered
    // rather than a private duplicate. That matters only for the api artifact,
    // because CodeverseApiProvider holds a static registration: a second copy
    // of that class would be a second registry, and the voice service would
    // register where nothing could find it.
    //
    // The jdbc artifact is deliberately NOT excluded. It is a plain
    // implementation with no static registration, this module constructs
    // JdbcIdentityService directly to avoid coupling its startup to Auth's
    // load order, and nothing on the proxy supplies it: Auth ships api but not
    // jdbc. Excluding it produced a jar whose proxy class referenced a type
    // absent from the runtime, which is a NoClassDefFoundError on
    // ProxyInitializeEvent, and an Error rather than an Exception, so the
    // startup catch missed it and the pool leaked. Its own references to api
    // types resolve to Auth's copy through cross plugin class loading, which
    // is exactly the sharing that is wanted.
    dependencies {
        exclude(dependency("com.github.CodeVerseHub-Minecraft.CodeverseAPI:api"))
    }

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
