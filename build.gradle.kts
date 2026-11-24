import java.util.Locale

plugins { java }

group = "dev.mcmy"
version = "0.6.0"

val userHome = File(System.getProperty("user.home"))
val os = System.getProperty("os.name").lowercase(Locale.ROOT)
fun userLocation(variable: String, fallback: String) = providers.environmentVariable(variable).orNull
    ?.takeIf { it.isNotBlank() }?.let(::File) ?: userHome.resolve(fallback)
val (cacheHome, dataHome) = when {
    os.startsWith("windows") -> userLocation("LOCALAPPDATA", "AppData/Local") to userLocation("APPDATA", "AppData/Roaming")
    os.contains("mac") -> userHome.resolve("Library/Caches") to userHome.resolve("Library/Application Support")
    else -> userLocation("XDG_CACHE_HOME", ".cache") to userLocation("XDG_DATA_HOME", ".local/share")
}
val ideaHome = providers.gradleProperty("ideaHome").orElse(cacheHome.resolve("idea-flutter-mcp-sdk/ideaIC-2026.1").path)
val pluginsHome = providers.gradleProperty("pluginsHome").orElse(dataHome.resolve("JetBrains/IntelliJIdea2026.3/plugins").path)
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
