plugins {
    java
}

version = "1.7.2"

dependencies {
    implementation("com.mysql:mysql-connector-j:9.2.0")
}

tasks.named<Jar>("jar") {
    duplicatesStrategy = org.gradle.api.file.DuplicatesStrategy.EXCLUDE
    from({
        configurations.runtimeClasspath.get()
            .filter { it.name.endsWith("jar") }
            .map { zipTree(it) }
    })
}
