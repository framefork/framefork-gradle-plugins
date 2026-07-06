plugins {
    alias(libs.plugins.kotlin.jvm)
    `java-gradle-plugin`
    `kotlin-dsl`
    alias(libs.plugins.pluginPublish)
}

repositories {
    mavenCentral()
    gradlePluginPortal()
}

//var hibernateVersion = configurations.detachedConfiguration(
//    dependencies.platform("org.springframework.boot:spring-boot-dependencies:${libs.versions.springBoot.get()}"),
//    dependencies.create("org.hibernate.orm:hibernate-core")
//).resolvedConfiguration
//    .firstLevelModuleDependencies
//    .first { it.moduleGroup == "org.hibernate.orm" && it.moduleName == "hibernate-core" }
//    .moduleVersion

dependencies {
    // Necessary to allow using version catalog in convention plugins.
    // See https://github.com/gradle/gradle/issues/15383 for more info.
    implementation(files(libs.javaClass.superclass.protectionDomain.codeSource.location))

    implementation(gradleApi())

    // Version of external plugins used in convention plugins must be managed inside the dependencies { } block.
    // See https://docs.gradle.org/current/userguide/custom_plugins.html#sec:convention_plugins
    // and https://docs.gradle.org/current/userguide/implementing_gradle_plugins_precompiled.html#sec:applying_external_plugins
    // for more info

    // imports all kotlin plugins
    implementation("org.jetbrains.kotlin:kotlin-gradle-plugin:${libs.versions.kotlin.get()}")
    implementation("org.jetbrains.kotlin:kotlin-allopen:${libs.versions.kotlin.get()}")
    implementation("org.jetbrains.kotlin:kotlin-noarg:${libs.versions.kotlin.get()}")
    implementation("org.jetbrains.kotlin:kotlin-reflect:${libs.versions.kotlin.get()}")
    implementation(kotlin("stdlib"))

    // imports 'org.springframework.boot' & 'io.spring.dependency-management'
    implementation("org.springframework.boot:spring-boot-gradle-plugin:${libs.versions.springBoot.get()}")

    // imports 'org.hibernate.orm'
//    implementation(group = "org.hibernate.orm", name = "hibernate-gradle-plugin", version = hibernateVersion)
}

java {
    sourceCompatibility = JavaVersion.VERSION_21
    targetCompatibility = JavaVersion.VERSION_21
}

tasks.withType<org.jetbrains.kotlin.gradle.tasks.KotlinCompile> {
    compilerOptions {
        freeCompilerArgs = listOf("-Xjsr305=strict")
        jvmTarget = org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_21 // This option specifies the target version of the generated JVM bytecode
    }
}

gradlePlugin {
    plugins {
        create("framefork.jvm-common") {
            id = "framefork.jvm-common"
            implementationClass = "org.framefork.build.JvmCommonPlugin"
            version = rootProject.version
        }

        create("framefork.jvm-library") {
            id = "framefork.jvm-library"
            implementationClass = "org.framefork.build.JvmLibraryPlugin"
            version = rootProject.version
        }
        create("framefork.jvm-library-spring") {
            id = "framefork.jvm-library-spring"
            implementationClass = "org.framefork.build.JvmLibrarySpringPlugin"
            version = rootProject.version
        }

        create("framefork.jvm-application") {
            id = "framefork.jvm-application"
            implementationClass = "org.framefork.build.JvmApplicationPlugin"
            version = rootProject.version
        }
        create("framefork.jvm-application-spring") {
            id = "framefork.jvm-application-spring"
            implementationClass = "org.framefork.build.JvmApplicationSpringPlugin"
            version = rootProject.version
        }
        create("framefork.jvm-application-spring-jpa") {
            id = "framefork.jvm-application-spring-jpa"
            implementationClass = "org.framefork.build.JvmApplicationSpringJpaPlugin"
            version = rootProject.version
        }
    }
}
