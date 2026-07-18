# Manueller Testablauf — Android Auto Stumm-Bug

Ziel: möglichst viele Diagnose-Pfade im neuen Logging treffen, damit die
Auswertung danach eindeutig ist. Bitte Schritte + Uhrzeiten (grob reicht)
möglichst genau einhalten/notieren.

## Ergebnis (2026-07-18, abgeschlossen)

Sowohl Kabel- als auch WLAN-Test zeigten dasselbe Bild: App/ExoPlayer melden
über die gesamte Testreihe hinweg durchgehend fehlerfreie Wiedergabe (kein
`onPlayerError`, kein `AUDIO_FOCUS_LOSS`, keine Sink-/Codec-Fehler,
`isMusicActive=true`) — **auch** in den stummen Versuchen. Beim Kabel-Test
wurde derselbe, nie neu gestartete MukkeKlopper-Player hörbar, nachdem
zwischenzeitlich Pulsar Pro erfolgreich Ton abgespielt hatte, ohne jede
Code-/App-Beteiligung.

**Schlussfolgerung:** kein MukkeKlopper-Bug — Audio-Routing-Aushandlung auf
Fahrzeug-/Android-Auto-Ebene beim ersten Verbindungsaufbau, außerhalb der
App-Kontrolle. Ein spekulativer Delay/Audiofokus-Kick vor dem Auto-Resume
wurde erwogen, aber verworfen (im WLAN-Test half auch mehrminütiges Warten
mit mehreren echten Tap-Versuchen nicht — nur der App-Wechsel).
`AudioPlaybackConfiguration.isMuted()` wäre ein passendes System-Signal
gewesen, ist aber nicht Teil des öffentlichen SDK für Drittanbieter-Apps.

**Workaround für den Alltag:** Bleibt es beim Verbinden stumm, kurz zu einer
anderen Medien-App wechseln und zurück.

Details in `STATUS.md` → „Android Auto im echten Fahrzeug". Rohdaten/Ablauf
der Testreihe unten stehen als Referenz.

## Stand nach Testrunde 1 (2026-07-18)

Auswertung des ersten Runs: ExoPlayer/MediaSession-Ebene lief in **allen**
Versuchen technisch einwandfrei (kein `onPlayerError`, kein
`AUDIO_FOCUS_LOSS`, saubere `BUFFERING→READY`-Übergänge) — auch bei den
Versuchen, die tatsächlich stumm blieben. Ein Titel (David Carretta) war
während der Fahrt hörbar, ein anderer (Dexys Midnight Runners) beim
Zuhause-Test nicht, obwohl beide nachweislich echte Android-Auto-Kommandos
waren. **Wichtigste offene Hypothese: Wireless Android Auto** — Steuerung
läuft über WLAN, der Audio-Stream bei vielen Implementierungen aber separat
über die klassische Bluetooth-Verbindung, die unabhängig scheitern kann,
ohne dass App oder Android-Auto-UI etwas davon merken.

**Deshalb jetzt Priorität 1 im nächsten Test: einmal explizit per USB-Kabel
verbinden** (nicht drahtlos) — läuft Audio+Steuerung über denselben
Digitalkanal, keine separate Bluetooth-Abhängigkeit mehr. Kommt dann
zuverlässig Ton: Ursache bestätigt, liegt außerhalb der App. Bleibt es auch
per Kabel stumm: weiter im Code/Telefon suchen.

Zusätzlich neu im Logging: Audio-Routing-Snapshot bei jedem `isPlaying=true`
(`isMusicActive` + aktive Ausgabegeräte des Telefons, inkl. ob
Bluetooth-A2DP oder USB als Ziel erscheint) sowie tiefere
AudioTrack-/Sink-/Codec-Fehler über `AnalyticsListener` — das deckt genau
die Ebene ab, die Player-Listener nicht zeigen können.

## Vorbereitung (noch am PC/zuhause, vor der Fahrt)

1. In MukkeKlopper: Settings → „Debug log" → **„Enable debug logging"**
   aktivieren, falls nicht schon an.
2. App komplett schließen (aus der Übersicht wischen), damit der nächste
   Start sauber im Auto passiert (nicht zwingend, aber sauberer für die Logs).
