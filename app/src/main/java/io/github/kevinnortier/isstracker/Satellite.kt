package io.github.kevinnortier.isstracker

/**
 * A satellite the user tracks.
 *
 * @property bright true for satellites that can be seen with the naked eye when sunlit (space stations,
 *   CelesTrak's "Brightest" group). Only their passes get the ★ "visible to the eye" mark; for others,
 *   being sunlit in a dark sky doesn't mean you can see them.
 */
data class Satellite(val noradId: Int, val displayName: String, val bright: Boolean = false) {
    companion object {
        val ISS = Satellite(25544, "ISS", bright = true)

        /** What new installs (and installs from before multi-satellite support) track. */
        val DEFAULT_TRACKED: List<Satellite> = listOf(ISS)

        /** Upper limit for the tracked list (CelesTrak downloads and calculation time). */
        const val MAX_TRACKED = 20
    }
}

/** Stores the tracked list as text: one satellite per line, "noradId<TAB>bright<TAB>name". */
object TrackedSatellitesCodec {

    fun encode(satellites: List<Satellite>): String = satellites.joinToString("\n") { s ->
        // Tabs and line breaks can't appear in a name read back from CelesTrak, but be safe.
        val name = s.displayName.replace('\t', ' ').replace('\n', ' ').trim()
        "${s.noradId}\t${if (s.bright) 1 else 0}\t$name"
    }

    /** Lines that can't be read are skipped; duplicates (same NORAD ID) keep the first. */
    fun decode(text: String): List<Satellite> = text.lineSequence()
        .filter { it.isNotBlank() }
        .mapNotNull { line ->
            val parts = line.split('\t', limit = 3)
            if (parts.size != 3) return@mapNotNull null
            val id = parts[0].trim().toIntOrNull()?.takeIf { it > 0 } ?: return@mapNotNull null
            val name = parts[2].trim().ifEmpty { "#$id" }
            Satellite(id, name, bright = parts[1].trim() == "1")
        }
        .distinctBy { it.noradId }
        .toList()
}
