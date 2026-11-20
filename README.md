# DTM Voice

Android-App, die Apps per Sprache öffnen, schließen und bedienen kann. Gedacht für die Fahrt mit dem Motorrad, Sprachausgabe und Mikrofon laufen über den Helm (Sena).

## Einrichten (einmalig)
1. APK aus GitHub Actions (Artifact `DTM-Voice-apk`) installieren.
2. App öffnen: **1. Berechtigungen erteilen** (Mikrofon, Benachrichtigungen, Bluetooth).
3. **2. Bedienungshilfe aktivieren** → "DTM Voice" einschalten (nötig zum Schließen, Tippen, Scrollen).
4. **3. Über anderen Apps einblenden** erlauben (damit Apps aus dem Hintergrund geöffnet werden dürfen).
5. In der App-Liste die Schalter setzen: **AN** = per Sprache steuerbar, **AUS** = geschützt (z. B. Navigation, Sena).
6. **Sprachsteuerung starten**.

## Bedienung
Die App hört dauerhaft offline **nur** auf das Aktivierungswort (Standard: **"Titan"**, in der App änderbar). Alles andere wird ignoriert. Nach dem Aktivierungswort kommt ein Ton, dann innerhalb von 9 Sekunden **ein** Befehl. Verb vorne oder hinten geht beides: "öffne Spotify" oder "Spotify öffnen".

| Befehl | Wirkung |
|---|---|
| öffne *App* / *App* öffnen / mach *App* auf | App öffnen |
| schließe *App* / *App* schließen / *App* zu | App beenden |
| schließe diese App | App im Vordergrund beenden (stört eine App → einfach das sagen) |
| schließe alle Apps | Übersicht öffnen und alles schließen |
| tippe auf / klicke *Text* | Schaltfläche mit diesem Text in der offenen App antippen |
| scrolle runter / hoch | Liste scrollen |
| schreibe *Text* | Text ins Textfeld schreiben |
| suche *Text* | Text schreiben und Suche abschicken |
| zurück / Startbildschirm / letzte Apps | Navigation |
| Pause / Play / nächster Titel / vorheriger Titel | Medien |
| lauter / leiser | Lautstärke |
| Hilfe | liest die Befehle vor |
| Sprachsteuerung aus | Dienst beenden |

## Hinweise
- Apps mit Schalter **AUS** werden nie geöffnet oder geschlossen.
- Schließen läuft über Einstellungen → "Beenden erzwingen" (kurzer Wechsel auf die Einstellungen-Seite, danach automatisch zurück).
- Die Spracherkennung nutzt die Google-Erkennung des Handys (Deutsch, möglichst offline). Dafür in den Google-Spracheinstellungen das deutsche Offline-Paket herunterladen.
- Bluetooth-Headsets wechseln beim Mikrofon-Einsatz in den Telefonie-Modus (HFP), die Musikqualität sinkt dann.
- Der Signaturschlüssel liegt im Repo, damit Updates immer über die alte Version installiert werden können.
