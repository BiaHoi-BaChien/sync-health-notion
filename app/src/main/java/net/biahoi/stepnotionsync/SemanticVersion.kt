package net.biahoi.stepnotionsync

private val SEMANTIC_VERSION_PATTERN = Regex("""^[vV]?(\d+)\.(\d+)\.(\d+)(?:[-+].*)?$""")

internal data class SemanticVersion(
    val major: Int,
    val minor: Int,
    val patch: Int,
    val label: String
) : Comparable<SemanticVersion> {
    override fun compareTo(other: SemanticVersion): Int =
        compareValuesBy(this, other, SemanticVersion::major, SemanticVersion::minor, SemanticVersion::patch)
}

internal fun String.toSemanticVersion(): SemanticVersion? {
    val match = SEMANTIC_VERSION_PATTERN.matchEntire(trim()) ?: return null
    val major = match.groupValues[1].toIntOrNull() ?: return null
    val minor = match.groupValues[2].toIntOrNull() ?: return null
    val patch = match.groupValues[3].toIntOrNull() ?: return null
    return SemanticVersion(
        major = major,
        minor = minor,
        patch = patch,
        label = "$major.$minor.$patch"
    )
}
