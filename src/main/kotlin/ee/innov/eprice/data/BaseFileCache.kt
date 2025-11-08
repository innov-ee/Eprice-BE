package ee.innov.eprice.data

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import kotlin.io.path.deleteIfExists
import kotlin.io.path.exists
import kotlin.io.path.isReadable

/**
 * An abstract base class for creating a cache that is persisted to a JSON file.
 *
 * This class handles the common logic for:
 * - Asynchronous file I/O using coroutines.
 * - Thread-safe writes/deletions using a Mutex.
 * - Atomic file writes using a temporary file.
 * - JSON serialization and deserialization.
 *
 * @param cacheFile The Path to the file where the cache will be persisted.
 */
abstract class BaseFileCache(protected val cacheFile: Path) {

    // A dedicated scope for file I/O
    protected val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    protected val persistMutex = Mutex()

    companion object {
        // A self-contained serializer, shared by all cache instances
        val json = Json {
            prettyPrint = true
            ignoreUnknownKeys = true
        }
    }

    /**
     * Loads and deserializes data from the cache file.
     * This is a blocking operation and should be called from init or a background thread.
     *
     * @return The deserialized data of type T, or null if loading fails or the file doesn't exist.
     */
    protected inline fun <reified T : Any> loadFromFile(): T? {
        if (!cacheFile.exists() || !cacheFile.isReadable()) {
            println("No cache file found or readable at $cacheFile. Starting with an empty cache.")
            return null
        }

        return try {
            val content = Files.readString(cacheFile)
            json.decodeFromString<T>(content)
        } catch (e: Exception) {
            println("Failed to load or parse cache file $cacheFile. Deleting corrupt file. Error: ${e.message}")
            // If file is corrupt, delete it to start fresh next time
            try {
                cacheFile.deleteIfExists()
            } catch (_: Exception) {
                // Ignore deletion error
            }
            null
        }
    }

    /**
     * Asynchronously saves the provided data to the cache file.
     *
     * @param data The data to be serialized and saved.
     */
    protected inline fun <reified T : Any> saveToFileAsync(data: T) {
        scope.launch {
            persistMutex.withLock {
                persistCache(data)
            }
        }
    }

    /**
     * Synchronously persists the cache to disk.
     * This method performs an atomic write by writing to a temp file first.
     */
    protected inline fun <reified T : Any> persistCache(data: T) {
        try {
            val jsonString = json.encodeToString(data)

            // Atomic write: Write to temp file, then rename
            val tempFile = cacheFile.resolveSibling("${cacheFile.fileName}.tmp")

            Files.newBufferedWriter(tempFile).use { writer ->
                writer.write(jsonString)
            }

            Files.move(
                tempFile,
                cacheFile,
                StandardCopyOption.REPLACE_EXISTING,
                StandardCopyOption.ATOMIC_MOVE
            )
        } catch (e: Exception) {
            // Log the error, but don't crash the application
            println("Failed to persist cache to $cacheFile. Error: ${e.message}")
            e.printStackTrace()
        }
    }

    /**
     * Asynchronously clears the cache file from disk.
     */
    protected fun clearFileAsync() {
        scope.launch {
            persistMutex.withLock {
                clearCacheFile()
            }
        }
    }

    /**
     * Synchronously deletes the cache file.
     */
    private fun clearCacheFile() {
        try {
            val deleted = Files.deleteIfExists(cacheFile)
            if (deleted) {
                println("Cache file $cacheFile deleted.")
            } else {
                println("Cache file $cacheFile did not exist.")
            }
        } catch (e: Exception) {
            // Log the error, but don't crash the application
            println("Failed to delete cache file $cacheFile. Error: ${e.message}")
            e.printStackTrace()
        }
    }
}