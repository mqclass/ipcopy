plugins {
    java
}

group = "ru.mqclass"
version = "1.2.0"

java {
    sourceCompatibility = JavaVersion.VERSION_21
    targetCompatibility = JavaVersion.VERSION_21
    toolchain {
        languageVersion.set(JavaLanguageVersion.of(21))
    }
}

repositories {
    mavenCentral()
    maven("https://maven.fabricmc.net/")
    maven("https://maven.terraformersmc.com/releases/")
}

val userHome = System.getProperty("user.home")
val freesmDir = file("$userHome/AppData/Roaming/FreesmLauncher")

dependencies {
    // Intermediary minecraft client jar
    compileOnly(fileTree("$freesmDir/instances/1.21.11 SM/minecraft/.fabric/remappedJars") {
        include("**/*.jar")
    })

    // Processed fabric mods
    compileOnly(fileTree("$freesmDir/instances/1.21.11 SM/minecraft/.fabric/processedMods") {
        include("**/*.jar")
    })

    // ModMenu jar from mods folder
    compileOnly(fileTree("$freesmDir/instances/1.21.11 SM/minecraft/mods") {
        include("**/modmenu*.jar")
    })

    // Fabric Loader & Sponge Mixin & Brigadier & Gson
    compileOnly(fileTree("$freesmDir/libraries") {
        include("**/fabric-loader*.jar")
        include("**/sponge-mixin*.jar")
        include("**/brigadier*.jar")
        include("**/gson*.jar")
    })
}

tasks.withType<JavaCompile> {
    options.encoding = "UTF-8"
    options.compilerArgs.add("-proc:none")
}

tasks.jar {
    archiveBaseName.set("ipcopy")
    archiveVersion.set("1.1.0")
}
