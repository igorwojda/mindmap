plugins {
    id("java")
    id("org.jetbrains.kotlin.jvm") version "2.1.20"
    id("org.jetbrains.intellij.platform") version "2.3.0"
}

group = "com.mindmap.plugin"
version = "1.0.6"

repositories {
    mavenCentral()
    intellijPlatform {
        defaultRepositories()
    }
}

dependencies {
    intellijPlatform {
        intellijIdeaCommunity("2024.3")

        bundledPlugin("org.jetbrains.kotlin")

        // PythonCore provides `com.jetbrains.python.psi.*` for compile-time use by
        // `PythonCallableProvider`. IntelliJ IDEA Community does NOT bundle Python, so we
        // pull it from the marketplace for the dev classpath only. At runtime the optional
        // `<depends config-file="mindmap-python.xml">` gate in plugin.xml ensures the
        // Python provider is registered only when the host IDE actually loads
        // `com.intellij.modules.python` (PyCharm, IntelliJ with Python plugin installed,
        // or Android Studio+Python) — so on vanilla IDEA CE the Python classes are never
        // instantiated and no ClassNotFoundError is thrown.
        //
        // Version tracks IntelliJ IDEA 2024.3 (build 243.*). Bump when the base platform
        // version changes.
        plugin("PythonCore", "243.28141.18")

        pluginVerifier()
        zipSigner()
    }

    implementation("com.google.code.gson:gson:2.11.0")
}

kotlin {
    compilerOptions {
        jvmTarget = org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_21
    }
}

intellijPlatform {
    buildSearchableOptions = false
    publishing {
        token = providers.environmentVariable("PUBLISH_TOKEN")
    }
}

tasks {
    withType<JavaCompile> {
        sourceCompatibility = "21"
        targetCompatibility = "21"
    }

    patchPluginXml {
        sinceBuild.set("243")
    }

    runIde {
        if (project.hasProperty("org.gradle.java.home")) {
            environment("JAVA_HOME", project.property("org.gradle.java.home").toString())
        }
    }

}
