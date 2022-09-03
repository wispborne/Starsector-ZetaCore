package techsupporter

import Config
import discord4j.core.GatewayDiscordClient
import discord4j.core.event.domain.message.MessageCreateEvent
import discord4j.core.`object`.entity.Message
import discord4j.core.`object`.entity.channel.MessageChannel
import discord4j.core.spec.MessageCreateSpec
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.decodeFromStream
import kotlinx.serialization.json.encodeToStream
import util.ktx.Timber
import java.nio.file.Path
import java.time.Duration
import java.time.Instant
import kotlin.io.path.exists
import kotlin.jvm.optionals.getOrNull

/**
 * - Save file in memory, do batched writing.
 * - Periodic file cleanup.
 */
class TechSupporter {
    private val techSupportChannelId = 432809739164844032 // 619635013201428481
    private val ignoreListFile: Path = Path.of("userIgnoreList.json")
    private val archiveTypes = """(\.zip|\.7z|\.rar)?"""
    private val wrongLogfileRegex = Regex("""starsector\.log\.\d$archiveTypes""", RegexOption.IGNORE_CASE)
    private val validLogfileRegex = Regex("""starsector(\.log)?$archiveTypes""", RegexOption.IGNORE_CASE)

    /**
     * 1. If account joined more than X hours ago, disregard it.
     * 2. Don't send the Welcome message to an account more than once.
     * 3. Don't send the Welcome message to an account if their first message includes a starsector.log.
     * 4. Only send the Wrong File message, not the Welcome message, if their first message includes the wrong log file.
     *
     * Need to track when each type of message was last sent (to avoid repeats and for later cleanup).
     */
    @OptIn(ExperimentalStdlibApi::class)
    fun start(client: GatewayDiscordClient, config: Config) {
        val cutoffPoint = Instant.now().minus(Duration.ofHours(24))

        client.on(MessageCreateEvent::class.java)
            .filter { it.message.channelId.asLong() == techSupportChannelId }
            .filter { !it.message.author.get().isBot }
//            .filter { it.message.authorAsMember.block()?.joinTime?.getOrNull()?.isAfter(cutoffPoint) ?: false }
            .subscribe { event ->
                kotlin.runCatching {
                    event ?: return@subscribe
                    val message = event.message
                    val user = message.author.getOrNull() ?: return@subscribe

                    Timber.d { "${user.username} wrote '${message.content}' with attachments '${message.attachments.joinToString { it.filename }}'." }

                    val ignoreFile = readIgnoreFile()
                    val hasAlreadySentWelcome = ignoreFile.users[user.id.asString()]?.lastWelcomeMsgTimestamp != null

                    val channel = message.channel.block()
                    val hasWrongLogFile = hasWrongLogFile(message = message)
                    val hasValidLogFile = hasValidLogFile(message = message)

                    // If they included a valid logfile, don't bug them with welcome message.
                    if (hasValidLogFile) {
                        upsertUser(
                            (ignoreFile.users[user.id.asString()] ?: User(user.id.asString()).copy(
                                lastWelcomeMsgTimestamp = Instant.now().epochSecond
                            ))
                        )
                    }

                    if (hasWrongLogFile) {
                        sendMessageOnWrongLogFile(message, channel, ignoreFile, config.wrongLogMessage)
                    } else if (!hasAlreadySentWelcome && !hasValidLogFile) {
                        sendWelcomeMessage(
                            message = message,
                            channel = channel,
                            techSupporterDatabase = ignoreFile,
                            welcomeMessage = config.welcomeMessage
                        )
                    }
                }
                    .onFailure { Timber.w(it) }
            }
    }


    fun hasValidLogFile(message: Message): Boolean {
        return message.attachments.orEmpty().any { attachment ->
            attachment.filename.matches(validLogfileRegex)
        }
    }

    fun hasWrongLogFile(message: Message): Boolean {
        return message.attachments.orEmpty().any { attachment ->
            attachment.filename.matches(wrongLogfileRegex)
        }
    }

    @OptIn(ExperimentalStdlibApi::class)
    fun sendWelcomeMessage(
        message: Message,
        channel: MessageChannel?,
        techSupporterDatabase: TechSupporterDatabase,
        welcomeMessage: String
    ) {
        val userId = message.author.getOrNull()?.id?.asString() ?: return
        val userPing = "<@$userId>"

        val msg = welcomeMessage.replace("\$userPing", userPing)
        Timber.i { msg }
        channel
            ?.createMessage(
                MessageCreateSpec.builder()
                    .content(msg)
                    .messageReference(message.id)
                    .build()
            )
            ?.block()

        (techSupporterDatabase.users[userId] ?: User(userId = userId))
            .copy(lastWelcomeMsgTimestamp = Instant.now().epochSecond)
            .run { upsertUser(this) }
    }

    @OptIn(ExperimentalStdlibApi::class)
    fun sendMessageOnWrongLogFile(
        message: Message,
        channel: MessageChannel?,
        techSupporterDatabase: TechSupporterDatabase,
        wrongFileMessage: String
    ) {
        val user = message.author.getOrNull() ?: return
        val userId = user.id.asString()
        val userPing = "<@$userId>"

        message.attachments.orEmpty().firstOrNull { attachment ->
            attachment.filename.matches(wrongLogfileRegex)
        }?.let { backupLogFile ->
            val wrongFileMsg = wrongFileMessage
                .replace("\$backupLogFile", backupLogFile.filename)
                .replace("\$userPing", userPing)

            Timber.i { wrongFileMsg }
            channel
                ?.createMessage(
                    MessageCreateSpec.builder()
                        .content(wrongFileMsg)
                        .messageReference(message.id)
                        .build()
                )
                ?.block()
        }?.run {
            upsertUser(
                (techSupporterDatabase.users[userId]
                    ?: User(userId).copy(lastWrongLogfileTimestamp = Instant.now().epochSecond))
            )
        }
    }

    @OptIn(ExperimentalSerializationApi::class)
    fun readIgnoreFile(retries: Int = 1): TechSupporterDatabase {
        if (retries < 0) {
            throw RuntimeException("Unable to read file, no more retries.")
        }

        if (!ignoreListFile.exists()) {
            ignoreListFile.toFile().createNewFile()
            Json.encodeToStream(TechSupporterDatabase(), ignoreListFile.toFile().outputStream())
        }

        return kotlin.runCatching {
            Json.decodeFromStream<TechSupporterDatabase>(ignoreListFile.toFile().inputStream())
        }
            .recover {
                Timber.e(it)
                ignoreListFile.toFile().delete()
                ignoreListFile.toFile().createNewFile()
                Json.encodeToStream(TechSupporterDatabase(), ignoreListFile.toFile().outputStream())
                readIgnoreFile(retries = retries - 1)
            }
            .getOrThrow()
    }

    @OptIn(ExperimentalSerializationApi::class)
    fun upsertUser(user: User) {
        readIgnoreFile().run {
            val newUsersList = this.users.toMutableMap().apply { this[user.userId] = user }
            Json.encodeToStream(this.copy(users = newUsersList), ignoreListFile.toFile().outputStream())
        }
    }
}