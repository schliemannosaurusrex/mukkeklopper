# Datenschutzerklärung — MukkeKlopper

> **Hinweis:** Entwurf zum Hosting unter `https://schliemannosaurusrex.de/privacy`
> (URL-Platzhalter, siehe [`docs/play-store-checklist.md`](play-store-checklist.md)
> Abschnitt 6). Vor Veröffentlichung: Impressum-Verweis ergänzen und Stand
> juristisch gegenprüfen — dies ist eine Grundlage, keine Rechtsberatung.

**Stand:** _[Datum vor Veröffentlichung einsetzen]_

## 1. Verantwortlicher

_[Name/Anschrift des Betreibers — Pflichtangabe nach Art. 13 DSGVO,
identisch mit dem Impressum des Store-Listings]_

## 2. Grundsatz

MukkeKlopper ist als lokale App konzipiert: Musikbibliothek und
Sync-Zugangsdaten verbleiben auf dem Gerät bzw. werden ausschließlich mit
einem Server synchronisiert, den der Nutzer selbst betreibt. Es gibt **keine**
Übertragung an den App-Entwickler, an Cloud-Dienste oder an Werbe-/Analytics-
Anbieter.

## 3. Welche Daten werden verarbeitet?

| Daten | Speicherort | Zweck | Weitergabe |
|---|---|---|---|
| Audiodateien (Metadaten, Titel) | Gerät (`MediaStore`) | Bibliotheksanzeige, Wiedergabe | Nein |
| SSH-Zugangsdaten (Passwort und/oder privater Schlüssel) | Gerät, verschlüsselt im Android Keystore | Sync mit dem eigenen Server des Nutzers | Nein — nur an den vom Nutzer selbst konfigurierten Server |
| Gepinnter SSH-Host-Key | Gerät (App-Einstellungen) | Schutz vor Man-in-the-Middle beim Sync | Nein |
| Standort (nur bei aktiviertem „Modus B") | Gerät, zur Laufzeit über `ACCESS_FINE_LOCATION` | Erkennung, ob sich das Gerät im Heimnetz befindet (SSID-Abgleich) | Nein — verlässt das Gerät nicht, keine Standort-Historie |
| Home-SSID-Liste (Modus B) | Gerät (App-Einstellungen) | s. o. | Nein |

Es werden **keine** Nutzungsstatistiken, Werbe-IDs, Absturzberichte oder
sonstigen Analytics-Daten erhoben.

## 4. Standortdaten (Modus B) im Detail

Die Standortberechtigung wird ausschließlich für den optionalen „Modus B"
(SSID-Whitelist zur Heimnetz-Erkennung) benötigt und ist standardmäßig
**deaktiviert**. Vor der Berechtigungsanfrage zeigt die App einen expliziten
Hinweis-Dialog (Prominent Disclosure), der Zweck und Umfang erklärt. Der
Standort selbst wird nicht dauerhaft gespeichert oder übertragen — es wird
lediglich lokal geprüft, ob die aktuell verbundene WLAN-SSID auf der vom
Nutzer gepflegten Liste steht.

## 5. Rechtsgrundlage

- Sync-Zugangsdaten und Audiodateien: Art. 6 Abs. 1 lit. b DSGVO
  (Vertragserfüllung/Funktionserbringung der App)
- Standort (Modus B): Art. 6 Abs. 1 lit. a DSGVO (Einwilligung, jederzeit
  widerrufbar über die Android-Systemeinstellungen)

## 6. Speicherdauer

Alle genannten Daten verbleiben auf dem Gerät, bis der Nutzer sie über die
App-Einstellungen löscht oder die App deinstalliert. Auf dem Sync-Server
gespeicherte Musikdateien unterliegen der eigenen Verwaltung des Nutzers und
sind nicht Gegenstand dieser Erklärung.

## 7. Rechte der Nutzer

Da keine Daten an den Entwickler übertragen werden, bestehen keine
serverseitigen Auskunfts- oder Löschansprüche gegenüber dem Entwickler — alle
Daten sind lokal einsehbar und löschbar (App-Einstellungen bzw.
Deinstallation). Bei Fragen: _[Kontakt-E-Mail einsetzen]_.

## 8. Drittanbieter-Dienste

- **Google Cast (Chromecast):** Verbindung zu Cast-Geräten im lokalen
  Netzwerk erfolgt über die Google-Play-Services-Cast-Bibliothek; es werden
  keine Konto- oder Nutzungsdaten an Google übermittelt, die über das für die
  Geräteerkennung im lokalen Netz technisch notwendige Maß hinausgehen.
- **Android Auto:** Wiedergabesteuerung erfolgt über den systemeigenen
  Media-Browse-Mechanismus; keine zusätzliche Datenverarbeitung.

## 9. Änderungen dieser Erklärung

Diese Erklärung wird bei funktionalen Änderungen der App aktualisiert; das
Datum in Abschnitt „Stand" wird entsprechend angepasst.
