pluginManagement {
    repositories {
        // 官方源优先：CI 跑在海外，国内镜像会超时
        google()
        mavenCentral()
        gradlePluginPortal()
        // 国内镜像作为兜底，本地网络差时仍可用
        maven { url = uri("https://maven.aliyun.com/repository/gradle-plugin") }
        maven { url = uri("https://maven.aliyun.com/repository/google") }
        maven { url = uri("https://maven.aliyun.com/repository/public") }
    }
}
dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
        maven { url = uri("https://maven.aliyun.com/repository/google") }
        maven { url = uri("https://maven.aliyun.com/repository/public") }
    }
}
rootProject.name = "xbox"
include(":app")
