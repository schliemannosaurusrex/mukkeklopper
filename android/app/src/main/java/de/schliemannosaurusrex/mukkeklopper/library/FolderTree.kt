package de.schliemannosaurusrex.mukkeklopper.library

/**
 * Ordneransicht rein aus den `RELATIVE_PATH`-Werten des MediaStore abgeleitet —
 * kein SAF, kein Dateisystem-Zugriff (PLAN.md Phase 8). Pfade enden immer auf `/`;
 * die Wurzel ist der leere String.
 */
data class FolderEntry(
    /** Voller Pfad inkl. Schrägstrich am Ende, z. B. `Music/MukkeKlopper/`. */
    val path: String,
    /** Nur das letzte Segment, z. B. `MukkeKlopper`. */
    val name: String,
    /** Titel in diesem Ordner und allen Unterordnern. */
    val trackCount: Int,
)

data class FolderContent(
    val path: String,
    val subFolders: List<FolderEntry>,
    val tracks: List<Track>,
)

object FolderTree {

    /** Kinder-Ordner und direkt enthaltene Titel für [path]. */
    fun contentOf(tracks: List<Track>, path: String): FolderContent {
        val prefix = path
        val subFolders = tracks.asSequence()
            .map { it.relativePath }
            .filter { it.length > prefix.length && it.startsWith(prefix) }
            .mapNotNull { it.drop(prefix.length).substringBefore('/').ifEmpty { null } }
            .distinct()
            .map { name ->
                val childPath = "$prefix$name/"
                FolderEntry(
                    path = childPath,
                    name = name,
                    trackCount = tracks.count { it.relativePath.startsWith(childPath) },
                )
            }
            .sortedBy { it.name.lowercase() }
            .toList()

        val direct = tracks
            .filter { it.relativePath == prefix }
            .sortedWith(compareBy({ it.trackNumber }, { it.displayName.lowercase() }))

        return FolderContent(path = path, subFolders = subFolders, tracks = direct)
    }

    /** Alle Ordner, die (auch indirekt) Titel enthalten — für den Ziel-Ordner-Picker. */
    fun allFolders(tracks: List<Track>): List<String> =
        tracks.asSequence()
            .map { it.relativePath }
            .flatMap { path ->
                // jeden Präfix des Pfads aufsammeln: a/b/c/ → a/, a/b/, a/b/c/
                path.trim('/').split('/').runningFold("") { acc, segment -> "$acc$segment/" }
            }
            .filter { it.isNotEmpty() }
            .distinct()
            .sorted()
            .toList()

    fun parentOf(path: String): String {
        val trimmed = path.trimEnd('/')
        if (!trimmed.contains('/')) return ""
        return trimmed.substringBeforeLast('/') + "/"
    }

    /** Breadcrumb-Segmente: `Music/MukkeKlopper/` → [(Music, Music/), (MukkeKlopper, Music/MukkeKlopper/)]. */
    fun breadcrumbs(path: String): List<Pair<String, String>> {
        if (path.isEmpty()) return emptyList()
        var acc = ""
        return path.trim('/').split('/').map { segment ->
            acc = "$acc$segment/"
            segment to acc
        }
    }
}
