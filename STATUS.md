# KaniAmp — Projektstatus

**Stand:** 2026-07-14  
**App-ID:** `de.schliemannosaurusrex.kaniamp`  
**Publisher:** SchliemannosaurusRex  

---

## Technischer Stack

| Komponente | Technologie |
|-----------|-------------|
| Plattform | Android (primär), Linux (nachgelagert) |
| Sprache | Kotlin |
| UI | Jetpack Compose + Material3 |
| Player | Media3 (ExoPlayer + MediaSessionService) |
| Bild-Loading | Coil |
| Navigation | Navigation Compose |
| Build | Gradle 8.14.5 · AGP 8.13.2 · Kotlin 2.0.0 |
| minSdk | 30 (Android 11) |
| targetSdk | 35 (Android 15) |
| Testgerät | Pixel 8 Pro, **Android 17 (API 37)** — Local Network Protection aktiv |

---

## Abgeschlossen ✅

### Phase 1 — Android-Fundament

- Gradle-Projekt vollständig aufgesetzt (Version Catalog, AGP, Kotlin Compose Plugin)
- `AndroidManifest.xml` mit allen Permissions:
  - `READ_MEDIA_AUDIO`, `INTERNET`, `ACCESS_NETWORK_STATE`
  - `ACCESS_FINE_LOCATION` (Modus B, deklariert ab v1)
  - `FOREGROUND_SERVICE_MEDIA_PLAYBACK`, `FOREGROUND_SERVICE_DATA_SYNC`
  - `ACCESS_LOCAL_NETWORK` (forward-declared für Android 17 / API 37)
- Launcher-Icon (adaptiv, Teal-Hintergrund + Play-Triangle)
- `assembleDebug` baut fehlerfrei durch ✅

### Phase 2 — Audio-Player-Kern

**Player:**
- `KaniPlayerService` — `MediaSessionService` mit `ExoPlayer`, Notification mit `PendingIntent` zur `MainActivity`
- `PlayerViewModel` — `MediaController` via `SessionToken`, `StateFlow<PlayerState>`, Position-Polling alle 500 ms
- `PlayerState` — vollständiger Playback-State (Titel, Artist, Album, Artwork, Position, Duration, Shuffle, Repeat)

**Library:**
- `MediaStoreRepository` — Tracks + Alben via `MediaStore.Audio`, sortiert nach Artist/Album/Track
- `LibraryViewModel` — Permission-Guard, async Laden via Coroutines
- `Track` / `Album` — Datenmodelle mit URI, AlbumArt-URI, Dauer

**UI (Jetpack Compose):**
- `KaniAmpApp` — Root-Composable mit `Scaffold` + Bottom Navigation Bar
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
Fix: zentrale `navigateToTab()`-Extension in `KaniAmpApp.kt`, die von Bottom-Bar **und**
Track-Tap verwendet wird. Debug-Logging der Navigation unter Tag `KaniNav`.

### Phase 5 — Settings

- `SettingsRepository` — Preferences DataStore (`settings`), alle 6 Keys gem. Plan:
  `server_host`, `server_port` (Default 22, validiert 1–65535), `pinned_hostkey` (TOFU),
  `require_vpn_outside_home` (Default true), `sync_mode_b_enabled` (Default false), `home_ssids`
- `SettingsViewModel` — `StateFlow<KaniSettings>` via `stateIn`, Setter-Methoden
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
- `SecretStore` — AES-256-GCM, Schlüssel im Android Keystore (`kaniamp_secrets`),
  Chiffrat (IV+Ciphertext, Base64) im DataStore
- `SshKeys` — Ed25519-Paar in-App generiert (nur der 32-Byte-Seed wird verschlüsselt
  persistiert, Keys werden deterministisch abgeleitet); OpenSSH-Export
  `ssh-ed25519 <b64> kaniamp@android` für authorized_keys.
  **Achtung:** sshj 0.38 nutzt `net.i2p.crypto:eddsa` für Ed25519, deklariert es aber
  nur als *runtime*-Dependency → in `libs.versions.toml`/`build.gradle.kts` explizit
  als `implementation` ergänzt
- Auth wählbar: `authPassword` (verschlüsselt gespeichert) oder `authPublickey`

