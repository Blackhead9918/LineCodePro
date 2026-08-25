package cn.lineai.build

import com.android.build.api.dsl.LibraryExtension
import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.api.plugins.JavaPluginExtension
import org.gradle.kotlin.dsl.dependencies

class LineCodeConventionPlugin : Plugin<Project> {
    override fun apply(target: Project) {
        with(target) {
            plugins.apply("com.android.library")

            extensions.findByType(LibraryExtension::class.java)?.let { ext ->
                ext.compileSdk = 36
                ext.defaultConfig {
                    minSdk = 26
                    testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
                }
            }

            extensions.findByType(JavaPluginExtension::class.java)?.let { ext ->
                ext.sourceCompatibility = org.gradle.api.JavaVersion.VERSION_17
                ext.targetCompatibility = org.gradle.api.JavaVersion.VERSION_17
            }

            dependencies.add("testImplementation", "junit:junit:4.13.2")

            configurations.matching {
                it.name == "debugRuntimeClasspath" || it.name == "releaseRuntimeClasspath"
            }.configureEach {
                exclude(mapOf("group" to "com.google.guava", "module" to "listenablefuture"))
            }
        }
    }
}
