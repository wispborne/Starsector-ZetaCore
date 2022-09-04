package techsupporter

import Config
import discord4j.core.GatewayDiscordClient
import discord4j.core.event.domain.message.MessageCreateEvent
import discord4j.core.`object`.entity.Message
import discord4j.core.`object`.entity.channel.MessageChannel
import discord4j.core.spec.MessageCreateSpec
import kotlinx.coroutines.*
import util.ktx.Timber
import java.nio.file.Path
import java.time.Duration
import java.time.Instant
import kotlin.jvm.optionals.getOrNull
import kotlin.time.Duration.Companion.seconds

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
    val repo = RepoWithCache<TechSupporterDatabase>(ignoreListFile.toFile(), TechSupporterDatabase.serializer())

    private var scope = CoroutineScope(Job())

    /**
     * 1. If account joined more than X hours ago, disregard it.
     * 2. Don't send the Welcome message to an account more than once.
     * 3. Don't send the Welcome message to an account if their first message includes a starsector.log.
     * 4. Only send the Wrong File message, not the Welcome message, if their first message includes the wrong log file.
     *
     * Need to track when each type of message was last sent (to avoid repeats and for later cleanup).
     */
    @OptIn(ExperimentalStdlibApi::class)
    suspend fun start(client: GatewayDiscordClient, config: Config) {
        val cutoffPoint = Instant.now().minus(Duration.ofHours(24))

        scope = CoroutineScope(Job())
        client.on(MessageCreateEvent::class.java)
            .filter { it.message.channelId.asLong() == techSupportChannelId }
            .filter { !it.message.author.get().isBot }
//            .filter { it.message.authorAsMember.block()?.joinTime?.getOrNull()?.isAfter(cutoffPoint) ?: false }
            .subscribe { event ->
                scope.launch(Dispatchers.Default) {
                    kotlin.runCatching {
                        withTimeout(timeout = 15.seconds) {
                            event ?: return@withTimeout
                            val message = event.message
                            val user = message.author.getOrNull() ?: return@withTimeout

                            Timber.d { "${user.username} wrote '${message.content}' with attachments '${message.attachments.joinToString { it.filename }}'." }

                            val ignoreFile = readIgnoreFile()
                            val userInDb = ignoreFile.users[user.id.asString()]
                            val hasAlreadySentWelcome = userInDb?.lastWelcomeMsgTimestamp != null

                            val channel = message.channel.block()
                            val hasWrongLogFile = hasWrongLogFile(message = message)
                            val hasValidLogFile = hasValidLogFile(message = message)

                            // If they included a valid logfile, don't bug them with welcome message.
                            if (hasValidLogFile) {
                                upsertUser(
                                    (userInDb ?: User(user.id.asString()).copy(
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
                    }
                        .onFailure { Timber.w(it) }
                }
            }
    }

    fun stop() {
        Timber.i { "Stopping ${TechSupporter::class.simpleName}." }
        scope.cancel()
        repo.close()
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
    suspend fun sendWelcomeMessage(
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
    suspend fun sendMessageOnWrongLogFile(
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

    fun readIgnoreFile(): TechSupporterDatabase = repo.get() ?: TechSupporterDatabase()

    suspend fun upsertUser(user: User) {
        withContext(Dispatchers.IO) {
            val db = readIgnoreFile()

            val newUsersList = db.users.toMutableMap().apply { this[user.userId] = user }
            repo.set(db.copy(users = newUsersList))
        }
    }
}