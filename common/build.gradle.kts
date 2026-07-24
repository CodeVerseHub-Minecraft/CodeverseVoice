// Platform independent core: identity model, restrictions, configuration,
// messages, storage and cross server propagation. Depends on neither Paper nor
// Velocity, which is what lets both modules share it without either dragging
// the other's API onto the classpath.
plugins {
    `java-library`
}

dependencies {
    // The shared contract. The api artifact is provided at runtime by
    // CodeverseAuth on the proxy, but a backend has no such provider, so the
    // jdbc artifact ships the identity implementation and drags api in with
    // it. api() rather than implementation() so the platform modules that
    // depend on common can name TrustTier and Identity without redeclaring
    // the coordinate.
    api("com.github.CodeVerseHub-Minecraft.CodeverseAPI:api:0.2.0")
    api("com.github.CodeVerseHub-Minecraft.CodeverseAPI:jdbc:0.2.0")
    implementation("com.zaxxer:HikariCP:7.1.0")
    implementation("com.github.ben-manes.caffeine:caffeine:3.2.4")
    implementation("io.lettuce:lettuce-core:7.6.0.RELEASE")
    implementation("com.google.code.gson:gson:2.11.0")
    compileOnly("net.kyori:adventure-api:5.2.0")
    compileOnly("net.kyori:adventure-text-minimessage:5.2.0")

    testImplementation("org.junit.jupiter:junit-jupiter:5.11.4")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
    testRuntimeOnly("com.mysql:mysql-connector-j:9.7.0")
    testImplementation("net.kyori:adventure-api:5.2.0")
    testImplementation("net.kyori:adventure-text-minimessage:5.2.0")
    testImplementation("net.kyori:adventure-text-serializer-plain:5.2.0")
}
