pluginManagement { repositories { gradlePluginPortal(); google(); mavenCentral() } }
dependencyResolutionManagement { repositories { google(); mavenCentral() } }
rootProject.name = "every-article-desktop-poc"
include(":protocol", ":desktop-core", ":desktop")
