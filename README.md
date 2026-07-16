<p align="center">
  <img src="assets/icon/mukkeklopper-icon-1024.png" width="128" alt="MukkeKlopper icon">
</p>

<h1 align="center">MukkeKlopper</h1>

<p align="center">
  Lokaler Android-Musikplayer mit eigenem SSH-Sync zum Heimserver — keine Cloud, keine Fremd-Endpunkte.
</p>

## Über das Projekt

MukkeKlopper ist ein Audio-Player für Android, der die eigene Musiksammlung
direkt von einem selbst betriebenen Server (per SSH) synchronisiert, statt auf
einen Streaming-Dienst oder einen Cloud-Anbieter zu setzen. Alle Daten bleiben
lokal auf dem Gerät bzw. auf dem eigenen Server; es gibt keine Dritt-Endpunkte.

**App-ID:** `de.schliemannosaurusrex.mukkeklopper`
**Publisher:** SchliemannosaurusRex

## Features

- **Player:** Media3/ExoPlayer, Notification-Steuerung, Equalizer
- **Bibliothek:** Ordner-Browse über `MediaStore.Audio`, Merken des Startordners
- **Sync:** SSH-Sync (Publickey + Passwort) mit Host-Key-Pinning (TOFU),
  Delta-Erkennung, Konfliktbehandlung bei serverseitig gelöschten Dateien
- **Netzwerk-Gating:** Sync nur im Heimnetz oder über VPN — kein
  unbeabsichtigter Sync über fremde Netze
- **Cast:** Chromecast-Unterstützung (`CastPlayer`-Swap)
- **Android Auto:** `MediaLibraryService`-Browse-Baum, identische
  Ordnerstruktur wie in der App
- **Debug-Log:** Umschaltbares In-App-Log für Sync-Diagnose

Details zu Architektur und Umsetzungsstand: [`STATUS.md`](STATUS.md).

## Tech-Stack

| Komponente | Technologie |
|---|---|
| Sprache | Kotlin |
| UI | Jetpack Compose + Material3 |
| Player | Media3 (ExoPlayer + MediaLibraryService, CastPlayer) |
| Sync | sshj (SSH2), Android Keystore für Credential-Verschlüsselung |
| Build | Gradle · AGP · Kotlin |
| minSdk / targetSdk | 30 (Android 11) / 35 (Android 15) |

## Build

```bash
cd android
./gradlew assembleDebug
```

Für signierte Release-Builds wird ein lokaler Test-Keystore über
`android/local.properties` eingebunden (siehe `app/build.gradle.kts`) — ohne
diese Datei baut die Release-Variante unsigniert. Der Play-Store-Upload-Key
ist separat und liegt nicht in diesem Repository.

## Projektstatus & Doku

- [`STATUS.md`](STATUS.md) — abgeschlossene Phasen, offene Punkte, Geräteverifikation
- [`PLAN.md`](PLAN.md) — ursprüngliche Phasenplanung
- [`idea.md`](idea.md) — Netzwerk-Logik & Play-Store-Anforderungen
- [`docs/play-store-checklist.md`](docs/play-store-checklist.md) — abhakbare
  Play-Store-Vorbereitung inkl. Data-Safety-Entwurf
- [`docs/privacy-policy-de.md`](docs/privacy-policy-de.md) — Datenschutzerklärung-Entwurf
  zum späteren Hosting unter `web/`

## Lizenz

[AGPL-3.0](LICENSE) — Weiterverbreitung und abgeleitete Werke sind erlaubt,
müssen aber unter derselben Lizenz offengelegt werden (Copyleft, inkl.
Netzwerknutzung). Name, Icon und Store-Branding sind davon unabhängig zu
behandeln (kein Freibrief für Marken-/Namensnutzung).
