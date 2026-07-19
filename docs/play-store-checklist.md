# Play-Store-Veröffentlichung — Checkliste

Konkretisiert die Vorarbeiten-Liste aus [`STATUS.md`](../STATUS.md) und die
Anforderungen aus [`idea.md`](../idea.md) Abschnitt 2 zu einer vollständigen,
abhakbaren Reihenfolge. Ziel: Production-Release inkl. Android-Auto-Nutzung
(sideloading wird von Android Auto standardmäßig blockiert — Play-Store-
Release hebt das auf, siehe Abschnitt 8).

**Stand 2026-07-18:** Android Auto ist am echten Fahrzeug (Kabel + WLAN)
erfolgreich verifiziert (siehe `STATUS.md`). Größte verbleibende Blocker sind
administrativ (Domain/Datenschutz-URL, Play-Console-Konto), nicht technisch.

---

## 0. Compliance-Check gegen die Google-Play-Programmierrichtlinien

Geprüft gegen [play.google/developer-content-policy](https://play.google/developer-content-policy/)
am 2026-07-18. Ergebnis: **kein Richtlinienverstoß.** MukkeKlopper fällt in
keine der restriktiv behandelten Kategorien (kein User-Generated-Content,
keine expliziten Inhalte, keine Werbung/Werbe-ID, keine
Fremd-Server-Übertragung — Sync geht ausschließlich an den eigenen,
selbst konfigurierten Server des Nutzers).

Offen sind ausschließlich **formale** Pflichtpunkte, die Google vor jeder
Veröffentlichung verlangt — abgedeckt in den Abschnitten unten:

| Bereich | Status | Pflicht |
|---|---|---|
| Data-Safety-Formular | Entwurf fertig, noch nicht in Play Console eingetragen | Ja, vor Veröffentlichung |
| Datenschutzerklärung live gehostet | Entwurf mit Platzhaltern | Ja, muss per URL verlinkt sein |
| Entwicklerkontakt inkl. Adresse | Offen | Ja, seit 2023 öffentlich im Listing |
| Content-Rating-Fragebogen | Nicht ausgefüllt | Ja (unkritisch zu erwarten) |
| AAB statt APK | Noch nicht gebaut | Ja, für den Upload |
| `ACCESS_FINE_LOCATION`-Begründung | Code-seitig sauber, Formular-Text fehlt | Ja, sonst Ablehnungsrisiko |
| 12 Tester / Closed Testing | Nicht organisiert | Ja, für neue Entwicklerkonten |
| Trademark-Check „MukkeKlopper" | Offen | Kein Play-Pflichtpunkt, aber IP-Risiko |
| Branding-Abstand zu Pulsar (Vorbild-App) | Name/Icon bereits eigenständig | Kein Play-Pflichtpunkt, aber Impersonation-Risiko (siehe Abschnitt 9) |

---

## 1. Konten & rechtliche Grundlagen

- [ ] **Domain** `schliemannosaurusrex.de` — aktuell in Rückabwicklung bei
      Checkdomain (Weiterleitung auf myFritz-Adresse ließ sich dort nicht
      sauber einrichten) — **blockiert Abschnitt 7 (Datenschutz-URL)**
- [ ] Projektwebsite unter `web/` aufsetzen (Store-Listing-Link,
      Datenschutzerklärung-Hosting) — **blockiert durch Domain**
- [ ] **Google Play Console Developer-Konto** anlegen — **ab jetzt startbar,
      unabhängig von der Domain**
  - 25 $ einmalige Gebühr, Zahlung über das Google-Konto des Owners
  - Publisher-Name: „SchliemannosaurusRex"
  - Identitätsverifizierung (Google verlangt seit 2023 für neue
    Einzelentwickler-Konten ggf. Ausweis-Upload) — Zeitpuffer einplanen,
    kann mehrere Tage dauern
- [ ] **Impressum-Text** vorbereiten (ladungsfähige Adresse, Pflicht auch für
      Privatpersonen nach DE-Recht) — **Inhalt ab jetzt vorbereitbar**, Hosting
      später (Website oder direkt als Kontaktangabe im Play-Console-Profil)
- [ ] **Trademark-Check „MukkeKlopper"** bei DPMA (Deutsches Patent- und
      Markenamt, [register.dpma.de](https://register.dpma.de)) und optional
      USPTO — **ab jetzt startbar**, reine Registerabfrage

## 2. Signing

- [ ] **Upload-Keystore generieren** — separat vom lokalen Test-Keystore
      (`android/keystore/mukkeklopper-test.jks`), **niemals wiederverwenden**:
  ```bash
  keytool -genkeypair -v -keystore mukkeklopper-upload.jks \
    -alias mukkeklopper-upload -keyalg RSA -keysize 2048 -validity 10000
  ```
- [ ] Keystore-Datei + Store-/Key-Passwort **sofort** in Bitwarden ablegen
      (nicht ins Git-Repo, auch nicht ins jetzt öffentliche GitHub-Repo) —
      Verlust des Upload-Keys vor Aktivierung von Play App Signing macht das
      Konto für diese App unbrauchbar
- [ ] Signing-Config im Gradle-Build ergänzen (analog zur bestehenden
      `testRelease`-Config in `android/app/build.gradle.kts`), Passwörter aus
      `local.properties`/Umgebungsvariablen, nie hartkodiert
- [ ] **Play App Signing aktivieren** (bei erstem Upload in der Play Console
      anbieten lassen) — Google verwaltet den eigentlichen Release-Key,
      Uploads erfolgen mit dem selbst erzeugten Upload-Key; schützt vor
      Totalverlust bei Kompromittierung des Upload-Keys

## 3. App-Konfiguration & Release-Build

- [x] `applicationId` fixiert: `de.schliemannosaurusrex.mukkeklopper`
      (unveränderlich nach erstem Upload)
- [x] Adaptive Launcher-Icon vorhanden (siehe `STATUS.md` „Icon-Branding")
- [x] `targetSdk` 35 (Android 15) — aktuell genug für Play-Policy-Vorgaben
      (jährliche Pflicht-Anhebung im Blick behalten, siehe `idea.md` 2.1)
- [ ] **AAB statt APK** für den Upload — **ab jetzt testbar**, sobald der
      Upload-Keystore existiert:
  ```bash
  ./gradlew bundleRelease
  ```
- [ ] Versionierung: `versionCode` (monoton steigend) / `versionName`
      (semantisch, z. B. `1.1.0`) vor jedem Upload erhöhen — Schema in
      `build.gradle.kts` einmalig festlegen

## 4. Sensible Permissions — Begründung im Play-Console-Formular

Play Console verlangt für bestimmte Permissions eine explizite Begründung
im „App content"-Bereich, sonst Review-Ablehnung.

- [ ] **`ACCESS_FINE_LOCATION`** — Entwurfstext (im Formular ggf. auf
      Englisch einreichen, je nachdem was die Play Console zu dem Zeitpunkt
      verlangt):
  > Wird ausschließlich verwendet, um im opt-in „Modus B" das aktuell
  > verbundene WLAN (SSID) zu erkennen und so festzustellen, ob sich das
  > Gerät im Heimnetz befindet (Voraussetzung für die Sync-Funktion zum
  > eigenen Server des Nutzers). Kein Standort-Tracking, keine Speicherung
  > von Koordinaten, keine Weitergabe an Dritte oder Server. Nutzer sehen vor
  > der Berechtigungsanfrage einen Prominent-Disclosure-Dialog und müssen
  > aktiv zustimmen (Default: Modus B deaktiviert).
- [ ] `READ_MEDIA_AUDIO` — unkritisch, Kernfunktion (Musikbibliothek),
      i. d. R. keine gesonderte Begründung nötig
- [ ] `FOREGROUND_SERVICE_MEDIA_PLAYBACK` / `FOREGROUND_SERVICE_DATA_SYNC` —
      Standard-Deklaration für Media-Player- bzw. Sync-Apps, unkritisch

## 5. Data Safety Formular (Play Console)

Entwurf der Angaben, 1:1 aus dem tatsächlichen Datenmodell der App
(`idea.md` Abschnitt 2.2, `SecretStore.kt`, `ConfigCrypto.kt`) —
**inhaltlich fertig, nur noch ins Play-Console-Formular übertragen:**

| Datenkategorie | Erhoben? | Geteilt? | Zweck | Notiz |
|---|---|---|---|---|
| Standort (ungefähr/genau) | Ja (nur wenn Modus B aktiv, opt-in) | Nein | App-Funktionalität (Heimnetz-Erkennung via SSID) | `ACCESS_FINE_LOCATION`, Prominent Disclosure Dialog vor Request |
| Audio-/Mediendateien | Ja (lokal, `READ_MEDIA_AUDIO`) | Nein | Kernfunktion (Bibliothek) | Verlässt das Gerät nur beim eigenen Sync zum eigenen Server |
| SSH-Zugangsdaten (Passwort/Key) | Ja (lokal) | Nein | Sync-Funktion | Android Keystore, verschlüsselt at rest (`SecretStore.kt`) |
| Sonstige Nutzungsdaten/Analytics | Nein | Nein | — | Keine Analytics-/Crash-Reporting-SDKs eingebunden |
| Werbe-ID | Nein | Nein | — | Keine Werbung |

**Sicherheitspraktiken-Abschnitt:**
- Daten verschlüsselt bei der Übertragung: Ja (SSH, Host-Key-Pinning/TOFU)
- Daten können gelöscht werden: Ja (App-Daten/Deinstallation; Sync-Ziel ist
  der eigene Server des Nutzers, dort eigenständig verwaltet)
- Unabhängige Sicherheitsüberprüfung: Nein (Hobby-Projekt, keine Zertifizierung)

## 6. Content Rating

- [ ] IARC-Fragebogen in der Play Console ausfüllen — **ab jetzt startbar**
      (Teil des App-Content-Bereichs, kein Play-Console-Upload nötig)
- Erwartetes Ergebnis: niedrigste Alterseinstufung (PEGI 3 / „Everyone")
  — keine expliziten Inhalte, kein UGC, kein Chat, keine Glücksspiel-Elemente
- Fragen zu „Nutzer-generierte Inhalte teilen" → Nein; „Standort teilen mit
  anderen Nutzern" → Nein (Standort wird nur lokal für Netzwerkerkennung
  ausgewertet, nie übertragen)

## 7. Store-Listing-Assets

- [x] Icon 512×512 — vorhanden: [`assets/icon/play-store-512.png`](../assets/icon/play-store-512.png)
- [ ] **Feature Graphic** 1024×500 px, JPEG/PNG (kein Alpha-Kanal) — offen,
      analog zum Icon per KI-Bildgenerierung + Nachbearbeitung erstellbar
- [ ] **Screenshots** — Play Console verlangt aktuell mind. 2, max. 8 pro
      Gerätetyp; Seitenverhältnis zwischen 16:9 und 9:16, kürzeste Seite
      ≥ 320 px, längste ≤ 3840 px (genaue Werte vor Upload in der Play
      Console gegenprüfen, Google passt das gelegentlich an). Geplant:
      Library (Ordneransicht), Now Playing, Android Auto (Browse-Baum im
      Fahrzeug/DHU) — mind. 3 Screenshots, am Pixel 8 Pro oder Emulator
      aufnehmbar, **ab jetzt vorbereitbar**
- [ ] **Kurzbeschreibung** (max. 80 Zeichen) — Entwurf:
  > Lokaler Musik-Player mit eigenem SSH-Sync, Android Auto & Equalizer.
- [ ] **Langbeschreibung** (max. 4000 Zeichen) — Entwurf-Baustein (Details/
      Ton vor finaler Nutzung gegenlesen):
  > MukkeKlopper ist ein schlanker, werbefreier Musik-Player für Android —
  > ohne Cloud-Zwang, ohne Tracking. Deine Musikbibliothek bleibt auf deinem
  > Gerät; wer synchronisieren möchte, verbindet sich wahlweise per SSH mit
  > dem eigenen Server, nicht mit einem Dienst von Drittanbietern.
  >
  > Funktionen:
  > • Ordnerbasierte Bibliothek, Alben-/Artist-Ansicht, Volltextsuche
  > • Android-Auto-Unterstützung mit vollem Ordner-Browse-Baum
  > • Equalizer mit Presets, Bass-Boost, Virtualizer
  > • Chromecast/Google-Home-Streaming
  > • Sicherer Sync zum eigenen Server via SSH (Passwort oder Public-Key),
  >   Host-Key-Pinning gegen Man-in-the-Middle
  > • Config-Export/-Import als Backup
  > • Keine Werbung, keine Analytics, keine Werbe-ID
- [ ] **Kategorie**: Musik & Audio
- [ ] **Kontakt-E-Mail** für das Listing festlegen
- [ ] **Datenschutz-URL**: `https://schliemannosaurusrex.de/privacy` —
      **blockiert durch Domain**; Entwurf liegt bereit unter
      [`docs/privacy-policy-de.md`](privacy-policy-de.md) (Platzhalter für
      Name/Adresse/Datum vor Veröffentlichung ausfüllen)

## 8. Android Auto — erledigt ✅

Technisch umgesetzt und **vollständig verifiziert** (siehe `STATUS.md`
„Android Auto"): `MediaLibraryService`, Manifest-Deklaration
`com.google.android.gms.car.application`, ablenkungsfreie UI.

- [x] Verifikation via Desktop Head Unit (DHU, 2026-07-16)
- [x] Verifikation am echten Fahrzeug, Kabel **und** WLAN (2026-07-18)
- Media-App-Zertifizierung erfolgt implizit über den Play-Store-Review,
  sobald die App auf einem produktiven Track (mind. Closed Testing) liegt —
  **kein separater Antrag nötig**. Bis dahin bleibt Android Auto auf
  Sideload-Installationen blockiert außer bei aktivierten
  Entwicklereinstellungen („Unbekannte Quellen" in der Android-Auto-App,
  nur für eigene Testzwecke — bereits genutzt für die bisherigen Tests)

## 9. Branding-/IP-Sicherheit

Laut `idea.md`: Funktionsklon eines bestehenden Players (Pulsar) ist
zulässig, **Assets/Branding-Ähnlichkeit nicht** (Impersonation-Policy-Risiko,
Takedown-Gefahr).

- [x] Eigener App-Name „MukkeKlopper", eigenes KI-generiertes Icon — bereits
      eigenständig, keine Pulsar-Ähnlichkeit
- [ ] Vor dem Upload: Screenshots/Feature-Graphic nochmal bewusst gegen
      Pulsar-Screenshots abgleichen (Farbwelt, Layout-Ähnlichkeit vermeiden)

## 10. Testing & Rollout

- [ ] **Internal Testing** zuerst — kein Review-Wartezeit, schneller
      End-to-End-Smoketest des Upload-Prozesses selbst
- [ ] **12 Tester für Closed Testing** organisieren (Play-Policy-Pflicht für
      neue Entwicklerkonten: mind. 12 Opt-in-Tester, 14 zusammenhängende
      Tage aktiv, bevor Production-Freischaltung überhaupt möglich ist) —
      **kann als Vorlauf schon jetzt starten** (E-Mail-Liste sammeln,
      unabhängig vom Rest)
- [ ] Rollout-Reihenfolge: Internal → Closed Testing (≥14 Tage) →
      Production (manuell promoten)
- [ ] Zeitpuffer einplanen: Konto-Verifizierung (Tage) + 14-Tage-Closed-Test
      + Produktions-Review (üblich: Stunden bis wenige Tage)

## 11. Release-Pipeline (zurückgestellt, `STATUS.md` Phase 7)

Ursprünglich als GitLab-CI geplant; nach dem Umzug nach GitHub sinnvoller als
GitHub Actions:

```
build (gradle bundleRelease)
  → sign (Upload-Key aus Secret-Store, z. B. GitHub Actions Secrets)
  → upload (fastlane supply / Gradle Play Publisher → internal track)
  → promote (internal → closed → production, manuell)
```

Nicht Teil des aktuellen Auftrags — separat einplanen, sobald ein
Play-Console-Konto existiert (Abschnitt 1).

---

## Schnellübersicht: was geht jetzt ohne die Domain?

| Kategorie | Beispiele | Status |
|---|---|---|
| **Technisch vorbereitbar** | Upload-Keystore, AAB-Build, Data-Safety-Text, Store-Beschreibungen, Feature Graphic, Screenshots, Permission-Begründung | ab sofort |
| **Braucht dein Zutun (Account/Recherche)** | Play-Console-Konto (Zahlung), Trademark-Check, 12 Tester rekrutieren | ab sofort startbar, aber nicht von mir ausführbar |
| **Blockiert durch Domain** | Datenschutz-URL im Listing, `web/`-Projektseite | wartet auf Checkdomain-Rückabwicklung |

Ohne Datenschutz-URL lässt sich in der Play Console kein Track (auch nicht
Closed Testing) tatsächlich live schalten — alle anderen Punkte lassen sich
aber bereits vollständig vorbereitet in der Schublade liegen haben, sodass
nach Domain-Klärung nur noch der Upload selbst fehlt.
