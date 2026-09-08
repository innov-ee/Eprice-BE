package ee.innov.eprice.util

/**
 * Retrieves a required environment variable, or returns [default] if specified.
 * Throws [IllegalStateException] if the variable is not set or blank and no default is provided.
 */
fun getEnv(
    name: String,
    default: String? = null
): String {
    val value = System.getenv(name)
    if (!value.isNullOrBlank()) {
        return value
    }
    return default ?: throw IllegalStateException("$name environment variable is not set.")
}

/**
 * Retrieves an environment variable and splits it into a list of strings by delimiter (default comma).
 * Trims whitespace and excludes blank entries.
 * Returns [default] if the environment variable is not set or empty.
 */
fun getEnvList(
    name: String,
    delimiter: String = ",",
    default: List<String>? = null
): List<String> {
    val value = System.getenv(name)
    if (value != null) {
        val list = value.split(delimiter).map { it.trim() }.filter { it.isNotEmpty() }
        if (list.isNotEmpty()) return list
    }
    return default ?: throw IllegalStateException("$name environment variable is not set.")
}