**Engine (`SyncEngine`):**
- Strikte Host-Key-Prüfung gegen den gepinnten Fingerprint (Gate muss vorher gelaufen sein;
  ohne gepinnten Key bricht der Sync mit Hinweis auf „Test connection" ab)
- Remote-Inventur: rekursives SFTP-Listing (nur Audio-Extensions, `..`-Segmente gefiltert)
- Lokale Inventur: MediaStore-Query `RELATIVE_PATH LIKE 'Music/KaniAmp/%'`
- Delta: neu / Größe ungleich / Remote-mtime neuer → Download mit `IS_PENDING`,
  bestehende Dateien werden via `openOutputStream(uri, "wt")` überschrieben (App ist Owner)
- Löschungen: nur nach Bestätigung pro Lauf (`ConfirmDeletions`-State); im
  Hintergrund-Sync nie — dort nur als „pending" gezählt

**Orchestrierung:**
- `SyncManager` (Singleton) — `StateFlow<SyncState>` (Idle/CheckingNetwork/Blocked/
  Connecting/Indexing/Downloading/ConfirmDeletions/Finished/Failed), max. ein Lauf,
  Cancel + Lösch-Bestätigung via `CompletableDeferred`
- `KaniSyncService` — Foreground-Service (dataSync) mit Fortschritts-Notification
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

**End-to-End am echten Server (`finch`, SSH-Key-Auth) durchgespielt — alles grün:**

| Test | Ergebnis |
|------|----------|
| „Test connection" | „OK — host key verified"; gepinnter Fingerprint `64:c6:…:17:b9` = echter Ed25519-Host-Key von finch ✅ |
| SSH-Key in-App generieren + `authorized_keys` | Publickey-Auth funktioniert ✅ |
| Erster Sync (3 Dateien, MP3 + FLAC) | „3 downloaded"; Ordnerstruktur 1:1 unter `Music/KaniAmp/Testartist/Testalbum/`, Größen exakt ✅ |
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
   `KaniAmpApplication` ersetzt beim Start das System-BC durch das volle BouncyCastle
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

---

## Offen 🔲

### Phase 9 — Equalizer (nächster Schritt)

- `audiofx.Equalizer` + BassBoost/Virtualizer an der ExoPlayer-Audio-Session
- Presets + Band-Regler, Persistenz in DataStore, Screen aus Now Playing

### Phase 4b — Sync-Fehlerdetails & Config-Export (nach Phase 8 nun fällig)

- `SyncState.Finished` trägt noch `failed: Int` — Umbau auf `failures: List<SyncFailure>`
  mit Fehler-Detail-Screen, „Retry failed", „Copy report" (Details in PLAN.md)
- Config-Export/-Import (JSON, Secrets nur opt-in passphrase-verschlüsselt)

### Geräteverifikation Phase 8

- Ordnernavigation, Startordner, Verschieben (eigene + fremde Dateien) am Gerät testen

### Zurückgestellt ⏸

- **Phase 6** — Linux-Client (Python)
- **Phase 7** — CI/CD (GitLab: build → sign → upload → promote)

---

## Vorarbeiten (Daniel)

- [ ] `schliemannosaurusrex.de` registrieren — **vor erstem AAB-Upload**
- [ ] `kaniamp.app` registrieren — für Projektseite + Datenschutzerklärung
- [ ] Google Play Console Developer-Konto anlegen (25 $)
- [ ] Datenschutzseite aufsetzen (erforderliche URL für Store-Listing)
- [ ] Upload-Keystore generieren + in Bitwarden ablegen
- [ ] Trademark-Check: DPMA / USPTO für „KaniAmp"
- [ ] 12 Tester für Closed Testing organisieren

---

## Dateistruktur

```
workspace/kaniamp/
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
            └── java/de/schliemannosaurusrex/kaniamp/
                ├── KaniAmpApplication.kt
                ├── MainActivity.kt
                ├── player/
                │   ├── KaniPlayerService.kt
                │   ├── PlayerViewModel.kt
                │   └── PlayerState.kt
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
                │   ├── SyncEngine.kt       (SFTP-Delta-Sync → MediaStore)
                │   ├── SyncManager.kt      (Singleton-Koordinator, StateFlow)
                │   ├── SyncViewModel.kt
                │   ├── SyncWorker.kt       (WorkManager, täglich)
                │   └── KaniSyncService.kt  (Foreground-Service dataSync)
                ├── network/
                │   └── NetworkGatekeeper.kt
                ├── settings/
                │   ├── SettingsRepository.kt
                │   └── SettingsViewModel.kt
                ├── ui/
                │   ├── KaniAmpApp.kt
                │   ├── Navigation.kt
                │   ├── NowPlayingScreen.kt
                │   ├── LibraryScreen.kt
                │   ├── SyncScreen.kt
                │   ├── SettingsScreen.kt
                │   ├── LocalNetworkPermission.kt  (ACCESS_LOCAL_NETWORK, Android 17)
                │   └── theme/
                │       ├── Color.kt
                │       ├── Theme.kt
                │       └── Type.kt
                └── util/
                    └── FormatUtil.kt
```
