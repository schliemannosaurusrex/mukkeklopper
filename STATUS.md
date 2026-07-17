# MukkeKlopper — Projektstatus

**Stand:** 2026-07-17  
**App-ID:** `de.schliemannosaurusrex.mukkeklopper`  
**Publisher:** SchliemannosaurusRex  

---

## Technischer Stack

| Komponente | Technologie |
|-----------|-------------|
| Plattform | Android (primär), Linux (nachgelagert) |
| Sprache | Kotlin |
| UI | Jetpack Compose + Material3 |
| Player | Media3 (ExoPlayer + MediaLibraryService, `CastPlayer` für Chromecast) |
| Bild-Loading | Coil |
| Navigation | Navigation Compose |
| Build | Gradle 9.6.1 · AGP 9.2.1 · Kotlin 2.2.10 |
| minSdk | 30 (Android 11) |
| targetSdk | 35 (Android 15) |
| Testgerät | Pixel 8 Pro, **Android 17 (API 37)** — Local Network Protection aktiv |
| Emulator (CI-frei) | AVD `MukkeKlopper_Test`, `google_apis;x86_64` API 35, headless via `adb` |

---

## Abgeschlossen ✅

### Phase 1 — Android-Fundament

- Gradle-Projekt vollständig aufgesetzt (Version Catalog, AGP, Kotlin Compose Plugin)
- `AndroidManifest.xml` mit allen Permissions:
  - `READ_MEDIA_AUDIO`, `INTERNET`, `ACCESS_NETWORK_STATE`
  - `ACCESS_FINE_LOCATION` (Modus B, deklariert ab v1)
  - `FOREGROUND_SERVICE_MEDIA_PLAYBACK`, `FOREGROUND_SERVICE_DATA_SYNC`
  - `ACCESS_LOCAL_NETWORK` (forward-declared für Android 17 / API 37)
- Launcher-Icon (adaptiv, Teal-Hintergrund + Play-Triangle) — **ersetzt 2026-07-15**, siehe unten
- `assembleDebug` baut fehlerfrei durch ✅

### Icon-Branding (2026-07-15)

- Ursprünglicher Plan „SVG-Icon von Hand nachbauen" verworfen — handgezeichnete Vektorpfade für ein
  fotorealistisches Faust-Motiv sahen unbrauchbar aus; SVG ist für dieses Motiv das falsche Werkzeug.
- Referenz-Bild war zusätzlich nur 137×140 px (Thumbnail) — zu klein zum Hochskalieren.
- Stattdessen: Icon (Faust + Audio-Equalizer + Impact-Effekt, dunkler Hintergrund) per KI-Bildgenerierung
  in 1024×1024 neu erzeugt, weiße Rundungs-Artefakte in den Ecken gepatcht, Master abgelegt unter
  `assets/icon/mukkeklopper-icon-1024.png`.
- Daraus abgeleitet:
  - `assets/icon/play-store-512.png` — 512×512 für Play-Store-Listing
  - `android/app/src/main/res/mipmap-{mdpi,hdpi,xhdpi,xxhdpi,xxxhdpi}/ic_launcher.png` + `ic_launcher_round.png`
    (Raster-Fallback, circular-masked für die Round-Variante)
  - `android/app/src/main/res/drawable-nodpi/ic_launcher_foreground_art.png` (auf 88 % Safe-Zone-Inset
    skaliert) — referenziert von `ic_launcher_foreground.xml` (jetzt `<bitmap>` statt Vektor-Pfaden)
  - `ic_launcher_background.xml` — Solid-Color `#060606` (ersetzt Teal `#00897B`)
- `mipmap-anydpi-v26/ic_launcher.xml` / `ic_launcher_round.xml` unverändert (referenzieren weiter
  `@drawable/ic_launcher_background` + `@drawable/ic_launcher_foreground`).
- `assembleDebug` nach Umstellung erneut grün.

### Phase 2 — Audio-Player-Kern

**Player:**
- `MukkePlayerService` — `MediaSessionService` mit `ExoPlayer`, Notification mit `PendingIntent` zur `MainActivity`
- `PlayerViewModel` — `MediaController` via `SessionToken`, `StateFlow<PlayerState>`, Position-Polling alle 500 ms
- `PlayerState` — vollständiger Playback-State (Titel, Artist, Album, Artwork, Position, Duration, Shuffle, Repeat)

**Library:**
- `MediaStoreRepository` — Tracks + Alben via `MediaStore.Audio`, sortiert nach Artist/Album/Track
- `LibraryViewModel` — Permission-Guard, async Laden via Coroutines
- `Track` / `Album` — Datenmodelle mit URI, AlbumArt-URI, Dauer

**UI (Jetpack Compose):**
- `MukkeKlopperApp` — Root-Composable mit `Scaffold` + Bottom Navigation Bar
- `LibraryScreen` — Permission-Request (`READ_MEDIA_AUDIO`), LazyColumn, Tap → sofortiger Play
- `NowPlayingScreen` — Album-Art (Coil `AsyncImage`), Progress-Slider mit Seek, Play/Pause/Skip/Shuffle/Repeat
- `SettingsScreen` — Grundstruktur mit Section-Headern (Platzhalter für Phase 5)
- `Navigation.kt` — 3 Routen: Library · Now Playing · Settings

**Util:**
- `FormatUtil.kt` — `formatDuration(ms: Long): String` (MM:SS)
- Theme: dynamische Material3-Farben (Android 12+), Fallback Teal-Palette

**Bugfix (2026-07-14, am Gerät verifiziert):** Nach Track-Tap → Now Playing führte der
Library-Tab nicht zurück zur Titelliste. Ursache: Der Track-Tap navigierte „plain" ohne
die Bottom-Bar-Optionen; so ein Eintrag überstand das `popUpTo`/`restoreState` des Tabs.
Fix: zentrale `navigateToTab()`-Extension in `MukkeKlopperApp.kt`, die von Bottom-Bar **und**
Track-Tap verwendet wird. Debug-Logging der Navigation unter Tag `MukkeNav`.

