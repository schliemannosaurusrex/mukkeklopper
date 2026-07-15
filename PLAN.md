# MukkeKlopper — Implementierungsplan

## Kontext

MukkeKlopper ist ein datenschutzorientierter Audio-Player mit SSH-basiertem Datei-Sync von einem privaten Heimserver (`<sync-host>`). Musikdateien werden auf das Gerät gezogen und lokal abgespielt. Sync ist netzwerkgegated: nur im Heimnetz oder per WireGuard-VPN. Zwei Clients: Android (Kotlin/Media3) und Linux (Python).

- **App-ID:** `de.schliemannosaurusrex.mukkeklopper`
- **Publisher:** SchliemannosaurusRex

---

## Projektstruktur

```
workspace/mukkeklopper/
├── android/
│   └── app/src/main/java/de/schliemannosaurusrex/mukkeklopper/
│       ├── player/       # Media3 ExoPlayer + MediaSessionService
│       ├── sync/         # SSH/SFTP Sync-Engine
│       ├── network/      # NetworkGatekeeper
│       ├── ui/           # Activities, Fragments
│       └── settings/     # PreferenceFragmentCompat
├── linux/
│   └── mukkeklopper/
│       ├── player.py     # mpv/vlc subprocess wrapper
│       ├── sync.py       # paramiko SFTP
│       └── network.py    # socket + wg + nmcli
└── ci/
    └── .gitlab-ci.yml
```

---

## Phase 1 — Android-Fundament

**Gradle-Dependencies:**
- `androidx.media3:media3-exoplayer`
- `androidx.media3:media3-ui`
- `androidx.media3:media3-session`
- `net.schmizz:sshj` (SSH/SFTP)
- `kotlinx-coroutines-android`

**AndroidManifest.xml — Permissions:**
```xml
INTERNET, ACCESS_NETWORK_STATE
ACCESS_LOCAL_NETWORK          <!-- Android 17, LAN-Sync -->
READ_MEDIA_AUDIO
ACCESS_FINE_LOCATION          <!-- Modus B, ab v1 deklarieren -->
FOREGROUND_SERVICE
FOREGROUND_SERVICE_MEDIA_PLAYBACK
FOREGROUND_SERVICE_DATA_SYNC
```

**Foreground Services:**
- `MukkePlayerService : MediaSessionService` — serviceType: `mediaPlayback`
- `MukkeSyncService : Service` — serviceType: `dataSync`

---

## Phase 2 — Audio-Player-Kern (Media3)

`MukkePlayerService`:
- `ExoPlayer` + `MediaSession` — Playback-Controller für UI und Notification
- `MediaStyleNotification` mit Playback-Controls (Play/Pause/Skip)
- Playlist aus lokalem Storage (app-spezifisches externes Verzeichnis)

UI-Fragmente:
- `NowPlayingFragment` — Cover, Progress, Controls
- `LibraryFragment` — Browser nach Artist/Album/Track (MediaStore-Abfrage)
- `QueueFragment` — aktuelle Wiedergabeliste

---

## Phase 3 — Network-Gating (`NetworkGatekeeper`)

Gemeinsame Klasse, aufgerufen vor jedem Sync-Start:

```
canSync() → SyncDecision {ALLOWED, BLOCKED, MITM_WARNING}

[1] Heimnetz-Check:
    Modus A (default): TCP-Connect <sync-host>:22, Timeout 3s
    Modus B (opt-in):  SSID ∈ home_ssids (ConnectivityManager WifiInfo)
    → erkannt: weiter zu [3]
    → nicht erkannt: weiter zu [2]

[2] VPN-Check:
    ConnectivityManager.getNetworkCapabilities(net)
      .hasTransport(TRANSPORT_VPN)
    → aktiv: weiter zu [3]
    → inaktiv: return BLOCKED
      UI: Hinweis-Banner + Intent com.wireguard.android

[3] SSH-Handshake + Host-Key-Pinning:
    a) TCP-Connect <sync-host>:22
    b) TOFU beim ersten Connect: Ed25519-Key speichern (EncryptedSharedPreferences)
       Folge-Connects: Key-Vergleich
    → Match: return ALLOWED
    → Mismatch: return MITM_WARNING (kein Auto-Retry, User-Dialog)
```

