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
    // compileOnly rather than api since 0.4.0. CodeverseExtension is now the
    // single jar that ships the contract on a backend, and a second copy is
    // not a race to define one class: Paper gives each plugin its own, so two
    // copies mean two static registries and a service contributed to one is
    // invisible to a consumer reading the other. Verified by running both.
    compileOnly("com.github.CodeVerseHub-Minecraft.CodeverseAPI:api:0.3.0")
    compileOnly("com.github.CodeVerseHub-Minecraft.CodeverseAPI:jdbc:0.3.0")
    implementation("com.zaxxer:HikariCP:7.1.0")
    implementation("com.github.ben-manes.caffeine:caffeine:3.2.4")
    implementation("io.lettuce:lettuce-core:7.6.0.RELEASE")
    implementation("com.google.code.gson:gson:2.11.0")
    compileOnly("net.kyori:adventure-api:5.2.0")
    compileOnly("net.kyori:adventure-text-minimessage:5.2.0")

    // Tests exercise the contract directly, so they need the real classes
    // rather than the ones provided at runtime by CodeverseExtension.
    testImplementation("com.github.CodeVerseHub-Minecraft.CodeverseAPI:api:0.3.0")
    testImplementation("com.github.CodeVerseHub-Minecraft.CodeverseAPI:jdbc:0.3.0")
    testImplementation("org.junit.jupiter:junit-jupiter:5.11.4")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
    testRuntimeOnly("com.mysql:mysql-connector-j:9.7.0")
    testImplementation("net.kyori:adventure-api:5.2.0")
    testImplementation("net.kyori:adventure-text-minimessage:5.2.0")
    testImplementation("net.kyori:adventure-text-serializer-plain:5.2.0")
}
