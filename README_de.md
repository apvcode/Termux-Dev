<div align="center">

# devx für Android

**Ein KI-Assistent für die Tasche und eine Fernbedienung für devx auf deinem Computer.**  
Ohne Konto, ohne Server von uns. Deine Schlüssel und Chats bleiben auf dem Handy.

<br/>

[![Android](https://img.shields.io/badge/Android-8.0%2B-3ddc84.svg?style=flat-square&labelColor=0a0c11)](https://github.com/apvcode/Termux-Dev/releases/tag/android-app)
[![Download APK](https://img.shields.io/badge/download-APK-00c2d1.svg?style=flat-square&labelColor=0a0c11)](https://github.com/apvcode/Termux-Dev/releases/tag/android-app)
[![Languages](https://img.shields.io/badge/languages-9-4f8cff.svg?style=flat-square&labelColor=0a0c11)](#)
[![devx: MIT](https://img.shields.io/badge/devx-MIT-4f8cff.svg?style=flat-square&labelColor=0a0c11)](https://github.com/apvcode/Termux-Dev/blob/main/LICENSE)

<br/>

[English](README.md) &nbsp;•&nbsp; [Русский](README_ru.md) &nbsp;•&nbsp; [Українська](README_uk.md) &nbsp;•&nbsp; **Deutsch** &nbsp;•&nbsp; [Español](README_es.md) &nbsp;•&nbsp; [Français](README_fr.md) &nbsp;•&nbsp; [Português](README_pt.md) &nbsp;•&nbsp; [中文](README_zh.md) &nbsp;•&nbsp; [日本語](README_ja.md)

</div>

---

## Auf einen Blick

<table>
<tr>
<td valign="top" width="25%"><b>Taschenassistent</b><br/>Jedes Modell deiner Wahl, Sprache, Fotos und Dateien, Gedächtnis, Skills, MCP, Websuche.</td>
<td valign="top" width="25%"><b>Notizbücher</b><br/>Lernen oder Ideen sammeln: Quellen, Lernplan, Lernkarten, Audio-Überblick, Antworten mit Quellen.</td>
<td valign="top" width="25%"><b>Sprache und Handy</b><br/>Wecker, Anrufe, Nachrichten, Orte und Wetter, geplante Aufgaben, Widgets.</td>
<td valign="top" width="25%"><b>Fernbedienung für devx</b><br/>Dieselben Sitzungen wie im Terminal: Antworten, Freigaben, Pläne, Dateien.</td>
</tr>
</table>

## Herunterladen

Die App hat auf Termux-Dev eine eigene Seite, getrennt von den Releases von devx (App und devx haben getrennte Versionen). Jede Version liegt dort als Datei mit Prüfsumme; neue Versionen kommen neben die alten, und eine Datei wird nie ersetzt:

**[Releases → devx for Android](https://github.com/apvcode/Termux-Dev/releases/tag/android-app)** → die neueste Datei, `devx-android-X.Y.Z.apk`

Öffne sie auf dem Handy und erlaube die Installation aus dieser Quelle. Android 8.0 (API 26) oder neuer. Updates werden über die alte Version installiert: Jedes Release ist mit demselben Schlüssel signiert, und eine veröffentlichte Version wird nie ersetzt.

## Start

1. Öffne die App und sag ihr deinen Namen.
2. **Einstellungen → Anbieter → Anbieter hinzufügen.** Wähle ein Preset oder gib eine Adresse und einen API-Schlüssel ein (OpenAI-kompatibel oder Anthropic-Format; das Format wird selbst erkannt). Lade die Modelle und wähle eines.
3. Schreibe oder tippe auf das Mikrofon.

Der API-Schlüssel wird verschlüsselt auf dem Handy gespeichert (Android Keystore) und geht nur an den Anbieter, den du hinzugefügt hast.

## Was sie kann

### Chat

- Mehrere Anbieter und Modelle, Denkaufwand pro Modell, Verzweigungen beim Bearbeiten einer Nachricht, temporäre Chats, Chats mit Bildschirmsperre oder Code gesperrt, Projekte (laufende Arbeit mit eigenen Anweisungen und eigenem Wissen) und Notizbücher (unten).
- Fotos, Video, Dateien und Kamera in einer Nachricht; PDF- und Text-Viewer; Export als Markdown oder PDF.
- Gedächtnis: Der Assistent behält nützliche Notizen und eine Zusammenfassung über dich, nur auf dem Handy. Du kannst sie lesen, bearbeiten und löschen. Die Zusammenfassung erneuert sich im Hintergrund selbst und zeigt, was neu darin ist; eine Notiz, die bald nicht mehr stimmt (Prüfung am Freitag), kann ablaufen; bei vielen Notizen werden zuerst die gesendet, die deine Frage betreffen.
- Skills (geschriebene Anleitungen, die der Assistent liest, wenn eine Aufgabe passt): eingebaut, importiert (`.skill` oder `SKILL.md`) oder von dir geschrieben.
- MCP-Server über https (GitHub, Dokumentation und andere) mit Freigaben und einem Nur-Lesen-Schalter.
- Websuche (liest Suchseiten selbst; ein kostenloser Tavily- oder Brave-Schlüssel in den Einstellungen macht sie spürbar genauer), Dateien und Archive, ein Terminal und Python in der App, in einem eigenen Ordner. Jedes davon lässt sich ausschalten.
- Formulare, die der Assistent für dich zeichnet: beschriftete Fragen mit Hinweisen, Auswahl (eine oder mehrere), Schieberegler und Schalter; Eingaben bleiben erhalten, und ein gesendetes Formular bleibt gesendet.
- Chat-Namen werden geschrieben, während die Antwort entsteht, mit einer kleinen Animation; der Chat, in dem du bist, ist in der Liste markiert.
- Reiche Antworten: Karten, Tabellen, Diagramme, Karten (Maps), Wetter, Timer, Checklisten, Mermaid-Diagramme, App-Karten aus Google Play und F-Droid. Wechselnde Begrüßungen auf dem Startbildschirm.
- Ein Vollbild-Editor mit Markdown-Vorschau für lange Nachrichten. Wähle mehrere Chats aus, um sie zu löschen oder zu archivieren.
- Tippe `@` ins Feld: ein Menü dessen, worauf du verweisen kannst (ein Notizbuch, ein Projekt, eine Datei der Bibliothek, eine geplante Aufgabe). Ein Notizbuch oder Projekt wird Teil des Satzes, in seiner Farbe, und der Assistent bekommt, was darin steckt. Per Stimme nenne einfach den Namen.
- Eine Suche über Chats, Notizbücher, Projekte, Dateien und Quellen, auch nach dem, was in Dokumenten, PDFs und Notizen steht.
- Füge Bilder und Dateien direkt ins Nachrichtenfeld ein (aus der Zwischenablage oder von der Tastatur). „Devx fragen“ zu markiertem Text berührt deine Zwischenablage nicht.
- Sicherung und Wiederherstellung von allem in einer Datei: Chats, Notizbücher und Projekte, Gedächtnis, Skills, Charakter, Anhänge und Einstellungen. API-Schlüssel und Token kommen nur hinein, wenn Sie ein Passwort setzen (damit werden sie verschlüsselt). Vor dem Wiederherstellen zeigt die App, was in der Datei ist und was sie ersetzt. Eine Wochenkopie entsteht von selbst in einem Ordner Ihrer Wahl und wird auf Wunsch zusätzlich in einen WebDAV-Ordner hochgeladen (Nextcloud, NAS); die letzten vier bleiben.
- Chats anheften und archivieren, einen einzelnen Chat sperren, einen Chat auf den Startbildschirm legen. „Devx fragen“ zu markiertem Text zitiert die Stelle als Karte in deiner nächsten Nachricht.
- Der Assistent arbeitet mit Dateien in einem eigenen Ordner: Er legt Dateien an und liest sie, erstellt und entpackt Archive, liest ein PDF seitenweise und sieht sich dessen Bilder an, bearbeitet Bilder und entpackt, ändert, packt neu und signiert APKs. Freigaben lassen sich direkt aus der Benachrichtigung geben (Erlauben / Ablehnen).
- Vier Widgets (eine Fragezeile, Schnelltasten, die letzten Chats, die fälligen Karten) und zwei Schnelleinstellungs-Kacheln (Fragen, Sprache).

### Notizbücher und die Bibliothek

- Ein Notizbuch ist ein Ort zum Lernen oder Sammeln von Ideen zu einem Thema. *Ideen*-Notizbücher bewahren Quellen und Chats zu einem Thema; *Lern*-Notizbücher fügen ein Ziel, einen Lektionsplan und deinen Fortschritt hinzu. Projekte sind getrennt: Sie sind für laufende Arbeit.
- Quellen: Dateien, Fotos (auch von der Kamera) und Webseiten. Jede bekommt vom Assistenten eine kurze Beschreibung; bitte ihn, Quellen im Web zu finden, und er fügt die besten Seiten hinzu.
- Antworten zu einem Notizbuch nennen ihre Quellen als kleine Chips (`notes.pdf · 12`); ein Tippen öffnet die Quelle, ein PDF auf der Seite.
- Ein Lern-Notizbuch unterrichtet Schritt für Schritt, prüft mit Formularen, schreibt den Plan selbst und führt Karten: Fragen kommen zur Wiederholung zurück, wenn es Zeit ist (je besser du eine kennst, desto später kommt sie wieder). Die Karten haben einen eigenen Bildschirm: die Frage, ein Tippen dreht die Karte um, vier Tasten (nochmal, schwer, gut, leicht) zeigen, wann sie wiederkommt, und am Ende eine Zusammenfassung; es funktioniert ohne das Modell und kostet nichts. Eine tägliche Erinnerung ist eine Benachrichtigung mit der Zahl der fälligen Karten.
- Der Assistent kann ein Notizbuch oder Projekt ändern (Name, Symbol, Farbe, Beschreibung, Anweisungen, Ziel) und nach einem Gespräch ein neues Notizbuch oder Projekt vorschlagen: Du bestätigst auf einer Karte.
- Teile einen Link, einen Text, ein PDF oder ein Bild aus jeder App: devx bietet einen neuen Chat oder eines deiner Notizbücher an, wo es zur Quelle wird (ein Link wird gelesen und gespeichert).
- Lernstatistik auf der Seite: Tage in Folge, die letzten sieben Tage als Balken; ein Widget auf dem Startbildschirm zeigt, wie viele Karten fällig sind, und startet die Wiederholung. Karten und der Überblick des Notizbuchs lassen sich anhören (ein *Audio-Überblick* wird zum Zuhören geschrieben und dann vorgelesen), und ein Notizbuch lässt sich als eine Markdown-Datei exportieren.
- Karten können ein Bild haben und lassen sich laut beantworten. Sie kommen aus Anki (`.apkg` oder Text) und aus CSV und gehen als Anki-Paket mit Bildern, als Anki-Text oder als CSV hinaus. Eine *Begriffskarte* zeigt die Ideen des Notizbuchs und wie sie zusammenhängen, eingefärbt danach, wie gut Sie sie kennen, mit *Erklären* und *Frag mich ab*. Ein Notizbuch lässt sich archivieren oder kopieren, um neu zu beginnen (Quellen und Lektionen, nicht die Chats).
- Die Bibliothek enthält jede Datei, die du je angehängt hast oder die der Assistent erstellt hat: Suche, Filter, wo sie benutzt wurde, an einen neuen Chat anhängen, überall löschen. PDF, Word, Folien, Tabellen, Text, Bilder und Seiten öffnen sich im eingebauten Viewer; Office-Originale bleiben erhalten.

### Aussehen, Charakter und Sicherheit

- Das Aussehen hat eine eigene Seite: drei schnelle Auswahlen (System, Schwarz, Hell) und ein Bildschirm mit weiteren Themen (Dunkelgrau, Mitternacht, Wald, Dämmerung, Glut, Ozean, Papier, Sakura, Minze). Das Thema kann sich von selbst nach der Tageszeit ändern (ein Tag- und ein Nachtthema), und der Wechsel ist animiert. Das unscharfe Glas lässt sich ausschalten: dann bleiben die Schaltflächen deckend.
- Charakter: wähle eine Art (ruhig, ohne Floskeln, ausführlich, freundlich, professionell, direkt, Mentor), stimme den Ton fein ab, gib dem Assistenten einen Namen und schreibe eigene Anweisungen. Ein Tippen setzt alles zurück.
- App-Sperre mit der Bildschirmsperre, einem Code oder Biometrie; einzelne Chats lassen sich ebenfalls sperren, und die Liste der letzten Apps blendet das Bild aus, solange eine Sperre aktiv ist.
- Fotos pro Nachricht, Benachrichtigungen über Antworten und Freigaben und ob ein Computer im „Alles“-Modus laufen darf, stehen in den Einstellungen.
- Daten und Sicherheit haben eine eigene Seite: die Sperre, die Kopien und der Papierkorb. Gelöschte Chats, Projekte und Notizbücher bleiben 30 Tage im Papierkorb und lassen sich zurückholen (gleich nach dem Löschen bietet eine Meldung „Rückgängig“ an). Eine Chatdatei, die nicht gelesen werden kann, wird beiseitegelegt statt verloren, und Sie werden informiert. Wovon sich die App erholt hat, steht unter **Über → Diagnose**.

### Sprachassistent

- Wähle Devx als Assistenten des Handys: Die Ein-Aus-Taste oder ein Wischen aus einer Ecke öffnet ein durchsichtiges Sprachfenster über jeder App.
- Wecker, Timer, Erinnerungen; Apps und Systemeinstellungen öffnen; Kontakte, Anrufe, Nachrichten und Kalender (die letzte Taste drückst du); Taschenlampe, Musik, Lautstärke; Helligkeit und „Nicht stören“.
- Orte, Routen und Wetter von OpenStreetMap und Open-Meteo, ohne Schlüssel. 2GIS und Google Maps lassen sich mit deinem eigenen Schlüssel hinzufügen.
- Sieht sich deine Galerie an, liest Benachrichtigungen auf dem Bildschirm, arbeitet in einem Ordner deiner Wahl, aber nur, wenn du fragst und nur, wenn du es erlaubt hast.
- Geplante Aufgaben: „jeden Morgen schick mir die Nachrichten über KI“. Sie laufen von selbst und kommen als Chat und Benachrichtigung.
- Sprechtempo, Stimme und ein eigenes schnelles Modell fürs Sprechen findest du unter **Einstellungen → Sprachassistent**.
- Eine natürliche Stimme Ihres Anbieters (Sprache im OpenAI-Format oder Gemini) kann die roboterhafte Telefonstimme ersetzen: Anbieter, Sprachmodell (aus seiner Liste oder einen Namen eintippen) und Stimme wählen und eine Probe anhören. Schlägt es fehl, wird der Grund angezeigt, und währenddessen liest die Telefonstimme.
- Tippe auf die Pille, um zu schreiben statt zu sprechen; halte sie und wische nach oben, um die App mit demselben Chat zu öffnen (ein Hinweis über der Pille zeigt immer, wie). Das Mikrofon hat eigene sanfte Töne statt des Pieptons des Spracherkennungsdienstes.
- „Nach einer Antwort weiter zuhören“ (freihändig) und die Sprache, die du sprichst, getrennt von der der App gewählt.
- Geplante Aufgaben haben einen eigenen Bildschirm: eine Zeit, die Tage, das Modell für jede, „jetzt ausführen“.

### Devx Remote

- Steuere devx auf deinem Computer: jeder Chat, Live-Antworten und Überlegungen, Freigabekarten, Fragen, der Plan (mit *Go and compact context*), Plan-/Agent-/YOLO-Modi, Modell und Anbieter, Kontext und Kosten, `/compact` und weitere Befehle. Dateien und Fotos gehen in beide Richtungen.
- Funktioniert bei geschlossenem Terminal. Das Handy erreicht den Computer direkt (gleiches WLAN, ein VPN wie Tailscale, ein weitergeleiteter Port) oder über ein kostenloses öffentliches Relay. Alles ist Ende-zu-Ende-verschlüsselt.
- Starte eine neue Sitzung auf dem Computer in einem Ordner deiner Wahl; füge einen Computer per Adresse (VPN, weitergeleiteter Port) hinzu, nicht nur per QR-Code.

Auf dem Computer:

```
devx remote start     # der Host, läuft ohne Terminal weiter
devx remote pair      # ein QR-Code: in der App scannen, die sechs Ziffern am Computer bestätigen
```

Ein Handy wird mit `/phones` in devx oder `devx remote revoke <name>` entfernt. Mehr im [devx-README](https://github.com/apvcode/Termux-Dev#readme).

## Sprachen

English, Русский, Deutsch, Español, Français, 日本語, Português, Українська, 中文: dieselben neun wie bei devx. **Einstellungen → Sprache**. Die Sprache bestimmt auch, worauf das Mikrofon hört.

## Datenschutz

- Chats, Gedächtnis, Skills und Schlüssel liegen auf dem Handy. Nichts geht an uns; es gibt keinen Server von uns.
- Deine Nachrichten gehen an den Anbieter deiner Wahl, und an einen MCP-Server oder Kartendienst nur, wenn ein Werkzeug es braucht.
- Galerie, Benachrichtigungen, Standort, Kontakte und der Ordner werden nur gelesen, wenn du fragst, und jedes lässt sich ausschalten.
- Anrufe, Nachrichten und Kalendereinträge werden nie ohne dein letztes Tippen gesendet.

## Bauen

```
./gradlew assembleRelease      # app/build/outputs/apk/release/app-release.apk
```

JDK 17 und das Android SDK. Signierung: Lege `signing.properties` (storeFile, storePassword, keyAlias, keyPassword) in `~/.config/devx-android/` ab, oder verweise mit `DEVX_SIGNING_PROPERTIES` darauf, oder nutze `keystore.properties` im Projektordner. Ohne eines davon wird mit dem Debug-Schlüssel signiert und ein installiertes Release lässt sich nicht aktualisieren. Bewahre den Schlüssel sicher auf: Er ermöglicht, dass Updates über die alte Version installiert werden.

Die App hat eigene Versionen. Ein Befehl baut sie, prüft den Signaturschlüssel und fügt `devx-android-X.Y.Z.apk` der Seite `android-app` von Termux-Dev hinzu (er fragt zuerst; eine Datei, die schon dort liegt, wird nie ersetzt; `--dry-run` zeigt, was passieren würde):

```
node scripts/release-android.mjs      # im devx-Ordner ausführen
```

## Stack

Kotlin, Jetpack Compose, OkHttp, Android Keystore. Die Icons sind [Tabler Icons](https://tabler.io/icons) (MIT); Mermaid ist für Diagramme eingebunden (MIT). Hinweise stehen in [NOTICE](NOTICE).

## Lizenz

Die App hat noch keine eigene Lizenzdatei; devx selbst steht unter MIT. Hinweise zu Drittanbietern (Tabler Icons, Mermaid) stehen in [NOTICE](NOTICE), die Lizenzen der Schriften in [licenses/](licenses).