3. Handy-Uhrzeit merken/Screenshot der aktuellen Uhrzeit — hilft später beim
   Zuordnen der Log-Zeitstempel.
4. **Falls möglich: Wireless Android Auto am Telefon vorübergehend
   deaktivieren** (Einstellungen → Verbundene Geräte → Verbindungseinstellungen
   → Android Auto → „Für drahtlose Android Auto verfügbar" aus), damit die
   Verbindung garantiert per Kabel zustande kommt statt automatisch auf WLAN
   zu wechseln.

## Im Auto

1. Handy **per USB-Kabel** ans Auto anschließen (bewusst nicht drahtlos),
   Android Auto startet. Prüfen, ob im Auto irgendwo „Kabel" bzw. eine
   Kabel-Verbindung angezeigt wird, nicht „Wireless"/WLAN-Symbol.
2. MukkeKlopper als Medienquelle auswählen. **Merken: läuft die
   Wiedergabe automatisch an, oder musst du „Play" antippen?**
3. Sobald (vermeintlich) Musik läuft: 10 Sekunden warten, genau hinhören.
4. Play/Pause einmal antippen (einmal pausieren, einmal fortsetzen).
5. Einen Titel im Browse-Baum antippen (bewusst einen **anderen** Ordner/
   Titel als den zuletzt gespielten) → prüfen, ob *danach* Ton da ist.
6. Lautstärke am Auto selbst hochregeln (falls es einen eigenen Regler für
   Medien/Android Auto gibt, unabhängig von der Telefon-Lautstärke).
7. **Kontrolltest:** MukkeKlopper in Android Auto verlassen, Pulsar Pro als
   Medienquelle auswählen, dort einen Titel abspielen — kommt darüber Ton
   aus den Auto-Lautsprechern? (Das entscheidet Auto/Fahrzeug-Problem vs.
   MukkeKlopper-spezifisch.)
8. Zurück zu MukkeKlopper wechseln, erneut prüfen ob Ton da ist.
9. Falls möglich: kurze Fahrtunterbrechung/Motor aus-an (nur wenn ohnehin
   ansteht, nicht extra provozieren) — zeigt, ob ein Service-Neustart das
   Verhalten ändert.

## Nach der Fahrt

1. Handy **nicht** mehr großartig bedienen (keine weiteren Apps testen,
   kein Force-Stop) — jede weitere Aktion verändert/verlängert das Log.
2. Handy wieder per USB an diesen PC anschließen.
3. Kurz Bescheid geben, welche der Schritte 2/4/5/7/8 oben hörbar Ton
   hatten und welche nicht — dann werte ich das persistierte Debug-Log
   (`adb shell run-as de.schliemannosaurusrex.mukkeklopper cat
   files/debug_log.txt`) gegen genau diese Zeitpunkte aus.

## Was die neuen Logs zeigen sollten

- Ob überhaupt ein `play`-Kommando ankommt (`playWhenReady=true`) und ob
  danach sofort ein `AUDIO_FOCUS_LOSS` folgt (Audiofokus verweigert/entzogen).
- Ob `onPlayerError` auftritt (harter Fehler im Renderer/AudioTrack).
- Welcher Controller (Package-Name) verbindet — bestätigt, dass wirklich
  Android Auto (nicht etwas anderes) die Session steuert.
- Ob beim Tap auf einen Titel `onSetMediaItems` überhaupt ausgelöst wird.
- Equalizer-Fehler beim Anwenden der Effekte (bisher stillschweigend
  verschluckt).
- **Neu:** Audio-Routing-Snapshot bei jedem Wiedergabestart — `isMusicActive`
  sowie die Liste der aktiven Ausgabegeräte (z. B. `BLUETOOTH_A2DP` vs.
  `USB_DEVICE`/`USB_ACCESSORY`). Zusammen mit „per Kabel verbunden" lässt
  sich damit direkt sehen, worüber der Ton tatsächlich läuft.
- **Neu:** `AnalyticsListener`-Fehler (Audio-Sink-Fehler, Codec-Fehler,
  Underruns) — tiefer als das, was Player-Listener zeigen können.

Das Log übersteht jetzt auch einen Prozess-Neustart des Player-Service
(vorher ging die Historie dabei verloren — das war der Grund, warum beim
letzten Mal nur ein kurzer Ausschnitt sichtbar war).
