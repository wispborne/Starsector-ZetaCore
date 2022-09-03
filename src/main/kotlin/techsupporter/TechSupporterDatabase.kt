package techsupporter

import kotlinx.serialization.Serializable

@Serializable
data class TechSupporterDatabase(
    val users: Map<String, User> = emptyMap()
)

@Serializable
data class User(
    val userId: String,
    val lastWelcomeMsgTimestamp: Long? = null,
    val lastWrongLogfileTimestamp: Long? = null,
)