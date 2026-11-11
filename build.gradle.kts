plugins { java }

group = "dev.mcmy"
version = "0.4.1"

val userHome = System.getProperty("user.home")
val ideaHome = providers.gradleProperty("ideaHome").orElse("$userHome/Library/Caches/idea-flutter-mcp-sdk/ideaIC-2026.1")
val pluginsHome = providers.gradleProperty("pluginsHome").orElse("$userHome/Library/Application Support/JetBrains/IntelliJIdea2026.3/plugins")
val platformJars = files(
    fileTree("${ideaHome.get()}/lib") { include("*.jar") },
    fileTree("${pluginsHome.get()}/Dart/lib") { include("*.jar") },
    fileTree("${pluginsHome.get()}/flutter-intellij/lib") { include("flutter-intellij-*.jar") }
)

dependencies {
    compileOnly(platformJars)
    testImplementation(platformJars)
}

tasks.withType<JavaCompile>().configureEach {
    options.release.set(21)
    options.encoding = "UTF-8"
}

val coreTest = tasks.register<JavaExec>("coreTest") {
    dependsOn(tasks.testClasses)
    classpath = sourceSets.test.get().runtimeClasspath
    mainClass.set("dev.mcmy.ideafluttermcp.CoreTest")
    enableAssertions = true
}
val vmTest = tasks.register<JavaExec>("vmTest") {
    dependsOn(tasks.testClasses)
    classpath = sourceSets.test.get().runtimeClasspath
    mainClass.set("dev.mcmy.ideafluttermcp.VmConnectionTest")
    enableAssertions = true
}
val httpTest = tasks.register<JavaExec>("httpTest") {
    dependsOn(tasks.testClasses)
    classpath = sourceSets.test.get().runtimeClasspath
    mainClass.set("dev.mcmy.ideafluttermcp.McpHttpServerTest")
    enableAssertions = true
}
// 回归套件使用独立入口和断言，由 check 显式执行。
tasks.test { enabled = false }
tasks.check { dependsOn(coreTest, vmTest, httpTest) }
tasks.register<Zip>("buildPlugin") {
    dependsOn(tasks.jar, tasks.check)
    archiveFileName.set("flutter-debug-mcp-${project.version}.zip")
    destinationDirectory.set(layout.projectDirectory.dir("dist"))
    from(tasks.jar) { into("flutter-debug-mcp/lib") }
}
