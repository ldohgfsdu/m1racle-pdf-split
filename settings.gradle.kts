pluginManagement {
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
        // Mirrors are declared after the publisher repositories so that a mirror
        // outage can never shadow an artifact that is available upstream.
        maven("https://maven.aliyun.com/repository/google")
        maven("https://maven.aliyun.com/repository/public")
    }
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
        maven("https://maven.aliyun.com/repository/google")
        maven("https://maven.aliyun.com/repository/public")
    }
}

rootProject.name = "m1racle-pdf-split"