---

## Phase 4 — SSH-Sync-Engine (`SyncEngine`)

- Bibliothek: `sshj` (SFTP-Subsystem) — bereits eingebunden (Phase 3)
- Gepinnter Host-Key: TOFU via `NetworkGatekeeper`, DataStore `pinned_hostkey` ✅ (Phase 3)
- Sync-Richtung: Server → Gerät (Pull only)

**Authentifizierung — zwei Methoden, wählbar in den Settings:**
- Passwort: `client.authPassword(user, password)`
- Public Key: `client.authPublickey(user, keyProvider)`
  - Ed25519-Paar wird **in-App generiert** (v1: kein Import)
  - Public Key in den Settings im OpenSSH-Format anzeigen (Kopieren/Teilen) →
    User trägt ihn in `authorized_keys` des Servers ein
- Secret-Speicherung (Passwort bzw. Private Key): AES-256-GCM-Schlüssel im
  Android Keystore, Chiffrat in DataStore — der Private Key muss für sshj lesbar
  sein und kann daher nicht als Keystore-Eintrag leben

**Neue Settings-Keys:**

| Key | Typ | Default |
|-----|-----|---------|
| `server_user` | String | — |
| `auth_method` | Enum `password` / `publickey` | `password` |
| `auth_secret` | String (AES-GCM-verschlüsselt) | — |
| `remote_base_path` | String | — |

**Sync-Ziel: `Music/MukkeKlopper/<Server-Struktur>`** (öffentlich, via MediaStore-Insert):
- Server-Ordnerstruktur wird 1:1 gespiegelt → Grundlage der Ordner-Library (Phase 8)
- MediaStore indexiert automatisch → gesyncte Titel erscheinen sofort in der Library
- MukkeKlopper ist Datei-Owner → lokales Verschieben später ohne System-Dialog

**Sync-Ablauf:**
1. `NetworkGatekeeper.canSync()` — Blocked → UI-Banner + WireGuard-Intent (`com.wireguard.android`)
2. Connect + Auth
3. Remote-Inventur: rekursives SFTP-Listing von `remote_base_path` (Pfad, Größe, mtime)
4. Lokale Inventur: MediaStore-Bestand unter `Music/MukkeKlopper/`
5. Delta: nur neue/geänderte Dateien laden (`IS_PENDING` während des Downloads)
6. Löschungen: server-seitig entfernte Dateien werden aufgelistet, lokales Löschen
   erst nach Bestätigung durch den User — **pro Sync-Lauf nachfragen**
7. Progress: `StateFlow<SyncState>` → UI beobachtet

`MukkeSyncService` (WorkManager-Integration):
- Manuell: UI-Button → sofortiger Start
- Scheduler: `PeriodicWorkRequest` (Constraint: `CONNECTED`)

---

## Phase 5 — Settings

`SettingsFragment : PreferenceFragmentCompat`:

| Key | Typ | Default |
|-----|-----|---------|
| `server_host` | String | — |
| `server_port` | Int | 22 |
| `pinned_hostkey` | String | — (gesetzt per TOFU) |
| `require_vpn_outside_home` | Boolean | true |
| `sync_mode_b_enabled` | Boolean | false |
| `home_ssids` | StringSet | [] |

Beim Aktivieren von Modus B:
- Prominent Disclosure Dialog anzeigen (vor Permission-Request)
- Dann `requestPermissions(ACCESS_FINE_LOCATION)`
- Data-Safety-Erklärung im Play-Store-Listing vorbereiten

---

## Phase 4b — Sync-Fehlerdetails & Config-Export (nach Phase 8)

### Fehlgeschlagene Objekte im Detail