### Phase 5 — Settings

- `SettingsRepository` — Preferences DataStore (`settings`), alle 6 Keys gem. Plan:
  `server_host`, `server_port` (Default 22, validiert 1–65535), `pinned_hostkey` (TOFU),
  `require_vpn_outside_home` (Default true), `sync_mode_b_enabled` (Default false), `home_ssids`
- `SettingsViewModel` — `StateFlow<MukkeSettings>` via `stateIn`, Setter-Methoden
- `SettingsScreen` — komplett auf echte Felder umgebaut:
  - Sync Server: Host/Port per Dialog editierbar, gepinnter Host-Key anzeig- und löschbar
    („Forget key" mit Bestätigungsdialog)
  - Network: VPN-Pflicht-Switch; Modus-B-Switch mit **Prominent Disclosure Dialog**
    vor dem `ACCESS_FINE_LOCATION`-Request (Play-Policy) — Modus B wird nur bei
    erteilter Permission aktiviert
  - Home-SSID-Liste (nur sichtbar bei aktivem Modus B): Hinzufügen/Entfernen

### Phase 3 — Network-Gating

- `NetworkGatekeeper.canSync()` → `SyncDecision` (sealed interface):
  `Allowed` · `NotConfigured` · `Blocked` · `MitmWarning(pinned, presented)` · `Unreachable(reason)`
  - [1] Heimnetz-Check — Modus A: TCP-Probe `server_host:server_port`, Timeout 3 s;
    Modus B: SSID ∈ `home_ssids` (via `WifiManager.connectionInfo`, braucht Location-Permission)
  - [2] VPN-Check — `ConnectivityManager` → `TRANSPORT_VPN`; blockiert nur wenn
    außerhalb Heimnetz **und** `require_vpn_outside_home` **und** kein VPN
  - [3] SSH-Handshake via `sshj 0.38.0` + Host-Key-Pinning: Fingerprint per TOFU in
    DataStore (`pinned_hostkey`), Mismatch → `MitmWarning`, Key wird **nicht** überschrieben
- Settings: „Test connection"-Eintrag führt `canSync()` aus und zeigt das Ergebnis an
  (verifiziert Gating + pinnt den Key vor dem ersten Sync)
- ProGuard-Regeln für sshj/BouncyCastle/eddsa ergänzt, `packaging.resources.excludes` für
  META-INF-Konflikte (signierte BC-Jars) — `assembleDebug` **und** `assembleRelease` (R8) bauen ✅
- Der WireGuard-Intent aus Phase 3 wurde mit der Sync-UI in Phase 4 umgesetzt ✅

### Phase 4 — SSH-Sync-Engine (2026-07-14)

**Secrets & Auth:**
- `SecretStore` — AES-256-GCM, Schlüssel im Android Keystore (`mukkeklopper_secrets`),
  Chiffrat (IV+Ciphertext, Base64) im DataStore
- `SshKeys` — Ed25519-Paar in-App generiert (nur der 32-Byte-Seed wird verschlüsselt
  persistiert, Keys werden deterministisch abgeleitet); OpenSSH-Export
  `ssh-ed25519 <b64> mukkeklopper@android` für authorized_keys.
  **Achtung:** sshj 0.38 nutzt `net.i2p.crypto:eddsa` für Ed25519, deklariert es aber
  nur als *runtime*-Dependency → in `libs.versions.toml`/`build.gradle.kts` explizit
  als `implementation` ergänzt
- Auth wählbar: `authPassword` (verschlüsselt gespeichert) oder `authPublickey`

**Engine (`SyncEngine`):**
- Strikte Host-Key-Prüfung gegen den gepinnten Fingerprint (Gate muss vorher gelaufen sein;
  ohne gepinnten Key bricht der Sync mit Hinweis auf „Test connection" ab)
- Remote-Inventur: rekursives SFTP-Listing (nur Audio-Extensions, `..`-Segmente gefiltert)
- Lokale Inventur: MediaStore-Query `RELATIVE_PATH LIKE 'Music/MukkeKlopper/%'`
- Delta: neu / Größe ungleich / Remote-mtime neuer → Download mit `IS_PENDING`,
  bestehende Dateien werden via `openOutputStream(uri, "wt")` überschrieben (App ist Owner)
- Löschungen: nur nach Bestätigung pro Lauf (`ConfirmDeletions`-State); im
  Hintergrund-Sync nie — dort nur als „pending" gezählt

**Orchestrierung:**
- `SyncManager` (Singleton) — `StateFlow<SyncState>` (Idle/CheckingNetwork/Blocked/
  Connecting/Indexing/Downloading/ConfirmDeletions/Finished/Failed), max. ein Lauf,
  Cancel + Lösch-Bestätigung via `CompletableDeferred`
- `MukkeSyncService` — Foreground-Service (dataSync) mit Fortschritts-Notification
  (Kanal „sync", Cancel-Action); `POST_NOTIFICATIONS` im Manifest ergänzt
- `SyncWorker` — periodischer WorkManager-Sync (täglich, Constraint CONNECTED),
  Toggle „Automatic daily sync" in den Settings

**UI:**
- Neuer **Sync-Tab** (`SyncScreen` + `SyncViewModel`, Bottom-Bar hat jetzt 4 Einträge):
  Server-Info-Karte, „Sync now", Live-Fortschritt (Datei x/y, Byte-Progress), Cancel,
  Blocked-Banner **mit WireGuard-Intent** (App öffnen bzw. Play-Store-Fallback),
  MITM-/Unreachable-Karten, Lösch-Bestätigungsdialog, Ergebnis-Karte
- Settings-Sektion „Sync account": Username, Remote-Ordner, Auth-Methode (Radio-Dialog),
  Passwort-Dialog (maskiert; leer = löschen), SSH-Key generieren/anzeigen/kopieren/
  regenerieren (mit Warnung), Auto-Sync-Switch

Neue Settings-Keys: `server_user`, `auth_method`, `auth_secret`, `ssh_key_seed`,
`remote_base_path`, `auto_sync_enabled`.

`assembleDebug` **und** `assembleRelease` (R8) bauen ✅

### Phase 4 — Geräteverifikation (2026-07-14, Pixel 8 Pro, Android 17 / API 37)

**End-to-End am echten Server (`<sync-host>`, SSH-Key-Auth) durchgespielt — alles grün:**

| Test | Ergebnis |
|------|----------|
| „Test connection" | „OK — host key verified"; gepinnter Fingerprint stimmt mit dem echten Ed25519-Host-Key von `<sync-host>` überein ✅ |
| SSH-Key in-App generieren + `authorized_keys` | Publickey-Auth funktioniert ✅ |
| Erster Sync (3 Dateien, MP3 + FLAC) | „3 downloaded"; Ordnerstruktur 1:1 unter `Music/MukkeKlopper/Testartist/Testalbum/`, Größen exakt ✅ |
| Zweiter Sync (Delta) | „0 downloaded, 3 up to date" ✅ |
| Datei server-seitig gelöscht → „Keep files" | Datei bleibt lokal, Ergebnis „1 deletions pending" ✅ |
| Dasselbe → „Delete" | Datei + MediaStore-Eintrag entfernt, „1 deleted locally" ✅ |
| MediaStore-Indexierung | Gesyncte Titel mit `is_music=1` indexiert → in der Library sichtbar ✅ |

**Vier Bugs gefunden und behoben (alle nur am Gerät sichtbar, nicht im Build):**

1. **Crash beim Verbindungstest** — `WifiManager.getConnectionInfo()` wirft `SecurityException`
   ohne `ACCESS_WIFI_STATE`; die Permission fehlte im Manifest (Location allein genügt nicht).
   Zusätzlich ist der SSID-Abruf jetzt in `runCatching` gekapselt und fällt bei fehlender
   SSID auf Modus A (TCP-Probe) zurück, statt zu crashen.
2. **Android 17 Local Network Protection** — LAN-Verbindungen brauchen ab API 37 die
   Runtime-Permission `ACCESS_LOCAL_NETWORK`; ohne sie werden Pakete **stillschweigend**
   verworfen (sichtbar nur als Connect-Timeout „Server unreachable"). Neu:
   `ui/LocalNetworkPermission.kt` fordert sie vor „Test connection" und „Sync now" an.
3. **`no such algorithm: X25519 for provider BC`** — Androids eingebauter BouncyCastle-Provider
   ist beschnitten, sshj fordert Algorithmen aber explizit bei Provider „BC" an. Fix:
   `MukkeKlopperApplication` ersetzt beim Start das System-BC durch das volle BouncyCastle
   (`bcprov-jdk18on` explizit als `implementation` ergänzt) → curve25519-sha256 läuft.
4. **Auto-Capitalize in den Settings-Dialogen** — die Tastatur machte aus `/home/...` ein
   `/Home/...` (auf Linux fatal). `TextFieldDialog` nutzt jetzt
   `KeyboardCapitalization.None` + `autoCorrectEnabled = false`.

**Noch nicht am Gerät getestet:** Blocked-Banner + WireGuard-Intent (erfordert Test außerhalb
des Heimnetzes ohne VPN), Abbruch während eines laufenden Downloads, MITM-Fall.

### Phase 8 — Ordner-Library (Pulsar-Stil, 2026-07-14)

- `library/FolderTree.kt` — Ordnerbaum rein aus MediaStore-`RELATIVE_PATH` abgeleitet
  (kein SAF/Dateisystem-Zugriff): Kind-Ordner + direkte Titel pro Pfad, Breadcrumbs,
  Eltern-Navigation, Ordnerliste für den Ziel-Picker
- `LibraryViewModel` — komplett umgebaut: vier Ansichtsmodi (**Ordner** (Default) ·
  Alben · Artists · Alle Titel, persistiert), Ordnernavigation, Mehrfachauswahl
  (Long-Press), Verschieben
- **Startverzeichnis** (`library_root`): „Markieren statt tippen" — Stern-Icon in der
  Titelzeile setzt/löscht den aktuellen Ordner als Startordner; Library öffnet dort,
  „Home"-Button springt zurück, Navigation oberhalb bleibt erlaubt
- **Verschieben** (rein lokal): eigene (gesyncte) Dateien per direktem
  `RELATIVE_PATH`-Update; fremde via `MediaStore.createWriteRequest()` →
  System-Bestätigungsdialog pro Batch; Namenskollision im Ziel → Abbruch, kein
  Überschreiben
- `LibraryScreen` — Breadcrumb-Zeile, View-Mode-Menü, Selection-AppBar mit
  „Move to…"-Dialog, Snackbar-Feedback
- Neue Settings-Keys: `library_root`, `library_view_mode`

`assembleDebug` **und** `assembleRelease` (R8) bauen ✅ — **Geräteverifikation steht noch aus.**

### Umbenennung KaniAmp → MukkeKlopper (2026-07-14/15)

- Vollständige Umbenennung: App-ID `de.schliemannosaurusrex.kaniamp` →
  `de.schliemannosaurusrex.mukkeklopper`, Package-Verzeichnis verschoben, alle
  `Kani*`-Klassen umbenannt (`KaniAmpApplication` → `MukkeKlopperApplication`,
  `KaniPlayerService` → `MukkePlayerService`, `KaniSyncService` → `MukkeSyncService`,
  `KaniAmpApp` → `MukkeKlopperApp`, `KaniAmpTheme` → `MukkeKlopperTheme`,
  `KaniSettings` → `MukkeSettings`, Farbkonstanten, Log-Tags)
- Konstanten mitgezogen: `SYNC_ROOT` = `Music/MukkeKlopper/`, Keystore-Alias
  `mukkeklopper_secrets`, SSH-Key-Kommentar `mukkeklopper@android`,
  WorkManager-Name `mukkeklopper_periodic_sync`
- `idea.md`, `PLAN.md`, dieses Dokument auf MukkeKlopper/`mukkeklopper.app` umgestellt
- **Bewusst in Kauf genommen:** App erscheint auf Geräten mit installierter alter
  `kaniamp`-App als komplette Neuinstallation (anderer App-ID); alte Daten/Ordner
  bleiben zurück und müssen manuell entfernt werden

### Android-SDK + Emulator unter Windows (2026-07-15)

- Command-Line-Tools installiert (ohne volles Android Studio), `ANDROID_HOME` →
  `C:\Android\Sdk`, `local.properties` auf Windows-Pfad umgeschrieben
- `sdkmanager`-Java-Versions-Check (Fehldetektion bei JDK 25) via
  `SKIP_JDK_VERSION_CHECK=1` umgangen
- AVD `MukkeKlopper_Test` (`google_apis;x86_64`, API 35) angelegt, Hypervisor
  (WHPX) aktiv → Emulator bootet headless, per `adb wait-for-device` verifiziert

### Phase 4b — Sync-Fehlerdetails & Config-Export (2026-07-15)

**Fehlerdetails:**
- `SyncFailure.kt` — `FailureReason`-Enum + `SyncFailure`-Datenklasse (beide
  `@Serializable`), `classifyFailure()` mappt Exceptions/SFTP-Statuscodes auf
  Klartext-Gründe
- `SyncState.Finished` trägt jetzt `failures: List<SyncFailure>` statt `failed: Int`
- Letzter Lauf wird als JSON in `SettingsRepository` persistiert
  (`kotlinx.serialization` als neue Dependency + Plugin ergänzt)
- `SyncScreen` — Ergebnis-Karte zeigt „View n failed files" → neue Route
  `sync_failures` (`SyncFailuresScreen`): Liste mit Pfad + Klartext-Grund,
  „Retry failed" (`SyncManager.runSync(retryOnly = …)`), „Copy report" (Clipboard)

**Config-Export/-Import:**
- `ConfigExport.kt` — Datenmodell (Version, Server inkl. gepinntem Host-Key,
  Network, Library, Sync); Secrets standardmäßig **nicht** exportiert
- `ConfigCrypto.kt` — Opt-in „Include credentials" mit Passphrase
  (PBKDF2-HMAC-SHA256, AES-256-GCM)
- `ConfigExporter.kt` — Serialisierung/Deserialisierung ↔ `SettingsRepository`
- `SettingsScreen` — neue Sektion „Backup": Export/Import via
  `CreateDocument`/`OpenDocument`, Datei `mukkeklopper-config-<yyyyMMdd>.json`,
  Import mit Zusammenfassungs-Dialog

`assembleDebug` **und** `assembleRelease` (R8) bauen ✅ — **am Emulator
end-to-end verifiziert** (Export → Datei im Picker gespeichert → Inhalt
geprüft → Import → Zusammenfassung → erfolgreich übernommen, keine Crashes).

### Phase 9 — Equalizer (2026-07-15)

- `EqualizerManager` (Singleton) — kapselt `android.media.audiofx.Equalizer`,
  `BassBoost`, `Virtualizer`; `attach(audioSessionId)`/`release()`, exponiert
  Geräte-Fähigkeiten (Bandanzahl, Frequenzen, Presets) und aktuelle Settings
  als `StateFlow`
- `MukkePlayerService` — hört auf `onAudioSessionIdChanged` des ExoPlayers und
  bindet/löst die Effekte entsprechend
- Persistenz neuer Settings-Keys (Enabled, Preset, Band-Level, Bass-/
  Virtualizer-Stärke) via `SettingsRepository`, Anwendung beim Start
- `EqualizerViewModel` + `EqualizerScreen` — Enable-Switch, Preset-Chips,
  Band-Slider (gerätabhängige Anzahl/Frequenzen), Bass-Boost-/
  Virtualizer-Slider; erreichbar über neues Icon in `NowPlayingScreen`

`assembleDebug` **und** `assembleRelease` (R8) bauen ✅ — **am Emulator
verifiziert**: reale Geräte-Fähigkeiten wurden korrekt ausgelesen (5 Bänder:
60 Hz/230 Hz/910 Hz/3 kHz/14 kHz, Presets Normal/Classical/Dance/Flat/Folk/…),
Enable-Switch + Slider reagieren, keine Crashes im Logcat.

### Debug-Log (2026-07-15)

- `debug/AppLog.kt` — Singleton mit In-Memory-Ringpuffer (`ArrayDeque`, Cap 1000,
  `StateFlow<List<LogEntry>>`); `i()`/`w()`/`e()` werden **immer** nach Logcat und
  in den Puffer geschrieben, `d()` (Debug/Verbose) nur wenn der Toggle aktiv ist
- Neuer Settings-Key `debug_log_enabled` (`SettingsRepository`), beim App-Start
  in `AppLog.setDebugEnabled(...)` übernommen und live nachverfolgt
  (`MukkeKlopperApplication` sammelt den Settings-Flow)
- `ui/DebugLogScreen.kt` — farbcodierte, neueste-oben `LazyColumn`, Aktionen
  „Copy"/„Share" (`Intent.ACTION_SEND`) und „Clear"; erreichbar über
  `SettingsScreen` → Sektion „Debug log" (Switch „Enable debug logging" +
  Eintrag „View log")
- Verdrahtet in `SyncEngine`, `NetworkGatekeeper`, `SyncManager`,
  `MukkeSyncService`, `SyncWorker`, `EqualizerManager`, `ConfigExporter`,
  `PlayerViewModel`, `MukkeKlopperApplication` (BC-Provider-Swap)
- **SLF4J-Bridge** (`debug/MukkeSlf4jProvider.kt` +
  `META-INF/services/org.slf4j.spi.SLF4JServiceProvider`): sshj nutzt intern
  SLF4J; ohne eigene Bindung fällt SLF4J 2.x auf einen stillen NOP-Logger
  zurück und Handshake-/Auth-Details (welche Auth-Methoden der Server anbietet,
  warum eine Authentifizierung scheitert) gehen verloren. Eigene
  `SLF4JServiceProvider`/`ILoggerFactory`/`AbstractLogger`-Implementierung
  leitet jeden sshj-Log-Aufruf nach `AppLog` um (DEBUG/TRACE nur bei aktivem
  Toggle, WARN/ERROR immer); `slf4j-api` musste explizit als `implementation`
  ergänzt werden (kam über sshj nur transitiv als Runtime-Dependency);
  ProGuard-Keep-Regeln für `org.slf4j.**` und den eigenen Provider ergänzt

`assembleDebug` **und** `assembleRelease` (R8) bauen ✅.

### Sync-Fix — Passwort-Authentifizierung (2026-07-15)

- Diagnose: Publickey-Auth war am echten Server (`<sync-host>`) bereits verifiziert,
  Passwort-Auth hingegen nie. Die rohe sshj-`UserAuthException`
  („Exhausted available authentication methods") wurde 1:1 durchgereicht,
  ohne zu verraten, ob der Server `PasswordAuthentication` überhaupt anbietet
  oder ob das Passwort falsch ist
- `SyncEngine.authenticate()` fängt `UserAuthException` gezielt ab und wirft
  eine `SyncConfigException` mit klarerem Text plus Handlungsempfehlung
  (Passwort prüfen oder auf die bereits verifizierte Public-Key-Methode
  wechseln); `SyncState.Failed` zeigt jetzt diese Meldung
- Mit der SLF4J-Bridge (siehe Debug-Log oben) liefert der nächste
  Passwort-Sync-Versuch im Debug-Log die vom Server tatsächlich angebotenen
  Auth-Methoden — damit lässt sich Server-Konfiguration von falschem Passwort
  zweifelsfrei unterscheiden, ohne weiter zu raten
- **Empfehlung an den User:** bis zur endgültigen Klärung die bereits am
  echten Server verifizierte Public-Key-Methode nutzen

### Queue-Anzeige in Now Playing (2026-07-15)

- Ordner-Wiedergabe ohne Unterordner war bereits korrekt implementiert
  (`FolderTree.contentOf()` liefert nur direkte Titel des Pfades) — keine
  Änderung nötig, nur bestätigt
- `PlayerViewModel` — neuer `StateFlow<List<QueueItem>>`, befüllt aus dem
  `MediaController` (`mediaItemCount`/`getMediaItemAt`) bei
  `onTimelineChanged`/`onMediaItemTransition`; neue Methode
  `playQueueIndex(index: Int)` → `controller.seekTo(index, 0)`
- `ui/QueueScreen.kt` — Liste der aktuellen Warteschlange, aktueller Titel
  hervorgehoben, Tap springt zum Titel; neue Route `queue`
- `NowPlayingScreen` — neuer Icon-Button (Queue-Symbol) neben dem
  Equalizer-Button

### Chromecast / Google Home (2026-07-15)

- Neue Dependencies: `androidx.media3:media3-cast`,
  `com.google.android.gms:play-services-cast-framework`,
  `androidx.mediarouter:mediarouter`, `org.nanohttpd:nanohttpd`
- `cast/MukkeCastOptionsProvider.kt` — generischer
  `CastMediaControlIntent.DEFAULT_MEDIA_RECEIVER_APPLICATION_ID`, kein eigener
  Cast-Receiver nötig; Registrierung via `OPTIONS_PROVIDER_CLASS_NAME` in
  `AndroidManifest.xml`
- `cast/LocalMediaServer.kt` — NanoHTTPD-Server, löst `content://`-Track-URIs
  über den `ContentResolver` auf und streamt sie mit korrektem MIME-Type und
  HTTP-Range-Unterstützung (Seeking); zufälliges Token pro Start gegen
  unautorisierten LAN-Zugriff; läuft nur während einer aktiven Cast-Session
- `player/CastMediaItemConverter.kt` — eigener `MediaItemConverter` für
  `CastPlayer`, ersetzt die `content://`-URI durch
  `http://<Handy-LAN-IP>:<port>/track/<id>?token=…` (IP-Ermittlung via
  `ConnectivityManager`/`LinkProperties`); Titel/Artist/Album bleiben
  erhalten, Album-Art wird in v1 ausgelassen (bekannte Einschränkung)
- `MukkePlayerService` — hält zusätzlich einen `CastPlayer`; ein
  `SessionAvailabilityListener` swapt den aktiven Player der `MediaSession`
  bei Cast-Verbindung/-Trennung und startet/stoppt `LocalMediaServer` synchron
  dazu
- `ui/CastButton.kt` — gemeinsame Composable (`AndroidView` um
  `androidx.mediarouter.app.MediaRouteButton`), in den Top-Bars von
  `LibraryScreen` und `NowPlayingScreen`; fordert proaktiv die
  `ACCESS_LOCAL_NETWORK`-Permission (Android 17+) an, sobald der Button das
  erste Mal sichtbar wird — sonst verwirft das Betriebssystem eingehende
  LAN-Requests des Cast-Geräts stillschweigend (identische Fehlerklasse wie
  der Sync-Bug aus Phase 4)
- **Bekannte Einschränkungen:** nur generischer Media-Receiver ohne eigenes
  Cast-UI-Branding, kein Album-Cover auf dem Empfänger in v1, Handy und
  Cast-Gerät müssen im selben LAN sein, Handy muss erreichbar/foregrounded
  bleiben (wie beim Sync)

`assembleDebug` **und** `assembleRelease` (R8) bauen ✅ — **am Pixel 8 Pro
verifiziert:** Cast-Button zeigt den echten Geräte-Chooser ("Streamen auf")
mit sieben realen Google-Home-/Chromecast-Zielen im Heimnetz (Badezimmer,
Büro, Hauke, Schlafzimmer, Terrasse, Terrasse_1, Wohnzimmer); Verbindungstest
zu einem Gerät vom User bestätigt.

**Bug gefunden und behoben (Gerätetest 2026-07-15):** Antippen des
Cast-Buttons crashte zuverlässig mit
`IllegalStateException: The activity must be a subclass of FragmentActivity`
(`MediaRouteButton.showDialog` → `DialogFragment`). `MainActivity` erbte von
`androidx.activity.ComponentActivity`, `MediaRouteButton` benötigt aber eine
`androidx.fragment.app.FragmentActivity`. Fix: `MainActivity` erbt jetzt von
`FragmentActivity` (Compose-`setContent` funktioniert unverändert, da
`FragmentActivity` selbst eine `ComponentActivity`-Unterklasse ist).

### Android Auto (2026-07-15)

- `MukkePlayerService` von `MediaSessionService` auf `MediaLibraryService`
  umgestellt (`MediaLibrarySession` + `MediaLibrarySession.Callback`):
  - `onGetLibraryRoot` → Wurzel-Knoten „MukkeKlopper"
  - `onGetChildren(parentId, …)` → liefert je Ordner-Pfad (analog
    `FolderTree.contentOf()`) Unterordner + direkte Titel, exakt dieselbe
    Ordnerstruktur wie in der App (User-Entscheidung: Ordner-Browse statt
    Alben/Artists-Wurzel)
  - `onGetItem(mediaId)` → einzelnes `MediaItem` per Track-ID auflösen
  - `onSetMediaItems` → Tippen auf einen Titel in Auto lädt denselben Ordner
    (ohne Unterordner) als Queue, analog zu `LibraryScreen`s `FolderList`
- Neue `res/xml/automotive_app_desc.xml` (`<uses name="media"/>`)
- `AndroidManifest.xml` — `<meta-data android:name="com.google.android.gms.car.application">`
  im `<application>`-Element; `MukkePlayerService`-Intent-Filter um
  `androidx.media3.session.MediaLibraryService` und
  `android.media.browse.MediaBrowserService` ergänzt (zusätzlich zum
  bestehenden `MediaSessionService`)
- Play/Pause/Skip/Shuffle/Repeat funktionieren unverändert über dieselbe
  `MediaSession` — keine Änderung an `PlayerViewModel`/`NowPlayingScreen`
  nötig

`assembleDebug` **und** `assembleRelease` (R8) bauen ✅.

**Verifikation auf Android-Automotive-OS-Emulator (2026-07-16, API 33,
`android-automotive` x86_64):** App installiert und startet crash-frei,
erscheint mit neuem Icon im App-Grid, `android.media.browse.MediaBrowserService`
ist system-seitig korrekt registriert (`dumpsys package` bestätigt
`MukkePlayerService` als Resolver für `MediaSessionService`,
`MediaLibraryService` und `MediaBrowserService`). Erscheint erwartungsgemäß
**nicht** in der „Media apps"-Quellenauswahl der Car-Media-App — das ist kein
Bug, sondern weil das Manifest gezielt für **Android Auto** (Phone-Projection,
`com.google.android.gms.car.application`) konfiguriert ist, nicht für die
eigenständigen, strengeren Anforderungen von nativem **Android Automotive
OS** (separates `com.android.automotive`-Meta-Data, `uses-feature
android.hardware.type.automotive`, kein Launcher-Activity in einem eigenen
Automotive-Modul — eigene Zielplattform, hier nicht angestrebt).

**Echter DHU-Test (Android Auto Phone-Projection) weiterhin offen:**
DHU 2.0 ist installiert (`%ANDROID_SDK%\extras\google\auto\`), scheitert aber
am fehlenden Android-Auto-App-Build auf dem Test-Emulator — Play Store
erfordert Google-Anmeldung (nicht headless automatisierbar), ein
Sideload von APKMirror ist an Cloudflares Bot-Schutz gescheitert (kein
scriptbarer Download möglich, kein Umgehungsversuch unternommen). Nächster
sinnvoller Schritt: echtes Pixel 8 Pro per USB, aktuelle Android-Auto-App aus
dem Play Store, Entwicklermodus + „Head Unit Server starten", dann
`adb forward tcp:5277 tcp:5277` + `desktop-head-unit.exe`.

### Sieben Findings behoben (2026-07-17)

Analyse + Plan: `.todos/2026-07-16-findings-sync-export-cast-ui-playback.md`.

1. **Sync — „has no access to content://media/…":** `SyncEngine` prüft jetzt
   `OWNER_PACKAGE_NAME`; fremd-eigene Einträge (z. B. nach Neuinstallation)
   werden beim manuellen Sync über einen `MediaStore.createWriteRequest`-Dialog
   freigegeben (`SyncState.AwaitWriteAccess`, Launcher in `SyncScreen`) und per
   **delete + insert** ersetzt — die App wird dadurch wieder Owner
   (Self-Healing). Auto-Sync ohne Grant überspringt betroffene Titel mit neuem
   `FailureReason.NOT_OWNED`; `classifyFailure` mappt `SecurityException` nicht
   mehr auf „Unknown error".
2. **Config-Export v2:** `ConfigExport` enthält jetzt `player.equalizer`
   (EqualizerSettings) und `debug.debugLogEnabled`; `CURRENT_VERSION = 2`,
   v1-Dateien bleiben importierbar (neue Felder nullable). Import wendet den
   Equalizer sofort auf die laufende Session an
   (`EqualizerManager.applyImported`, normalisiert Band-Levels auf die
   Geräte-Bandzahl).
3. **Cast:** `transferPlayback()` überträgt Queue, Index, Position und
   Play-Status beim Session-Wechsel in beide Richtungen (Exo ↔ Cast) — vorher
   startete der CastPlayer leer („kein Titel ausgewählt").
4. **Rotation:** `android:screenOrientation="portrait"` an der `MainActivity` —
   das Player-Layout ist nicht für Landscape ausgelegt.
5. **Navigation:** `navigateToTab` poppt Nested-Routen (`equalizer`, `queue`,
   `sync_failures`, `debug_log`) vor dem Tab-Wechsel — Rückkehr-Klick landet
   wieder auf der Tab-Root statt in der alten Unter-Ansicht.
6. **Letzter Titel:** Neuer DataStore-Key `playback_state`
   (`player/PlaybackState.kt`: Queue-Track-IDs, Index, Position), geschrieben
   bei Titelwechsel/Pause/`onDestroy`; Wiederherstellung beim Service-Start
   (still, ohne Auto-Play) und via `onPlaybackResumption`. Gelöschte Tracks
   werden beim Auflösen übersprungen; bewusst nicht Teil des Config-Exports.
7. **Android Auto:** Browse-Einstieg (`ROOT_ID`) zeigt das in der App markierte
   Startverzeichnis (`library_root`) statt der MediaStore-Wurzel.

Build `assembleDebug` grün, Installation + Smoke-Test (Start, Library-Cache,
EqualizerManager-Attach) am Pixel 8 Pro OK. Funktionale Geräteverifikation der
einzelnen Findings: siehe „Offen".

---

## Offen 🔲

### Geräteverifikation der sieben Findings (2026-07-17)

- Sync-Write-Grant-Dialog + Self-Healing am echten Server/Gerät durchspielen
  (betroffener Titel: fremd-eigener MediaStore-Eintrag)
- Cast-Übernahme des laufenden Titels auf Google Home Mini
- Equalizer-Export/Import (v2-Datei) inkl. Live-Anwendung
- Letzter Titel nach App-Neustart im Player-Tab
- Android-Auto-Einstieg im Startordner (DHU oder Fahrzeug)

### Geräteverifikation Phase 8 & Emulator-Grenzen

- Ordnernavigation, Startordner, Verschieben (eigene + fremde Dateien) noch nicht
  am echten Gerät getestet (nur Code-Review + Build)
- SSH-Sync ist im Emulator ohne Zugriff auf das Heimnetz nur eingeschränkt
  testbar (kein `<sync-host>` erreichbar) — Sync-UI/Backup/Equalizer wurden
  daher isoliert am Emulator, die eigentliche Sync-Engine bereits zuvor am
  echten Pixel 8 Pro end-to-end verifiziert (siehe Phase 4 oben)

### Geräteverifikation Debug-Log / Sync-Fix / Queue / Cast / Android Auto (2026-07-15, Pixel 8 Pro)

**Erledigt:**
- Debug-Log-Toggle + `DebugLogScreen`: am Gerät verifiziert — Switch schaltet
  sauber um, „View log" zeigt farbcodierte Einträge mit Zeitstempel
  (`BouncyCastle provider replaced`, `Debug logging disabled/enabled`)
- Cast-Button + echter Geräte-Chooser + Verbindungstest zu einem
  Google-Home-Gerät: vom User bestätigt (siehe Cast-Abschnitt oben,
  inkl. gefundenem und behobenem `FragmentActivity`-Crash)
- Queue-Icon in `NowPlayingScreen` sichtbar neben Cast- und
  Equalizer-Button

**Bug gefunden und behoben (Rename-Nachwirkung):** Der Library-Browser
öffnete auf diesem Gerät weiterhin `Storage/Music/KaniAmp/<artist>/` (leer)
statt `Storage/Music/MukkeKlopper/<artist>/` (voller, echter Bestand) —
Ursache: `library_root` war vor der Umbenennung auf den alten Pfad gepinnt
worden und die Rename-Migration hatte diesen App-internen Einstellungswert
übersehen (physische Dateien und neue Syncs waren bereits korrekt unter
`MukkeKlopper/`). Fix: `SettingsRepository.migrateLegacyLibraryRoot()`
schreibt einen `library_root`-Wert mit dem alten `Music/KaniAmp/`-Präfix
beim App-Start einmalig auf `Music/MukkeKlopper/` um; am Gerät verifiziert
(Datastore-Dump vor/nach Fix, Library öffnet jetzt korrekt mit allen
Ordnern/Trackzahlen).

**Noch offen:**
- Sync-Retry mit Passwort-Methode bei aktivem Debug-Log noch nicht am echten
  Server wiederholt (finale Ursachenklärung Server-Config vs. falsches
  Passwort steht aus)
- Android-Auto-Browse-Baum noch nicht per Desktop Head Unit (DHU) oder im
  Fahrzeug verifiziert

### Zurückgestellt ⏸

- **Phase 6** — Linux-Client (Python)
- **Phase 7** — CI/CD (GitLab: build → sign → upload → promote)

---

## Vorarbeiten (Owner)

Ausführliche, abhakbare Checkliste inkl. Data-Safety-Entwurf und
Privacy-Policy-Draft: [`docs/play-store-checklist.md`](docs/play-store-checklist.md).

- [x] `schliemannosaurusrex.de` registriert
- [ ] Projektseite unter `web/` aufsetzen (Store-Listing-Link,
      Datenschutzerklärung-Hosting) — **in Arbeit**
- [ ] Google Play Console Developer-Konto anlegen (25 $)
- [ ] Upload-Keystore generieren + in Bitwarden ablegen (separat vom lokalen
      Test-Keystore `android/keystore/mukkeklopper-test.jks`)
- [ ] Trademark-Check: DPMA / USPTO für „MukkeKlopper"
- [ ] 12 Tester für Closed Testing organisieren

---

## Dateistruktur

```
workspace/mukkeklopper/
├── idea.md          — Konzept: Netzwerk-Logik & Play-Store-Anforderungen
├── PLAN.md          — Implementierungsplan (alle Phasen)
├── STATUS.md        — dieser Bericht
└── android/
    ├── settings.gradle.kts
    ├── build.gradle.kts
    ├── gradle.properties
    ├── gradle/
    │   ├── libs.versions.toml
    │   └── wrapper/gradle-wrapper.properties
    └── app/
        ├── build.gradle.kts
        ├── proguard-rules.pro
        └── src/main/
            ├── AndroidManifest.xml
            ├── res/
            │   ├── values/{strings,themes}.xml
            │   ├── xml/{backup_rules,data_extraction_rules}.xml
            │   └── drawable + mipmap-anydpi-v26/ (Launcher-Icon)
            ├── res/xml/automotive_app_desc.xml  (Android Auto Media-App-Deklaration)
            ├── resources/META-INF/services/
            │   └── org.slf4j.spi.SLF4JServiceProvider  (Service-Registrierung SLF4J-Bridge)
            └── java/de/schliemannosaurusrex/mukkeklopper/
                ├── MukkeKlopperApplication.kt
                ├── MainActivity.kt
                ├── debug/
                │   ├── AppLog.kt           (Ringpuffer, Toggle, StateFlow, Debug-Log)
                │   └── MukkeSlf4jProvider.kt  (SLF4J-Bridge sshj → AppLog)
                ├── player/
                │   ├── MukkePlayerService.kt  (MediaLibraryService, ExoPlayer+CastPlayer-Swap, Android Auto)
                │   ├── PlayerViewModel.kt     (+ Queue-StateFlow, Phase Queue-UI)
                │   ├── PlayerState.kt
                │   ├── EqualizerManager.kt    (Equalizer/BassBoost/Virtualizer, Phase 9)
                │   ├── EqualizerViewModel.kt
                │   └── CastMediaItemConverter.kt  (content:// → http:// für CastPlayer)
                ├── cast/
                │   ├── MukkeCastOptionsProvider.kt  (Cast-Framework OptionsProvider)
                │   └── LocalMediaServer.kt          (NanoHTTPD, Range-Support, Token-Schutz)
                ├── library/
                │   ├── Track.kt
                │   ├── Album.kt
                │   ├── FolderTree.kt       (Ordnerbaum aus RELATIVE_PATH, Phase 8)
                │   ├── MediaStoreRepository.kt  (+ moveTracks/applyMove, Phase 8)
                │   └── LibraryViewModel.kt
                ├── sync/
                │   ├── SecretStore.kt      (AES-GCM via Android Keystore)
                │   ├── SshKeys.kt          (Ed25519-Generierung + OpenSSH-Export)
                │   ├── SyncState.kt
                │   ├── SyncFailure.kt      (FailureReason, classifyFailure, Phase 4b)
                │   ├── SyncEngine.kt       (SFTP-Delta-Sync → MediaStore, retryOnly, Auth-Fehlermeldung)
                │   ├── SyncManager.kt      (Singleton-Koordinator, StateFlow)
                │   ├── SyncViewModel.kt
                │   ├── SyncWorker.kt       (WorkManager, täglich)
                │   └── MukkeSyncService.kt  (Foreground-Service dataSync)
                ├── network/
                │   └── NetworkGatekeeper.kt
                ├── settings/
                │   ├── SettingsRepository.kt  (+ debug_log_enabled)
                │   ├── SettingsViewModel.kt
                │   ├── ConfigExport.kt     (Export-Datenmodell, Phase 4b)
                │   ├── ConfigCrypto.kt     (Passphrase-Verschlüsselung, Phase 4b)
                │   └── ConfigExporter.kt   (Serialisierung ↔ SettingsRepository, Phase 4b)
                ├── ui/
                │   ├── MukkeKlopperApp.kt
                │   ├── Navigation.kt
                │   ├── NowPlayingScreen.kt   (+ Queue-Button)
                │   ├── QueueScreen.kt        (Warteschlangen-Anzeige)
                │   ├── LibraryScreen.kt      (+ CastButton)
                │   ├── CastButton.kt         (MediaRouteButton + Local-Network-Permission)
                │   ├── SyncScreen.kt
                │   ├── SyncFailuresScreen.kt  (Fehlerliste, Retry, Copy report, Phase 4b)
                │   ├── EqualizerScreen.kt     (Presets, Bänder, Bass/Virtualizer, Phase 9)
                │   ├── DebugLogScreen.kt      (Log-Anzeige, Copy/Share/Clear)
                │   ├── SettingsScreen.kt      (+ Debug-Log-Sektion)
                │   ├── LocalNetworkPermission.kt  (ACCESS_LOCAL_NETWORK, Android 17)
                │   └── theme/
                │       ├── Color.kt
                │       ├── Theme.kt
                │       └── Type.kt
                └── util/
                    └── FormatUtil.kt
```
