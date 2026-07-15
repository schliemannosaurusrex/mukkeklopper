package de.schliemannosaurusrex.mukkeklopper.library

import android.app.Application
import android.content.IntentSender
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import de.schliemannosaurusrex.mukkeklopper.settings.LibraryViewMode
import de.schliemannosaurusrex.mukkeklopper.settings.SettingsRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class LibraryState(
    val tracks: List<Track> = emptyList(),
    val albums: List<Album> = emptyList(),
    val isLoading: Boolean = false,
    val hasPermission: Boolean = false,
    val viewMode: LibraryViewMode = LibraryViewMode.FOLDERS,
    /** Aktueller Ordner (RELATIVE_PATH mit Schrägstrich am Ende); leer = Wurzel. */
    val currentPath: String = "",
    /** Markiertes Startverzeichnis; leer = keins gesetzt. */
    val libraryRoot: String = "",
    val folder: FolderContent = FolderContent("", emptyList(), emptyList()),
    /** Album-/Artist-Modus: aktuell geöffnete Gruppe. */
    val selectedGroup: String? = null,
    /** IDs der ausgewählten Titel (Mehrfachauswahl für „Verschieben"). */
    val selectedTrackIds: Set<Long> = emptySet(),
    val message: String? = null,
) {
    val isSelecting: Boolean get() = selectedTrackIds.isNotEmpty()
    val isAtRoot: Boolean get() = currentPath.isEmpty()
    val currentIsStartFolder: Boolean get() = libraryRoot == currentPath && libraryRoot.isNotEmpty()
    val selectedTracks: List<Track> get() = tracks.filter { it.id in selectedTrackIds }
}

class LibraryViewModel(application: Application) : AndroidViewModel(application) {

    private val repository = MediaStoreRepository(application)
    private val settings = SettingsRepository(application)

    private val _state = MutableStateFlow(LibraryState())
    val state: StateFlow<LibraryState> = _state.asStateFlow()

    /** Ausstehende Verschiebe-Bestätigung (fremde Dateien) — die UI holt sie per IntentSender ein. */
    private val _writeRequest = MutableStateFlow<IntentSender?>(null)
    val writeRequest: StateFlow<IntentSender?> = _writeRequest.asStateFlow()

    private var pendingMove: Pair<List<Track>, String>? = null

    fun onPermissionGranted() {
        if (_state.value.hasPermission) return
        _state.update { it.copy(hasPermission = true) }
        loadLibrary(jumpToRoot = true)
    }

    fun refresh() = loadLibrary(jumpToRoot = false)

    private fun loadLibrary(jumpToRoot: Boolean) {
        viewModelScope.launch {
            _state.update { it.copy(isLoading = true) }
            val tracks = repository.loadTracks()
            val albums = repository.loadAlbums()
            val prefs = settings.settings.first()

            _state.update { current ->
                val path = if (jumpToRoot) prefs.libraryRoot else current.currentPath
                current.copy(
                    tracks = tracks,
                    albums = albums,
                    isLoading = false,
                    viewMode = prefs.libraryViewMode,
                    libraryRoot = prefs.libraryRoot,
                    currentPath = path,
                    folder = FolderTree.contentOf(tracks, path),
                    selectedTrackIds = emptySet(),
                )
            }
        }
    }

    // --- Ordner-Navigation ----------------------------------------------

    fun openFolder(path: String) = navigateTo(path)

    fun navigateUp() {
        val current = _state.value.currentPath
        if (current.isNotEmpty()) navigateTo(FolderTree.parentOf(current))
    }

    fun goToStartFolder() = navigateTo(_state.value.libraryRoot)

    private fun navigateTo(path: String) {
        _state.update {
            it.copy(
                currentPath = path,
                folder = FolderTree.contentOf(it.tracks, path),
                selectedTrackIds = emptySet(),
            )
        }
    }

    /** Aktuellen Ordner als Startverzeichnis markieren (bzw. Markierung entfernen). */
    fun toggleStartFolder() {
        val current = _state.value
        val newRoot = if (current.currentIsStartFolder) "" else current.currentPath
        viewModelScope.launch {
            if (newRoot.isEmpty()) settings.clearLibraryRoot() else settings.setLibraryRoot(newRoot)
            _state.update {
                it.copy(
                    libraryRoot = newRoot,
                    message = if (newRoot.isEmpty()) "Start folder cleared"
                    else "Library will now start in ${newRoot.trimEnd('/')}",
                )
            }
        }
    }

    fun setViewMode(mode: LibraryViewMode) {
        viewModelScope.launch { settings.setLibraryViewMode(mode) }
        _state.update { it.copy(viewMode = mode, selectedGroup = null, selectedTrackIds = emptySet()) }
    }

    fun openGroup(group: String) = _state.update { it.copy(selectedGroup = group) }

    fun closeGroup() = _state.update { it.copy(selectedGroup = null) }

    // --- Mehrfachauswahl & Verschieben -----------------------------------

    fun toggleSelection(trackId: Long) {
        _state.update {
            val selected = if (trackId in it.selectedTrackIds) {
                it.selectedTrackIds - trackId
            } else {
                it.selectedTrackIds + trackId
            }
            it.copy(selectedTrackIds = selected)
        }
    }

    fun clearSelection() = _state.update { it.copy(selectedTrackIds = emptySet()) }

    fun moveSelectedTo(targetPath: String) {
        val tracks = _state.value.selectedTracks
        if (tracks.isEmpty()) return
        viewModelScope.launch {
            when (val result = repository.moveTracks(tracks, targetPath)) {
                is MoveResult.NeedsConfirmation -> {
                    pendingMove = result.tracks to targetPath
                    _writeRequest.value = result.request
                }
                is MoveResult.Moved -> finishMove("Moved ${result.count} file(s)")
                is MoveResult.Collision -> _state.update {
                    it.copy(
                        message = "Move cancelled — already exists in target: " +
                            result.fileNames.joinToString(", "),
                    )
                }
                is MoveResult.Failed -> _state.update {
                    it.copy(message = "Move failed: ${result.message}")
                }
            }
        }
    }

    /** Ergebnis des System-Bestätigungsdialogs (fremde Dateien). */
    fun onWriteRequestResult(granted: Boolean) {
        _writeRequest.value = null
        val (tracks, target) = pendingMove ?: return
        pendingMove = null
        if (!granted) {
            _state.update { it.copy(message = "Move cancelled") }
            return
        }
        viewModelScope.launch {
            when (val result = repository.applyMove(tracks, target)) {
                is MoveResult.Moved -> finishMove("Moved ${result.count} file(s)")
                is MoveResult.Failed -> _state.update {
                    it.copy(message = "Move failed: ${result.message}")
                }
                else -> finishMove(null)
            }
        }
    }

    private fun finishMove(message: String?) {
        _state.update { it.copy(selectedTrackIds = emptySet(), message = message) }
        loadLibrary(jumpToRoot = false)
    }

    fun consumeMessage() = _state.update { it.copy(message = null) }

    /** Alle Ordner mit Audio-Inhalt — Auswahlliste für „Move to…". */
    fun moveTargets(): List<String> = FolderTree.allFolders(_state.value.tracks)
}
