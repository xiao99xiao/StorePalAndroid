plugins {
    id("com.android.library") version "8.13.2" apply false
    id("com.android.application") version "8.13.2" apply false
    kotlin("jvm") version "2.2.21" apply false
    kotlin("android") version "2.2.21" apply false
    kotlin("plugin.serialization") version "2.2.21" apply false
    id("org.jetbrains.kotlin.plugin.compose") version "2.2.21" apply false
}
allprojects {
    group = "app.storepal"
    version = "0.1.0"
}

subprojects {
    plugins.withId("maven-publish") {
        extensions.configure<PublishingExtension> {
            repositories {
                maven {
                    name = "StorePal"
                    url = rootProject.layout.buildDirectory.dir("maven-repository").get().asFile.toURI()
                }
            }
            publications.withType<MavenPublication>().configureEach {
                pom {
                    name.set(if (project.name == "storepal-core") "StorePal Core" else "StorePal Android")
                    description.set("Native Android feedback, attachments, conversations, and localized release notes for StorePal.")
                    url.set("https://github.com/xiao99xiao/StorePalAndroid")
                    licenses { license { name.set("MIT License"); url.set("https://opensource.org/license/mit") } }
                    developers { developer { id.set("xiao99xiao"); name.set("xiao99xiao"); url.set("https://github.com/xiao99xiao") } }
                    scm {
                        url.set("https://github.com/xiao99xiao/StorePalAndroid")
                        connection.set("scm:git:https://github.com/xiao99xiao/StorePalAndroid.git")
                        developerConnection.set("scm:git:ssh://git@github.com/xiao99xiao/StorePalAndroid.git")
                    }
                }
            }
        }
    }
}
