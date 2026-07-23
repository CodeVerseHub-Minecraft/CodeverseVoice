plugins {
    java
}

subprojects {
    apply(plugin = "java")

    group = "net.codeverse"
    version = "0.2.0"

    repositories {
        mavenCentral()
        maven("https://repo.papermc.io/repository/maven-public/")
        maven("https://maven.maxhenkel.de/repository/public/")
        maven("https://repo.extendedclip.com/releases/")
    }

    java {
        toolchain {
            languageVersion.set(JavaLanguageVersion.of(25))
        }
    }

    tasks.withType<JavaCompile> {
        options.encoding = "UTF-8"
        options.release.set(25)
        options.compilerArgs.add("-Xlint:deprecation")
    }

    tasks.withType<Test> {
        useJUnitPlatform()
    }
}
