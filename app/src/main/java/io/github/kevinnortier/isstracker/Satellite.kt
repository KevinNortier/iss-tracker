package io.github.kevinnortier.isstracker

/**
 * A satellite the user tracks.
 *
 * @property bright true for satellites that can be seen with the naked eye when sunlit (space stations,
 *   CelesTrak's "Brightest" group). Only their passes get the ★ "visible to the eye" mark; for others,
 *   being sunlit in a dark sky doesn't mean you can see them.
 * @property colorIndex its colour in the satellite palette (0 until [COLOR_COUNT]), picked when it was added
 *   and kept when the list is reordered or others are removed. Null for satellites that aren't tracked.
 */
data class Satellite(
    val noradId: Int,
    val displayName: String,
    val bright: Boolean = false,
    val colorIndex: Int? = null,
) {
    companion object {
        val ISS = Satellite(25544, "ISS", bright = true, colorIndex = 0)

        /** What new installs (and installs from before multi-satellite support) track. */
        val DEFAULT_TRACKED: List<Satellite> = listOf(ISS)

        /** Upper limit for the tracked list (CelesTrak downloads and calculation time). */
        const val MAX_TRACKED = 20

        /** Number of colours in the satellite palette (see `SatelliteColors`). */
        const val COLOR_COUNT = 8
    }
}

/**
 * Gives every satellite without a (valid) colour the least used palette colour, the lowest one on a tie,
 * in list order. Satellites that already have one keep it. So the first [Satellite.COLOR_COUNT] satellites
 * get different colours, and after that colours repeat as evenly as possible.
 */
fun assignColors(satellites: List<Satellite>): List<Satellite> {
    val valid = satellites.map { s -> s.colorIndex?.takeIf { it in 0 until Satellite.COLOR_COUNT } }
    val uses = IntArray(Satellite.COLOR_COUNT)
    valid.forEach { index -> if (index != null) uses[index]++ }
    return satellites.mapIndexed { i, s ->
        val index = valid[i] ?: uses.indices.minBy { uses[it] }.also { uses[it]++ }
        if (index == s.colorIndex) s else s.copy(colorIndex = index)
    }
}

/**
 * Stores the tracked list as text: one satellite per line, "noradId<TAB>bright<TAB>colour<TAB>name".
 * Lines from before colours were stored ("noradId<TAB>bright<TAB>name") are still read, without a colour.
 */
object TrackedSatellitesCodec {

    fun encode(satellites: List<Satellite>): String = satellites.joinToString("\n") { s ->
        // Tabs and line breaks can't appear in a name read back from CelesTrak, but be safe.
        val name = s.displayName.replace('\t', ' ').replace('\n', ' ').trim()
        "${s.noradId}\t${if (s.bright) 1 else 0}\t${s.colorIndex ?: ""}\t$name"
    }

    /** Lines that can't be read are skipped; duplicates (same NORAD ID) keep the first. */
    fun decode(text: String): List<Satellite> = text.lineSequence()
        .filter { it.isNotBlank() }
        .mapNotNull { line ->
            val parts = line.split('\t', limit = 4)
            // Names never contain tabs, so three parts is the old format without a colour.
            val (colorText, nameText) = when (parts.size) {
                4 -> parts[2] to parts[3]
                3 -> "" to parts[2]
                else -> return@mapNotNull null
            }
            val id = parts[0].trim().toIntOrNull()?.takeIf { it > 0 } ?: return@mapNotNull null
            val name = nameText.trim().ifEmpty { "#$id" }
            Satellite(id, name, bright = parts[1].trim() == "1", colorIndex = colorText.trim().toIntOrNull())
        }
        .distinctBy { it.noradId }
        .toList()
}
