plugins {
    kotlin("jvm") version "2.3.21"
    id("io.papermc.paperweight.userdev") version "2.0.0-beta.24"
}

repositories { mavenLocal(); mavenCentral(); maven("https://repo.papermc.io/repository/maven-public/") }
kotlin { jvmToolchain(25) }
dependencies { add("paperweightDevelopmentBundle", "dev.folia:dev-bundle:26.3.local-SNAPSHOT") }
paperweight {
    addServerDependencyTo = configurations.named("compileOnly").map { setOf(it) }
}
tasks.jar {
    archiveFileName.set("folia-ai-region-probe.jar")
    from(configurations.runtimeClasspath.get().map { if (it.isDirectory) it else zipTree(it) })
    duplicatesStrategy = DuplicatesStrategy.EXCLUDE
}
