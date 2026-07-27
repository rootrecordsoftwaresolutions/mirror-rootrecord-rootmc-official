plugins {
    java
}

version = "1.7.0"

dependencies {
    implementation("com.mysql:mysql-connector-j:9.2.0")
    implementation("net.dv8tion:JDA:6.4.2") {
        exclude(module = "opus-java")
        exclude(module = "tink")
    }
}

tasks.named<Jar>("jar") {
    duplicatesStrategy = org.gradle.api.file.DuplicatesStrategy.EXCLUDE
    from({
        configurations.runtimeClasspath.get()
            .filter { it.name.endsWith("jar") }
            .map { zipTree(it) }
    })
}
