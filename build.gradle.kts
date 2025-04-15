val jdaVersion: String by project
val mapdbVersion: String by project
val guavaVersion: String by project
val gsonVersion: String by project
val logbackVersion: String by project
val junitVersion: String by project

plugins {
    id("java")
    id("com.gradleup.shadow") version "8.3.6"
}

group = "io.github.shaksternano"
base.archivesName.set("pin-bot")
version = "1.0.2"

repositories {
    mavenCentral()
}

dependencies {
    implementation("net.dv8tion:JDA:$jdaVersion") {
        exclude(module = "opus-java")
    }
    implementation("org.mapdb:mapdb:$mapdbVersion")
    implementation("com.google.guava:guava:$guavaVersion-jre")
    implementation("com.google.code.gson:gson:$gsonVersion")
    implementation("ch.qos.logback:logback-classic:$logbackVersion")
    testImplementation("org.junit.jupiter:junit-jupiter-api:$junitVersion")
    testRuntimeOnly("org.junit.jupiter:junit-jupiter-engine:$junitVersion")
}

tasks {
    jar {
        enabled = false
    }

    shadowJar {
        archiveClassifier.set("")
        mergeServiceFiles()
        minimize {
            exclude(dependency("ch.qos.logback:.*:.*"))
            exclude(dependency("org.mapdb:.*:.*"))
        }
        manifest {
            attributes(
                mapOf(
                    "Main-Class" to "${project.group}.pinbot.Main",
                )
            )
        }
    }

    build {
        dependsOn(shadowJar)
    }

    test {
        useJUnitPlatform()
    }
}
