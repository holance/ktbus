import com.vanniktech.maven.publish.SonatypeHost
import org.jetbrains.kotlin.gradle.ExperimentalKotlinGradlePluginApi
import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.kotlinMultiplatform)
    alias(libs.plugins.androidLibrary)
    alias(libs.plugins.vanniktech.mavenPublish)
}

group = "org.holance" // groupId is often set at the project level too

fun shouldPublishToGitHubPackages(): Boolean {
    return System.getenv("GITHUB_USERNAME") != null && System.getenv("GITHUB_TOKEN") != null
}

fun shouldPublishToMavenCentral(): Boolean {
    return System.getenv("ORG_GRADLE_PROJECT_mavenCentralUsername") != null && System.getenv("ORG_GRADLE_PROJECT_mavenCentralPassword") != null
}

fun shouldSign(): Boolean {
    return System.getenv("ORG_GRADLE_PROJECT_signingInMemoryKeyId") != null && System.getenv("ORG_GRADLE_PROJECT_signingInMemoryKey") != null && System.getenv(
        "ORG_GRADLE_PROJECT_signingInMemoryKeyPassword"
    ) != null
}

mavenPublishing {
//    configure(
//        KotlinJvm(
//            javadocJar = JavadocJar.Javadoc(),
//            sourcesJar = true,
//        )
//    )
    coordinates(group.toString(), "ktbus", project.version.toString())

    pom {
        name = "KtBus"
        description = "An event bus implementation based on Kotlin Sharedflow."
        url = "https://github.com/holance/ktbus"
        licenses {
            license {
                name = "MIT License"
                url = "https://github.com/holance/ktbus/blob/main/LICENSE"
            }
        }
        developers {
            developer {
                id = "holance"
                name = "holance"
                email = "holance.app@gmail.com"
            }
        }
        scm {
            connection = "scm:git:git://github.com/holance/ktbus.git"
            developerConnection = "scm:git:ssh://github.com/holance/ktbus.git"
            url = "https://github.com/holance/ktbus"
        }
    }
    if (shouldPublishToMavenCentral()) {
        publishToMavenCentral(SonatypeHost.CENTRAL_PORTAL)
    }
    if (shouldSign()) {
        signAllPublications()
    }
}

publishing {
    repositories {
        if (shouldPublishToGitHubPackages()) {
            maven {
                name = "GitHubPackages"
                url = uri("https://maven.pkg.github.com/holance/ktbus")
                credentials {
                    username = System.getenv("GITHUB_USERNAME")
                    password = System.getenv("GITHUB_TOKEN")
                }
            }
        }
    }
}

kotlin {
    jvm()
    androidTarget {
        publishLibraryVariants("release")
        @OptIn(ExperimentalKotlinGradlePluginApi::class)
        compilerOptions {
            jvmTarget.set(JvmTarget.JVM_11)
        }
    }

    sourceSets {
        val commonMain by getting {
            dependencies {
                //put your multiplatform dependencies here
                implementation(libs.kotlin.reflect)
                implementation(libs.kotlinx.coroutines.core)
            }
        }
        val commonTest by getting {
            dependencies {
                implementation(libs.kotlin.test)
            }
        }
    }
}

android {
    namespace = "org.jetbrains.kotlinx.multiplatform.library.template"
    compileSdk = libs.versions.android.compileSdk.get().toInt()
    defaultConfig {
        minSdk = libs.versions.android.minSdk.get().toInt()
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
}