# Play-Store-Veröffentlichung — Checkliste

Konkretisiert die Vorarbeiten-Liste aus [`STATUS.md`](../STATUS.md) und die
Anforderungen aus [`idea.md`](../idea.md) Abschnitt 2 zu einer abhakbaren
Reihenfolge. Ziel: Production-Release inkl. Android-Auto-Zertifizierung
(sideloading wird von Android Auto standardmäßig blockiert — siehe
`STATUS.md` „Android Auto").

## 1. Konten & rechtliche Grundlagen

- [ ] Domain `schliemannosaurusrex.de` registrieren — **erledigt laut Owner**
- [ ] Projektwebsite unter `web/` aufsetzen (Store-Listing-Link,
      Datenschutzerklärung-Hosting) — **in Arbeit laut Owner**
- [ ] Google Play Console Developer-Konto anlegen (25 $ einmalig,
      Publisher-Name „SchliemannosaurusRex")
- [ ] Impressum im Store-Listing (ladungsfähige Adresse, auch als
      Privatperson nach DE-Recht Pflicht)
- [ ] Trademark-Check „MukkeKlopper" bei DPMA/USPTO (laut `idea.md` offen)

## 2. Signing

- [ ] Upload-Keystore generieren (separat vom lokalen Test-Keystore aus
      `android/keystore/mukkeklopper-test.jks` — **nicht** wiederverwenden)
  ```bash
  keytool -genkeypair -v -keystore mukkeklopper-upload.jks \
    -alias mukkeklopper -keyalg RSA -keysize 2048 -validity 10000
  ```
- [ ] Keystore-Datei + Passwort in Bitwarden ablegen (nicht ins Git-Repo —
      auch nicht ins jetzt öffentliche GitHub-Repo!)
- [ ] Play App Signing aktivieren (Google verwaltet den Release-Key, Upload
      erfolgt mit dem Upload-Key)

## 3. App-Konfiguration prüfen

- [x] `applicationId` fixiert: `de.schliemannosaurusrex.mukkeklopper`
      (unveränderlich nach erstem Upload)
- [x] Adaptive Launcher-Icon vorhanden (siehe `STATUS.md` „Icon-Branding")
- [ ] AAB statt APK für den Upload (`./gradlew bundleRelease`, mit
      Upload-Keystore signiert)
- [ ] Versionierung: `versionCode`/`versionName` vor jedem Upload erhöhen

## 4. Data Safety Formular (Play Console)

Entwurf der Angaben, 1:1 aus dem tatsächlichen Datenmodell der App
(`idea.md` Abschnitt 2.2, `SecretStore.kt`, `ConfigCrypto.kt`):

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

## 5. Content Rating

- [ ] Fragebogen ausfüllen — für einen reinen Audio-Player unkritisch,
      keine expliziten Inhalte/UGC/Chat-Funktionen

## 6. Store-Listing-Assets

- [ ] Icon 512×512 — **vorhanden:** [`assets/icon/play-store-512.png`](../assets/icon/play-store-512.png)
- [ ] Feature Graphic 1024×500 — offen (nicht Teil des Icon-Auftrags)
- [ ] Screenshots (mind. 2, empfohlen: Library, Now Playing, Android Auto)
- [ ] Kurzbeschreibung (max. 80 Zeichen) + Langbeschreibung (max. 4000 Zeichen)
- [ ] Datenschutz-URL: `https://schliemannosaurusrex.de/privacy` (sobald
      `web/` live ist) — Entwurf liegt bereit unter
      [`docs/privacy-policy-de.md`](privacy-policy-de.md)

## 7. Android Auto — zusätzliche Anforderungen

Technisch bereits umgesetzt (siehe `STATUS.md` „Android Auto"):
`MediaLibraryService`, Manifest-Deklaration `com.google.android.gms.car.application`,
ablenkungsfreie UI (Browse-Baum wird von Android Auto selbst gerendert).

Offen:
- [ ] Verifikation über Desktop Head Unit (DHU) oder echtes Fahrzeug
      (siehe eigener Abschnitt in `STATUS.md`)
- [ ] Media-App-Zertifizierung erfolgt implizit über den Play-Store-Review,
      sobald die App auf einem produktiven Track (mind. Closed Testing)
      liegt — **kein separater Antrag nötig**, aber ohne Play-Store-Release
      bleibt Android Auto auf Sideload-Installationen blockiert (Ausnahme:
      Entwicklereinstellungen → „Unbekannte Quellen" in der Android-Auto-App,
      nur für eigene Testzwecke)

## 8. Testing & Rollout

- [ ] 12 Tester für Closed Testing organisieren (Play-Policy-Pflicht für neue
      Konten: 12 Tester, 14 Tage, vor Production-Freischaltung)
- [ ] Rollout-Reihenfolge: Internal → Closed Testing → Production (manuell
      promoten, siehe Release-Pipeline unten)

## 9. Release-Pipeline (offen, zurückgestellt laut `STATUS.md` Phase 7)

Ursprünglich als GitLab-CI geplant; nach dem Umzug nach GitHub sinnvoller als
GitHub Actions:

```
build (gradle bundleRelease)
  → sign (Upload-Key aus Secret-Store, z. B. GitHub Actions Secrets)
  → upload (fastlane supply / Gradle Play Publisher → internal track)
  → promote (internal → closed → production, manuell)
```

Nicht Teil dieses Auftrags — separat einplanen, sobald ein Play-Console-Konto
existiert (Schritt 1).
