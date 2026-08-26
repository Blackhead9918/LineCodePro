package cn.lineai.build

import org.gradle.api.Plugin
import org.gradle.api.Project

class LineCodeFeatureConventionPlugin : Plugin<Project> {
    override fun apply(target: Project) {
        with(target) {
            pluginManager.apply("linecode.convention")
        }
    }
}
