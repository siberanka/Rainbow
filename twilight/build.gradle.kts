plugins {
    java
}

group = providers.gradleProperty("maven_group").get()
val twilightVersion = providers.gradleProperty("twilight_version").get()
version = twilightVersion

base {
    archivesName = "Twilight"
}

dependencies {
    compileOnly("io.papermc.paper:paper-api:1.21.4-R0.1-SNAPSHOT")
    compileOnly("org.geysermc.geyser:api:2.11.2-SNAPSHOT")
    testCompileOnly("io.papermc.paper:paper-api:1.21.4-R0.1-SNAPSHOT")

    testImplementation(platform("org.junit:junit-bom:5.14.1"))
    testImplementation("org.junit.jupiter:junit-jupiter")
    testImplementation("com.google.code.gson:gson:2.11.0")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

java {
    toolchain.languageVersion = JavaLanguageVersion.of(25)
    withSourcesJar()
}

tasks.withType<JavaCompile>().configureEach {
    options.encoding = "UTF-8"
    options.release = 21
    options.compilerArgs.add("-Xlint:deprecation")
}

tasks.processResources {
    val pluginProperties = mapOf("version" to providers.gradleProperty("twilight_version").get())
    inputs.properties(pluginProperties)
    filesMatching("plugin.yml") {
        expand(pluginProperties)
    }
}

tasks.jar {
    archiveFileName = "Twilight.jar"
    from(rootProject.file("LICENSE")) { rename { "LICENSE_Twilight" } }
    from(rootProject.file("LICENSE.LESSER")) { rename { "LICENSE.LESSER_Twilight" } }
    manifest.attributes(
        "Implementation-Title" to "Twilight",
        "Implementation-Version" to project.version,
        "Implementation-Vendor" to "siberanka"
    )
}

tasks.test {
    useJUnitPlatform()
}

tasks.register<JavaExec>("auditServerSources") {
    group = "verification"
    description = "Audits server content sources read-only and writes a machine-readable report."
    dependsOn(tasks.testClasses)
    mainClass = "com.siberanka.twilight.compiler.SourceAuditMain"
    classpath = sourceSets.test.get().runtimeClasspath
    val output = providers.gradleProperty("twilight.audit.output")
        .orElse(layout.buildDirectory.file("reports/twilight-source-audit.json").map { it.asFile.absolutePath })
    val roots = providers.gradleProperty("twilight.audit.roots")
    doFirst {
        val parsed = roots.orNull?.split(',')?.filter { it.isNotBlank() }
            ?: throw GradleException("Pass -Ptwilight.audit.roots=<server-root>,<server-root>")
        args = listOf(output.get()) + parsed
    }
}

tasks.register<JavaExec>("auditServerFonts") {
    group = "verification"
    description = "Audits only bitmap-font conversion from server sources read-only."
    dependsOn(tasks.testClasses)
    mainClass = "com.siberanka.twilight.compiler.FontAuditMain"
    classpath = sourceSets.test.get().runtimeClasspath
    val output = providers.gradleProperty("twilight.fontAudit.output")
        .orElse(layout.buildDirectory.file("reports/twilight-font-audit.json").map { it.asFile.absolutePath })
    val roots = providers.gradleProperty("twilight.audit.roots")
    providers.gradleProperty("twilight.audit.minecraft-version").orNull?.let {
        systemProperty("twilight.audit.minecraftVersion", it)
    }
    doFirst {
        val parsed = roots.orNull?.split(',')?.filter { it.isNotBlank() }
            ?: throw GradleException("Pass -Ptwilight.audit.roots=<server-root>,<server-root>")
        args = listOf(output.get()) + parsed
    }
}

tasks.register<JavaExec>("auditServerSounds") {
    group = "verification"
    description = "Audits only custom-sound conversion from server sources read-only."
    dependsOn(tasks.testClasses)
    mainClass = "com.siberanka.twilight.compiler.SoundAuditMain"
    classpath = sourceSets.test.get().runtimeClasspath
    val output = providers.gradleProperty("twilight.soundAudit.output")
        .orElse(layout.buildDirectory.file("reports/twilight-sound-audit.json").map { it.asFile.absolutePath })
    val roots = providers.gradleProperty("twilight.audit.roots")
    providers.gradleProperty("twilight.audit.minecraft-version").orNull?.let {
        systemProperty("twilight.audit.minecraftVersion", it)
    }
    doFirst {
        val parsed = roots.orNull?.split(',')?.filter { it.isNotBlank() }
            ?: throw GradleException("Pass -Ptwilight.audit.roots=<server-root>,<server-root>")
        args = listOf(output.get()) + parsed
    }
}
