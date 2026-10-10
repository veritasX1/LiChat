# LiChat – Architektur (1.0)

```
 Android-App (Kotlin)      Ubuntu-App (GTK4/Python)      lichat-cli (Team)
        │ uniffi                    │ pyo3                       │ pyo3
        └────────────── core/ (Rust): OpenMLS · Speicher verschlüsselt ─────┘
                                   │  nur MLS-Pakete (base64 in JSON, HTTPS)
                       server/ (Pi, hinter Reverse-Proxy und Wächtern)
             Einladungen · Geräte · Key-Packages · Postfach je Gerät · verschlüsselte Anhänge
```

## Was der Server weiß – und was nicht
| weiß | weiß nicht |
|---|---|
| Benutzernamen, Geräte-IDs, öffentliche Identitätsschlüssel | Inhalte, Anhänge, Gruppennamen, Kontaktlisten |
| dass für Gerät X ein Paket wartet (bis zur Abholung) | wer das Paket geschickt hat – das Postfach speichert keinen Absender (beim Senden meldet sich das Gerät aber mit seinem Token an) |
| verschlüsselte Anhänge (ohne Name und Typ) bis zur Löschfrist | wer mit wem spricht (über Postfach-Zeitpunkte hinaus) |

Zugestellte Pakete löscht er sofort beim Quittieren (`/v1/messages/ack`), nicht abgeholte nach 30 Tagen.
Gerätetokens liegen nur als SHA-256 in der Datenbank.

## Ablauf eines Einzelgesprächs (MLS)
1. Jedes Gerät lädt einen Vorrat an **Key-Packages** hoch (`POST /v1/key-packages`).
2. Anna holt sich Bernds Geräte und Schlüssel (`GET /v1/users/bernd`) und vergleicht den Sicherheitscode
   (QR wie bei LiNotes).
3. Anna holt je Gerät ein Key-Package (`POST /v1/key-packages/bernd`, jedes nur einmal), legt die MLS-Gruppe an
   und schickt das **Welcome** an Bernds Geräte sowie den Commit an ihre eigenen weiteren Geräte.
4. Nachrichten sind MLS-Application-Messages an alle Geräte der Gruppe (`POST /v1/messages`, `to` = Geräte-IDs).
5. Jedes Gerät holt sein Postfach (`GET /v1/messages`) und quittiert (`POST /v1/messages/ack`).

## Schutz vor Missbrauch
- Key-Packages: höchstens ein Abruf-Kontingent je Abrufer und Stunde (danach 429), dazu ein MLS-Last-Resort-Package,
  damit niemand den Vorrat eines Geräts leeren kann.
- Raten-Bremsen für Anmeldung, Einladungen, PINs und Anhänge; Anhänge höchstens 100 MB.

## Bekannte Grenzen (1.0)
- **Anmeldung:** Geräte melden sich mit einem Bearer-Token an (über TLS; in der Datenbank nur als SHA-256, auf dem Gerät
  versiegelt). Später signiert das Gerät seine Anfragen mit seinem Schlüssel.
- Der Server sieht, welches Gerät wann Pakete abholt, und beim Senden, welches Gerät sendet und an welche Geräte.
