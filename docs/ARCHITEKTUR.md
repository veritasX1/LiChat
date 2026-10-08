# LiChat – Architektur (Stufe 1)

```
 Android-App (Kotlin)      Ubuntu-App (GTK4/Python)      lichat-cli (Team)
        │ uniffi                    │ pyo3                       │ pyo3
        └────────────── core/ (Rust): OpenMLS · Speicher verschlüsselt ─────┘
                                   │  nur MLS-Pakete (base64 in JSON, HTTPS)
                       server/ (Pi, hinter Reverse-Proxy und Wächtern)
             Einladungen · Geräte · Key-Packages · Postfach je Gerät · ntfy-Anstoß
```

## Was der Server weiß – und was nicht
| weiß | weiß nicht |
|---|---|
| Benutzernamen, Geräte-IDs, öffentliche Identitätsschlüssel | Inhalte, Anhänge, Gruppennamen, Kontaktlisten |
| dass für Gerät X ein Paket wartet (bis zur Abholung) | wer das Paket geschickt hat (Sealed Sender) |
| ntfy-Adresse je Gerät (nur eigener ntfy) | wer mit wem spricht (über Postfach-Zeitpunkte hinaus) |

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

## Offen / nächste Schritte
- **Missbrauch:** Key-Packages lassen sich von jedem angemeldeten Gerät abrufen. Es braucht eine Grenze je
  Abrufer und eine „letzte Reserve“ (MLS Last-Resort-Key-Package), damit niemand den Vorrat leert.
- **Anmeldung:** Heute Bearer-Token. Später signiert das Gerät seine Anfragen mit seinem Schlüssel.
- **Anhänge:** separate, verschlüsselte Uploads mit eigener Löschfrist.
- **core/** in Rust (OpenMLS) mit einem Testfall „Nachricht hin und zurück, Schlüsselwechsel“ auf beiden
  Plattformen. Rust- und Gradle-Builds nie gleichzeitig auf dem ThinkPad.
- Docker-Container auf dem Pi wie `limail-testserver`, eigene Test-Domain, Testkonten anna/bernd/erna + Team.
