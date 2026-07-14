# Konzept-Addendum: Netzwerk-Logik & Play-Store-Veröffentlichung

**App-ID:** `de.schliemannosaurusrex.kaniamp` · **Publisher:** SchliemannosaurusRex

---

## 1. Netzwerk-Logik: Sync-Gating (Heimnetz vs. VPN)

### 1.1 Entscheidungsbaum

```
Sync-Trigger (manuell oder Scheduler)
│
├─► [1] Heimnetz-Erkennung
│      Modus A (Default): Erreichbarkeitstest &lt;sync-host&gt;:22 (TCP, Timeout 3s)
│      Modus B (optional): SSID ∈ Whitelist (erfordert Location-Permission)
│      │
│      ├─ Heimnetz erkannt ──────────────► weiter zu [3]
│      └─ Nicht im Heimnetz ─────────────► weiter zu [2]
│
├─► [2] VPN-Prüfung
│      Android: ConnectivityManager → NetworkCapabilities.TRANSPORT_VPN
│      Linux:   `wg show <interface>` (latest handshake < 180s)
│      │
│      ├─ Tunnel aktiv ──────────────────► weiter zu [3]
│      └─ Kein Tunnel ───────────────────► SYNC BLOCKIERT
│           └─ UI-Hinweis + Intent: WireGuard-App öffnen
│              (com.wireguard.android, Tunnel-Toggle via App-Intent)
│
└─► [3] Verbindungstest + Identitätsprüfung
       a) TCP-Connect &lt;sync-host&gt;:22
       b) SSH-Handshake: Host-Key gegen gepinnten Key (TOFU beim Setup)
       │
       ├─ Key match ────────────────────► SYNC START
       └─ Key mismatch ─────────────────► ABBRUCH (MITM-Warnung, kein Auto-Retry)
```

### 1.2 Modi im Vergleich

| | Modus A: Erreichbarkeit + Host-Key | Modus B: SSID-Whitelist |
|---|---|---|
| Prinzip | „Heimnetz" = &lt;sync-host&gt; direkt erreichbar & Key verifiziert | Netz-Identität via SSID |
| Android-Permissions | `ACCESS_LOCAL_NETWORK` (Android 17), `INTERNET` | zusätzlich `ACCESS_FINE_LOCATION` + aktive Standortdienste |
| Play-Store-Aufwand | gering | Prominent Disclosure, Data-Safety-Angabe „Location", Review-Rückfragen möglich |
| Sicherheit | Host-Key beweist Identität → SSID-Kenntnis unnötig | SSID spoofbar, allein nicht ausreichend |
| Empfehlung | **Default** | Opt-in-Feature (Settings), mehrere SSIDs pflegbar |
| Release | v1 | **v1** (Entscheidung 07/2026) |

Konfiguration (Settings → Sync):
- `home_ssids: []` (Liste, optional, nur wirksam wenn Modus B aktiviert)
- `require_vpn_outside_home: true` (Default, nicht abschaltbar ohne Warnung)
- `&lt;sync-host&gt;_host`, `&lt;sync-host&gt;_port`, `pinned_hostkey` (Ed25519, TOFU beim ersten Connect)

### 1.3 Linux-Client

| Prüfung | Umsetzung |
|---|---|
| Heimnetz | gleicher Erreichbarkeitstest (Python: `socket.create_connection`) |
| VPN-Status | `wg show wg0 latest-handshakes` via subprocess; Fallback: `ip link show type wireguard` |
| SSID (Modus B) | `nmcli -t -f active,ssid dev wifi` (NetworkManager) |
| Host-Key | paramiko `RejectPolicy` + gepinnter Key in `~/.config/&lt;sync-host&gt;player/known_host` |

*Windows (falls Desktop-Client später portiert wird): `wg.exe show` (WireGuard for Windows), SSID via `netsh wlan show interfaces` — Logik identisch.*

---

## 2. Play-Store-Veröffentlichung

### 2.1 Technische Anforderungen

| Punkt | Anforderung |
|---|---|
| App-ID | `de.schliemannosaurusrex.kaniamp` — **unveränderlich nach erstem Upload**; Domain schliemannosaurusrex.de vor Upload registrieren |
| Format | AAB (App Bundle), Play App Signing verpflichtend |
| Target SDK | jährliche Pflicht-Anhebung (Play-Policy); spricht für Kotlin/Media3 statt Kivy |
| Android 17 | `ACCESS_LOCAL_NETWORK` als Runtime-Permission deklarieren (LAN-Sync zu &lt;sync-host&gt;) |
| Foreground Service | `mediaPlayback` + `dataSync` Service-Types deklarieren (Player + Sync) |
| Storage | Scoped Storage: `READ_MEDIA_AUDIO`; Ordnerzugriff via SAF/MediaStore |

### 2.2 Data Safety & Review

| Angabe | Wert |
|---|---|
| Datenerhebung | keine (alle Daten lokal / eigener Server) |
| SSH-Credentials | lokal, Android Keystore, verschlüsselt at rest |
| Location (Modus B, ab v1) | „App-Funktionalität", nicht geteilt, Prominent Disclosure Dialog vor Permission-Request — **bereits im ersten Release deklarieren** (`ACCESS_FINE_LOCATION` im Manifest, Data-Safety-Angabe „Location", Review-Begründung vorbereiten) |
| Netzwerkzugriff | eigener Server des Nutzers (SSH), keine Dritt-Endpoints |

### 2.3 Konto & Prozess

| Schritt | Detail |
|---|---|
| Developer-Konto | 25 $ einmalig, Publisher-Name „SchliemannosaurusRex" (später änderbar) |
| Neue-Konto-Auflage | geschlossener Test: **12 Tester, 14 Tage** vor Production-Release → Zeitpuffer einplanen |
| Impressum | ladungsfähige Adresse im Store-Listing Pflicht (auch Privatperson, DE-Recht) |
| Branding | Name/Icon/Screenshots ohne Pulsar-Ähnlichkeit (Takedown-Risiko); Funktionsklon ist zulässig, Assets/Branding nicht |

### 2.4 Release-Pipeline (GitLab CI, self-hosted)

```
build (gradle bundleRelease)
  → sign (Upload-Key aus Bitwarden Secrets Manager)
  → upload (Gradle Play Publisher / fastlane supply → internal track)
  → promote (internal → closed → production, manuell)
```

---

## 3. Offene Punkte

- [x] Publisher-Name festgelegt: SchliemannosaurusRex
- [ ] Domain schliemannosaurusrex.de registrieren (vor erstem AAB-Upload) — *übernimmt Daniel*
- [ ] Projektseite aufsetzen (Store-Listing-Link, Datenschutzerklärung-Hosting) — *übernimmt Daniel*
- [x] Namenskollisions-Check: keine Konflikte (Name einzigartig)
- [ ] 12 Tester für Closed Testing organisieren
- [x] Entscheidung: **SSID-Modus B kommt in v1** (Opt-in, Modus A bleibt Default)