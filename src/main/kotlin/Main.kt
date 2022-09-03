import discord4j.core.DiscordClientBuilder
import techsupporter.TechSupporter
import util.LogLevel
import util.Timber
import java.nio.file.Path
import java.util.*
import kotlin.io.path.absolutePathString
import kotlin.io.path.bufferedReader
import kotlin.io.path.exists

fun main(args: Array<String>) {
    println("Hello World!")

    // Try adding program arguments via Run/Debug configuration.
    // Learn more about running applications: https://www.jetbrains.com/help/idea/running-applications.html.
    println("Program arguments: ${args.joinToString()}")

    Timber.plant(Timber.DebugTree(LogLevel.DEBUG))

    val auth = Main.readAuth() ?: return
    val config = Main.readConfig() ?: return
    val client = DiscordClientBuilder.create(
        auth.discordAuthToken ?: run { System.err.println("Discord auth token is required."); return }
    )
        .build()
        .login()
        .block() ?: throw NullPointerException("Discord client was null.")

    TechSupporter().start(client, config)

    client.onDisconnect().block()
}

object Main {
    val authFilePath = Path.of("auth.properties")
    val configFilePath = Path.of("config.properties")


    fun readAuth(): Auth? =
        if (kotlin.runCatching { authFilePath.exists() }
                .onFailure { System.err.println(it) }
                .getOrNull() == true)
            Auth(discordAuthToken = Properties().apply { this.load(authFilePath.bufferedReader()) }["auth_token"]?.toString())
        else {
            System.err.println("Unable to find ${authFilePath.absolutePathString()}.")
            null
        }

    fun readConfig(): Config? =
        if (kotlin.runCatching { configFilePath.exists() }
                .onFailure { System.err.println(it) }
                .getOrNull() == true) {
            val properties = Properties().apply { this.load(configFilePath.bufferedReader()) }
            Config(
                logLevel = properties["log_level"].toString(),
                welcomeMessage = properties["welcomeMessage"].toString(),
                wrongLogMessage = properties["wrongLogFileMessage"].toString()
            )
        } else {
            System.err.println("Unable to find ${configFilePath.absolutePathString()}.")
            null
        }
}