Heute meldet `SyncState.Finished` nur eine Zahl (`failed`), die Ursache landet
ausschließlich im Logcat. Ziel: pro fehlgeschlagener Datei nachvollziehbar machen,
**was** und **warum** — und gezielt wiederholbar.

**Datenmodell:**

```kotlin
data class SyncFailure(
    val relPath: String,
    val reason: FailureReason,   // enum, s. u.
    val detail: String,          // exception message, für „Details“-Ausklappen
    val bytesDone: Long,         // wie weit kam der Download
    val bytesTotal: Long,
    val timestamp: Long,
)

enum class FailureReason {
    PERMISSION_DENIED,   // SFTP: keine Leserechte auf dem Server
    NOT_FOUND,           // Datei verschwand zwischen Inventur und Download
    CONNECTION_LOST,     // Transport brach ab
    OUT_OF_SPACE,        // lokal kein Platz mehr
    MEDIASTORE_REJECTED, // insert/openOutputStream schlug fehl
    UNKNOWN,
}
```

- Mapping der sshj-/IO-Exceptions auf `FailureReason` in einer Funktion
  `classifyFailure(e: Exception): FailureReason` (sshj wirft `SFTPException` mit
  `StatusCode` — `PERMISSION_DENIED`, `NO_SUCH_FILE` — der Rest per Exception-Typ)
- `SyncState.Finished` trägt `failures: List<SyncFailure>` statt `failed: Int`
- **Persistenz:** letzter Lauf in DataStore (`last_sync_report`, JSON via
  `kotlinx.serialization`) — überlebt App-Neustart, damit der Report auch später
  noch einsehbar ist. Nur der jeweils letzte Lauf, kein Verlauf.

