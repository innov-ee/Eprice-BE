package ee.innov.eprice.util

/**
 * Retrieves an environment variable and splits it into a list of strings by delimiter (default comma).
 * Trims whitespace and excludes blank entries.
 * Returns [default] if the environment variable is not set or empty.
 */
fun getEnvList(
    name: String,
    delimiter: String = ",",
    default: List<String> = emptyList()
): List<String> {
    val value = System.getenv(name) ?: return default
    val list = value.split(delimiter).map { it.trim() }.filter { it.isNotEmpty() }
    return list.ifEmpty { default }
}
