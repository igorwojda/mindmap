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
        // Base IDE for compilation + dev sandbox. Aligned to 2024.3.5 (build 243.28141)
        // so the marketplace PythonCore release below can be installed without the
        // "requires build X or newer" rejection. Our compiled bytecode targets stable
        // PSI APIs that exist on every 243.* build, so plugin.xml `sinceBuild=243`
        // still lets users on 2024.3 GA install the plugin (Python features just stay
        // dormant unless they install the Python plugin).
        intellijIdeaCommunity("2024.3.5")

        bundledPlugin("org.jetbrains.kotlin")

        // PythonCore provides `com.jetbrains.python.psi.*` for compile-time use by
        // `PythonCallableProvider`. IntelliJ IDEA Community does NOT bundle Python, so we
        // pull it from the marketplace for the dev classpath only. At runtime the optional
        // `<depends config-file="mindmap-python.xml">` gate in plugin.xml ensures the
        // Python provider is registered only when the host IDE actually loads
        // `com.intellij.modules.python` (PyCharm, IntelliJ with Python plugin installed,
        // or Android Studio + Python) — so on vanilla IDEA CE the Python classes are never
        // instantiated and no ClassNotFoundError is thrown.
        //
        // The version below MUST satisfy the dev-sandbox IDE's `since-build` gate:
        // PythonCore 243.28141.18 declares `since=243.28141`, so the dev IDE must be
        // ≥ 243.28141 (i.e. IntelliJ IDEA 2024.3.5+). Older 243.x builds (2024.3 GA
        // through 2024.3.4) ship with PythonCore 243.21565.* / .26053.* etc., which the
        // marketplace no longer publishes — so we pin the dev IDE to 2024.3.5+ above.
        // For end users, this restriction does NOT apply: the user's installed Python
        // plugin matches their IDE version automatically.
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
        // Lower bound: IntelliJ IDEA 2024.3 GA (build 243.21565). Our PSI usage is stable
        // across all 243.* builds, so users on 2024.3 GA can install — they just don't
        // get Python features unless they also install the Python plugin.
        sinceBuild.set("243")
        // No `untilBuild` set: IntelliJ Platform Gradle plugin 2.x targeting platform
        // 243+ ignores `untilBuild` by design (and emits a warning if set), which means
        // the produced plugin has NO upper-build constraint and installs cleanly on
        // every future IntelliJ IDEA, PyCharm, and Android Studio release without
        // republishing. Do not add `untilBuild.set(...)` back unless JetBrains reverts.
    }

    runIde {
        if (project.hasProperty("org.gradle.java.home")) {
            environment("JAVA_HOME", project.property("org.gradle.java.home").toString())
        }
    }

}
