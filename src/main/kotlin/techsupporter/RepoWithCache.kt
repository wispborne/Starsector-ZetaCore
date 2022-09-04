package techsupporter

import kotlinx.coroutines.*
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.KSerializer
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.decodeFromStream
import kotlinx.serialization.json.encodeToStream
import util.ktx.Timber
import java.io.Closeable
import java.io.File
import kotlin.time.Duration.Companion.seconds

@OptIn(ExperimentalSerializationApi::class)
class RepoWithCache<T>(val file: File, private val serializer: KSerializer<T>) : Closeable {
    private var cachedObj: T? = null
    private val scope = CoroutineScope(Job())

    init {
        kotlin.runCatching { cachedObj = readIgnoreFile() }
            .onFailure { Timber.w(it) }

        scope.launch(Dispatchers.IO) {
            while (true) {
                kotlin.runCatching {
                    readIgnoreFile()?.run {
                        if (cachedObj != null && this != cachedObj) {
                            Timber.d { "Writing file: $cachedObj" }
                            Json.encodeToStream(serializer, cachedObj!!, file.outputStream())
                        }
                    }
                }
                    .onFailure { Timber.w(it) }

                delay(5.seconds)
            }
        }
    }

    private fun readIgnoreFile(retries: Int = 1): T? {
        if (retries < 0) {
            Timber.w { "Unable to read file, no more retries." }
            return null
        }

        if (!file.exists()) {
            file.createNewFile()

            if (cachedObj != null) {
                Json.encodeToStream(serializer, cachedObj!!, file.outputStream())
            }
        }

        return kotlin.runCatching {
            Json.decodeFromStream(serializer, file.inputStream())
        }
            .recover {
                Timber.e(it)
                file.delete()
                file.createNewFile()

                if (cachedObj != null) {
                    Json.encodeToStream(serializer, cachedObj!!, file.outputStream())

                    readIgnoreFile(retries = retries - 1)
                } else {
                    null
                }
            }
            .getOrThrow()
    }

    fun set(obj: T) {
        cachedObj = obj
    }

    fun get(): T? = cachedObj

    override fun close() {
        scope.cancel()
    }
}