**UI (Sync-Tab):**
- Ergebnis-Karte: „3 downloaded · 2 failed" → „2 failed" ist antippbar
- **Fehler-Detail-Screen** (neue Route `sync_failures`): Liste je Datei mit Pfad,
  Klartext-Grund („Permission denied on server"), ausklappbarem technischem Detail
- Aktion **„Retry failed"** — startet einen Sync, der nur die gelisteten Pfade
  erneut versucht (`SyncManager.runSync(retryOnly = failures.map { it.relPath })`)
- Aktion **„Copy report"** — Fehlerliste als Text in die Zwischenablage

### Config-Export / -Import

Zweck: Setup auf ein neues Gerät übertragen und ein Backup der Einstellungen halten,
**ohne** Secrets im Klartext preiszugeben.

**Format** (JSON, `kotlinx.serialization`, `application/json`, Dateiname
`mukkeklopper-config-<yyyyMMdd>.json`):

```jsonc
{
  "version": 1,
  "server": { "host": "…", "port": 22, "user": "…",
              "remoteBasePath": "…", "pinnedHostKey": "64:c6:…" },
  "network": { "requireVpnOutsideHome": true,
               "syncModeBEnabled": false, "homeSsids": [] },
  "library": { "libraryRoot": "Music/MukkeKlopper/…", "viewMode": "FOLDERS" },
  "sync": { "autoSyncEnabled": false },
  "secrets": null   // siehe unten
}
```

**Secrets-Regel — bewusst restriktiv:**
- **Default: Secrets werden NICHT exportiert.** `auth_secret` (Passwort) und
  `ssh_key_seed` bleiben im Gerät; der Import fragt sie neu ab bzw. generiert einen
  neuen SSH-Key. Das ist der Normalfall und braucht keine Warnung.
- **Opt-in „Include credentials"**: nur mit passphrase-verschlüsseltem Blob
  (PBKDF2-HMAC-SHA256, ≥ 210 000 Iterationen, zufälliges Salt → AES-256-GCM).
  Die Passphrase gibt der User beim Export ein und beim Import erneut. Der
  Keystore-Schlüssel selbst ist nicht exportierbar, deshalb dieser zweite Umschlag.
  Vor dem Aktivieren: Dialog mit klarem Hinweis, dass die Datei dann Zugangsdaten
  enthält.
- Der gepinnte Host-Key **wird** exportiert (er ist öffentlich und schützt gerade
  vor MITM auf dem neuen Gerät — TOFU entfällt damit).

**UI (Settings, neue Sektion „Backup"):**
- „Export configuration" → `ActivityResultContracts.CreateDocument("application/json")`
- „Import configuration" → `OpenDocument`; zeigt vor dem Übernehmen eine
  Zusammenfassung (Host, User, Pfad, ob Secrets enthalten) → Bestätigung
- Import validiert `version`; unbekannte Felder werden ignoriert (`ignoreUnknownKeys`),
  fehlende Pflichtfelder → Abbruch mit Meldung

---

## Phase 8 — Ordner-Library (Pulsar-Stil)

Umsetzungsreihenfolge: nach Phase 4, vor Phase 9.

- Ordnerbaum aus MediaStore `RELATIVE_PATH` ableiten — kein SAF/Dateisystem-Zugriff nötig
- Ansichtsmodi: **Ordner** (Default) · Alben · Artists · Alle Titel
- Setting `library_root` (Standard-/Startverzeichnis):
  - **Markieren statt tippen:** im Ordner stehen → Menü „Set as start folder" →
    der aktuelle Ordner wird gemerkt; Anzeige per Stern-Icon in der Titelzeile
  - Library öffnet beim App-Start immer dort; „Home"-Button springt jederzeit zurück
  - Navigation **oberhalb** des Startverzeichnisses bleibt erlaubt (kein Käfig)
  - Setting ist zurücksetzbar („Clear start folder" → Library startet wieder an der Wurzel)
- Navigation wie Dateimanager: Ordner + Titel gemischt, hoch/runter navigieren
- **Verschieben** (rein lokal, kein Rück-Sync zum Server):
  - Long-Press → Mehrfachauswahl → „Verschieben nach…" → Zielordner wählen
  - eigene (von MukkeKlopper gesyncte) Dateien: direktes MediaStore-Update von `RELATIVE_PATH`
  - fremde Dateien: `MediaStore.createWriteRequest()` → System-Bestätigungsdialog pro Batch
  - Namenskollision im Ziel: Abbruch mit Meldung, kein Überschreiben

## Phase 9 — Equalizer

- `android.media.audiofx.Equalizer` an die `audioSessionId` des ExoPlayers
  (im `MukkePlayerService`, Anwendung beim Service-Start)
- System-Presets + manuelle Band-Regler (gerätabhängig, i. d. R. 5 Bänder)
- Zusätzlich: BassBoost + Virtualizer
- Persistenz in DataStore
- UI: eigener Screen, erreichbar aus Now Playing

## Phase 6 — Linux-Client (Python) — ZURÜCKGESTELLT

`network.py`:
```python
# Heimnetz Modus A
socket.create_connection((host, 22), timeout=3)

# VPN
subprocess.run(['wg', 'show', 'wg0', 'latest-handshakes'])
# Fallback: ip link show type wireguard

# SSID Modus B
subprocess.run(['nmcli', '-t', '-f', 'active,ssid', 'dev', 'wifi'])
```

`sync.py` (paramiko):
- `RejectPolicy` — kein automatisches Key-Akzeptieren
- Gepinnter Key in `~/.config/mukkeklopper/known_hosts`
- TOFU: leere known_hosts → Key speichern; Abweichung → Abbruch + Meldung
- Delta-Sync: SFTP `listdir_attr` vs. lokale Dateien

`player.py`:
- `mpv` via subprocess (bevorzugt) oder `python-vlc`
- CLI-Steuerung: play/pause/next/prev via IPC-Socket (mpv JSON IPC)

---

## Phase 7 — CI/CD (GitLab, self-hosted) — ZURÜCKGESTELLT

```yaml
# ci/.gitlab-ci.yml
stages: [build, sign, upload]

build:
  script: ./gradlew bundleRelease

sign:
  script: |
    echo "$KEYSTORE_B64" | base64 -d > keystore.jks
    jarsigner -keystore keystore.jks -storepass "$KEYSTORE_PASS" \
      android/app/build/outputs/bundle/release/app-release.aab mukkeklopper
  variables:
    KEYSTORE_B64: $BITWARDEN_KEYSTORE   # Bitwarden Secrets Manager

upload:
  script: bundle exec fastlane supply --track internal --aab app-release.aab
```

Promote: internal → closed (12 Tester, 14 Tage) → production (manuell)

---

## Vorarbeit (vor Entwicklungsstart / vor erstem AAB-Upload)

### Domains

| Domain | Zweck | Priorität |
|--------|-------|-----------|
| `schliemannosaurusrex.de` | Basis des App-IDs `de.schliemannosaurusrex.mukkeklopper` — Pflicht vor erstem AAB-Upload | **Kritisch** |
| `mukkeklopper.app` oder `mukkeklopper.com` | Projektseite + Datenschutzerklärung-URL (Play Store verlangt erreichbare URL) | Pflicht vor Store-Listing |

### Google Play Console

> **Hinweis:** Google Cloud Console wird für diese App nicht benötigt — keine Google-APIs (kein Firebase, Maps, Sign-In). Alles läuft über den eigenen Server.

Unter [play.google.com/console](https://play.google.com/console):

1. **Developer-Konto anlegen** — 25 $ einmalig, Zahlungsmethode hinterlegen
2. **App erstellen**
   - App-Name: `MukkeKlopper`
   - App-ID: `de.schliemannosaurusrex.mukkeklopper` ← unveränderlich nach erstem Upload
   - Sprache, Land, kostenlos
3. **Play App Signing aktivieren** — Google verwaltet Release-Key, Upload erfolgt mit eigenem Upload-Key (Pflicht für AAB)
4. **Data Safety Formular ausfüllen**
   - Keine Datenerhebung (alles lokal / eigener Server)
   - Location deklarieren (Modus B, auch wenn opt-in)
   - SSH-Credentials: lokal, Android Keystore, verschlüsselt at rest
5. **Content Rating** — Fragebogen ausfüllen (für Audio-Player unkritisch)
6. **Store Listing vorbereiten**
   - Icon: 512×512 px
   - Feature Graphic: 1024×500 px
   - Screenshots: mind. 2
   - Kurz- und Langbeschreibung
   - Datenschutz-URL: z. B. `https://mukkeklopper.app/privacy`
7. **Closed Testing Track** — 12 Tester einladen, 14 Tage laufen lassen → dann Production freischaltbar

### Upload-Key (Keystore)

```bash
keytool -genkeypair -v \
  -keystore mukkeklopper-upload.jks \
  -alias mukkeklopper \
  -keyalg RSA -keysize 2048 \
  -validity 10000
```

Keystore-Datei und Passwort in **Bitwarden** ablegen (nicht ins Git-Repo).

### Empfohlene Reihenfolge

- [ ] `schliemannosaurusrex.de` registrieren
- [ ] `mukkeklopper.app` (oder `.com`) registrieren
- [ ] Play Console Developer-Konto anlegen ($25)
- [ ] Datenschutzseite aufsetzen (einfaches HTML genügt)
- [ ] Upload-Keystore generieren + in Bitwarden ablegen
- [ ] Trademark-Check: DPMA / USPTO für "MukkeKlopper"
- [ ] 12 Tester für Closed Testing organisieren

---

## Verifikation

**Android:**
1. Lokale MP3 abspielen → Notification-Controls funktionieren
2. Sync auslösen bei VPN aus + falscher IP → BLOCKED-State, WireGuard-Intent erscheint
3. MITM simulieren (falscher Host-Key in Settings) → App bricht ab, kein Retry, Dialog erscheint
4. Modus B aktivieren → Prominent Disclosure → Location-Permission-Request erscheint

**Linux:**
1. `python -m mukkeklopper sync --dry-run` → Delta-Liste ohne Download
2. `wg show` via Mock-Env → VPN-Logik greift korrekt
3. paramiko mit unbekanntem Key → `RejectPolicy` bricht ab, Fehlermeldung erscheint
