<div align="center">

<img src="assets/banner.svg" alt="DEVX" width="880" />

<br/>

**Ultraschneller, autonomer KI-Pair-Programmer und Vibe-Coding-Agent fürs Terminal.**  
Speziell entwickelt für **Android Termux**, **Windows**, **macOS** und **Linux**.

<br/>

[![npm](https://img.shields.io/npm/v/termux-dev.svg?style=flat-square&labelColor=0a0c11&color=00c2d1&label=npm)](https://www.npmjs.com/package/termux-dev)
[![CI](https://img.shields.io/github/actions/workflow/status/apvcode/Termux-Dev/ci.yml?style=flat-square&labelColor=0a0c11&label=CI)](https://github.com/apvcode/Termux-Dev/actions/workflows/ci.yml)
[![License: MIT](https://img.shields.io/badge/license-MIT-4f8cff.svg?style=flat-square&labelColor=0a0c11)](LICENSE)
[![Node.js](https://img.shields.io/badge/node-%3E%3D20-3fb950.svg?style=flat-square&labelColor=0a0c11)](https://nodejs.org)
[![TypeScript](https://img.shields.io/badge/TypeScript-5.4-4f8cff.svg?style=flat-square&labelColor=0a0c11)](https://www.typescriptlang.org)
[![Platform](https://img.shields.io/badge/platform-Termux%20%7C%20Windows%20%7C%20macOS%20%7C%20Linux-d97a3a.svg?style=flat-square&labelColor=0a0c11)](#quick-start)
[![Android app](https://img.shields.io/badge/Android-app%20%28APK%29-3ddc84.svg?style=flat-square&labelColor=0a0c11)](https://github.com/apvcode/Termux-Dev/releases/tag/android-app)

<br/>

[English](README.md) &nbsp;•&nbsp; [Русский](README_ru.md) &nbsp;•&nbsp; [Українська](README_uk.md) &nbsp;•&nbsp; **Deutsch** &nbsp;•&nbsp; [Español](README_es.md) &nbsp;•&nbsp; [Français](README_fr.md) &nbsp;•&nbsp; [Português](README_pt.md) &nbsp;•&nbsp; [Chinese](README_zh.md) &nbsp;•&nbsp; [Japanese](README_ja.md)

<br/><br/>

<a href="assets/demo.mp4"><img src="assets/demo.gif" alt="devx-Demo: Plan, Bau, Tests, Vorschau mit QR-Code" width="880" /></a>

<br/>

<img src="assets/preview.svg" alt="Die devx-Terminaloberfläche" width="880" />

</div>

---

## Auf einen Blick

<table>
<tr><td valign="top" width="33%"><b>Erst planen, dann handeln</b><br/>PLAN- und AGENT-Modus, Freigabe mit einem Klick, ein Plan in der Größe der Aufgabe</td><td valign="top" width="33%"><b>Standardmäßig sicher</b><br/>fragt vor Riskantem, versteckt Geheimnisse in Ausgaben, Rückgängig über git, optionales Kostenlimit</td><td valign="top" width="33%"><b>Jedes Modell</b><br/>OpenRouter, Gemini, DeepSeek, Groq, Mistral, OpenAI, Anthropic, Ollama und mehr</td></tr><tr><td valign="top" width="33%"><b>MCP eingebaut</b><br/>ein Panel, entfernte Server über HTTP, und der Agent richtet Server selbst ein</td><td valign="top" width="33%"><b>Fürs Terminal gemacht</b><br/>handytaugliche Layouts, 18 Farbschemata, Denken und Ausgabe lassen sich einklappen</td><td valign="top" width="33%"><b>Kosten im Blick</b><br/>laufende Kosten pro Sitzung, Denkaufwand je Modell, ein Limit, wenn du eins willst</td></tr>
</table>

## Schnellstart

```bash
npm install -g termux-dev
devx
```

Unter Android zuerst in Termux `pkg install nodejs-lts` ausführen.

---

## Highlights

Die Terminaloberfläche besteht aus einem umrahmten Prompt, der den Status in seinen Rändern trägt – Modus (`agent` / `plan` / `yolo`), Modell, Ordner, Git-Branch, Kontextanzeige und Kosten –, einer Kopfkarte, einem schattierten Echo deiner Nachricht, einem `●`-Rand für den Assistenten, `◆`-Zeilen für Werkzeugaufrufe mit ruhigen einzeiligen Ergebnissen und Diff-Boxen für Änderungen sowie einer Live-Arbeitsanzeige. Jedes Layout hat eine schmale Variante für Termux im Hochformat und misst Emoji und CJK-Text korrekt. Das ursprüngliche ASCII-Layout schaltest du in `/settings` → `Interface` ein.

- **Zwei-Gehirne-Architektur (PLAN- und AGENT-Modus):**  
  Wechsle sofort zwischen **PLAN** (sicherer Architekt, interaktiver Anforderungsfragebogen, keine Code-Dumps) und **AGENT** (autonome Dateiänderungen, Terminalbefehle, automatische Paketinstallation).
- **Planfreigabe mit einem Klick (`[Go / Go and compact context / Other]`):**
  Ist ein Architekturplan fertig, prüfst du ihn und bestätigst mit `Go`, damit der Coder-Agent sofort loslegt. `Go and compact context` fasst zuerst das lange Planungsgespräch zusammen und führt den freigegebenen Plan in einem kurzen, frischen Kontext aus (vom Handy aus funktioniert es genauso).
- **Multimodale Bildanhänge:**  
  Füge Screenshots direkt aus der Zwischenablage mit `Ctrl+V` oder `Alt+V` ein (auch `/image` oder ein eingefügter Dateipfad). Sie erscheinen als Badges wie `[Image #1 203kb]` und werden als echte Bilder an das Modell gesendet – außer das Modell liest bekanntermaßen nur Text. Benötigt `wl-clipboard` unter Wayland, `xclip` unter X11, `pngpaste` unter macOS.
- **Der Agent kann sich Bilder auf der Platte ansehen:**
  Frag ihn nach `screenshot.png` oder einem Mockup in einem beliebigen Ordner, und er öffnet die Datei mit `read_file`, so wie er ein angehängtes Bild sieht. PNG, JPEG, WebP und GIF bis 5 MB; der Dateiinhalt muss wirklich ein Bild sein. Nur die neuesten Bilder werden vollständig gesendet, ältere bleiben als Vermerk im Chat (das Modell kann die Datei erneut öffnen). Ein Modell ohne Bildverständnis bekommt stattdessen eine klare Meldung.
- **Eingebauter Live-Webserver (`/serve`):**  
  Sieh Web-Apps und HTML5-Spiele sofort auf Port 3000 an. Unter Android öffnet er sich nahtlos über `termux-open-url`.
- **Hintergrundaufgaben (`/tasks`):**
  Der Agent kann einen Dev-Server, einen Watcher oder einen langen Testlauf im Hintergrund starten und weiterarbeiten. Er kann auf eine Zeile warten (`ready|error`), die Ausgabe später lesen und erfährt, wenn eine Aufgabe endet oder etwas ausgibt, wonach er gefragt hat; bist du dann am Prompt, greift er die Neuigkeit selbst auf. Ein Badge `◈ 2 bg` im Prompt-Rahmen zeigt, was läuft, und `· 1 failed` kommt hinzu, wenn eine Aufgabe schlecht endete, bis devx es dem Agenten gesagt hat; `/tasks` listet die Aufgaben, `/tasks <id>` zeigt die Ausgabe, `/tasks stop <id|all>` beendet sie. Das Starten einer Aufgabe verlangt wie `bash` eine Freigabe; beim Beenden von devx wird alles gestoppt. Mit `"backgroundAutoWake": false` reagiert der Agent nicht mehr von selbst.
- **Sofortiges Zurückrollen (`/undo`):**  
  Jede Änderung mit den Dateiwerkzeugen (`write_file`, `edit_file`) wird aufgezeichnet und lässt sich mit einem Befehl zurücknehmen. Änderungen durch Shell-Befehle (`bash`) werden **nicht** erfasst, und Snapshots liegen nur im Speicher der aktuellen Sitzung.
- **Selbstheilende Diagnose und Auto-Installer:**  
  Eingebaute Unterstützung für TypeScript (`tsc`), JavaScript (`node --check`), Python (`py_compile`) und Rust (`cargo check`). Die KI findet und behebt Fehler automatisch, bevor ein Durchgang endet.
- **Projekt-Gedächtnis (`.devx/memory.md`):**  
  Dauerhafter Projektkontext über Sitzungen hinweg mit `/memory add <Regel>`.
- **Benachrichtigungen, wenn devx auf dich wartet:**
  Braucht der Agent eine Freigabe oder stellt er dir eine Frage (`◆ Clarify`) und das Terminal ist nicht im Vordergrund, bekommst du eine Benachrichtigung (das eigene Protokoll von kitty, `notify-send` unter Linux, `osascript` unter macOS; unter Termux eine Android-Benachrichtigung mit kurzer Vibration). Auch eine fertige Aufgabe wird gemeldet, mit dem Anfang der Antwort. Das Terminal muss Fokuswechsel melden (die meisten modernen tun das); kann devx nicht erkennen, ob du hinschaust, bleibt es still, statt sich vor dich zu schieben.
- **Frühere Chats (`search_chats`):**
  Sagst du „wie letztes Mal besprochen“ oder „in dem Chat über den Parser“, kann der Agent deine gespeicherten Chats nach Stichwörtern durchsuchen (in jeder Sprache, andere Wortformen treffen auch: Seite / Seiten) und den benötigten lesen, ein paar Nachrichten auf einmal. Das ist nur lesend und lokal, also fragt es nichts; der gerade offene Chat wird nicht durchsucht; Werkzeugausgaben werden nicht indiziert; und was er liest, wird dem Modell als Daten übergeben, nicht als Anweisung. Bei einer normalen Aufgabe nutzt der Agent es nicht.
- **18 Farbschemata auf reinem Schwarz (`#0a0a0c`):**  
  Tiefes OLED-Schwarz für ablenkungsfreies Vibe-Coding im Terminal, mit vollständigen Paletten statt einer einzigen Akzentfarbe: Cyan Cyber, Synthwave Purple, Matrix Hacker, Solar Amber, Ruby Crimson, Pure Monochrome, Nord Frost, Dracula, Tokyo Night, Gruvbox, Catppuccin Mocha, Solarized Dark, Rose Pine, Monokai, GitHub Dark, Deep Ocean, Sunset Glow und Forest Moss. Siehe [Farbschemata](#farbschemata).
- **Intelligenter Sitzungsverlauf (`/resume` und `/session`):**  
  Setze Sitzungen fort – die letzten 20 Nachrichten werden in vollem Markdown dargestellt – und verfolge eindeutige Sitzungs-IDs (`#a8f9z`).
- **Intelligenter mehrzeiliger Editor (`edit_file 2.0`):**  
  Kollisionssicheres Bearbeiten von Code mit Zeilennummern bei doppelten Treffern und `replaceAll`-Unterstützung.
- **Umfassende Testsuite (Vitest):**  
  Testabdeckung für Agentenschleife, Dateisysteme, Sicherheitswächter, Snapshots und Kontextverlauf mit automatisiertem CI.
- **Oberfläche in 9 Sprachen:**  
  English (Standard), Русский, Українська, Deutsch, Español, Français, Português, 中文 und 日本語. Wähle in `/settings` → `Language`; die Auswahl gilt sofort und wird gespeichert. Meldungen, Hinweise und die Einstellungen sind übersetzt; Code, Werkzeugnamen und die Antworten des Modells nicht.
- **Universelle Unterstützung für KI-Anbieter:**  
  **OpenRouter**, **Google Gemini**, **DeepSeek**, **Groq**, **Mistral**, **OpenAI**, **Anthropic**, **Alibaba** und lokale Modelle (**Ollama**, **LM Studio**).
- **Eigene Anbieter:**  
  `/provider` → **Anbieter hinzufügen**: Gib ihm einen Namen, eine Adresse (jede OpenAI- oder Anthropic-kompatible API) und einen Schlüssel. Er wird in `~/.devxrc.json` gespeichert und steht in der Liste neben den bekannten; wähle ihn erneut, um ihn zu verwenden, Namen, Adresse oder Schlüssel zu ändern oder ihn zu löschen. Ein Server auf dem eigenen Rechner (LM Studio, lokales Gateway) lässt sich ohne Schlüssel hinzufügen.

---

## Installation und Einrichtung

### Voraussetzungen
- **Node.js**: `v20.0.0` oder höher
- **npm**, **pnpm** oder **yarn**

### Node.js und npm installieren

devx braucht Node.js 20 oder neuer (npm ist dabei). Wähle dein System:

```bash
# Windows (PowerShell)
winget install OpenJS.NodeJS.LTS

# macOS (Homebrew)
brew install node

# Ubuntu / Debian (NodeSource: die Version im eigenen Repository der Distribution ist oft älter als 20)
curl -fsSL https://deb.nodesource.com/setup_lts.x | sudo -E bash -
sudo apt-get install -y nodejs

# Fedora
sudo dnf install -y nodejs

# Arch Linux
sudo pacman -S nodejs npm

# Android (Termux)
pkg update && pkg install -y nodejs-lts
```

Öffne das Terminal neu und prüfe: `node -v` (sollte `v20` oder höher sein) und `npm -v`. Jedes System: der Installer auf [nodejs.org](https://nodejs.org/en/download) oder [nvm](https://github.com/nvm-sh/nvm).

### Schnelle globale Installation (empfohlen)

```bash
npm install -g termux-dev
```

### Android (Termux)

```bash
# 1. Pakete aktualisieren und NodeJS installieren
pkg update && pkg install -y nodejs-lts

# 2. devx global über npm installieren
npm install -g termux-dev

# 3. devx überall starten!
devx
```

### Aus dem Quellcode bauen (Entwickler)

```bash
git clone https://github.com/apvcode/Termux-Dev.git
cd Termux-Dev
npm install
npm link
```

### Vollständige Deinstallation

So entfernst du `devx` samt Konfiguration, gespeicherten Sitzungen und Daten:

```bash
# 1. Das globale npm-Paket deinstallieren
npm uninstall -g termux-dev

# 2. Konfiguration und Sitzungsverlauf entfernen (Linux / macOS / Termux)
rm -rf ~/.devx ~/.devxrc.json

# (Windows PowerShell)
Remove-Item -Recurse -Force "$HOME\.devx", "$HOME\.devxrc.json" -ErrorAction SilentlyContinue
```

---

## Erste Schritte

Starte `devx` in einem beliebigen Projektordner:

```bash
devx
```

* Beim ersten Start öffnet `devx` einen interaktiven Einrichtungsassistenten für deinen KI-Anbieter und den API-Schlüssel.
* Direkt im **PLAN-Modus** starten:
  ```bash
  devx --plan
  ```
* **Headless-Einmalmodus** (Skripte, CI/CD, Termux:Widget):
  ```bash
  devx -p "review latest git diff and find security bugs"
  devx -p "fix build errors" --yolo
  ```

---

## Fernsteuerung vom Handy

**Die App holen:** Die Android-App hat auf Termux-Dev eine eigene Seite: **[Releases → devx for Android](https://github.com/apvcode/Termux-Dev/releases/tag/android-app)**. Jede Version liegt dort als Datei `devx-android-X.Y.Z.apk` (die neueste auf dem Handy öffnen und die Installation aus dieser Quelle erlauben); neue Versionen kommen neben die alten. Den Quellcode der App und ihre eigene README findest du im [Branch `android`](https://github.com/apvcode/Termux-Dev/tree/android). In der App wird ein Handy mit `/phones` in devx oder `devx remote revoke <name>` vom Computer entfernt.

devx 2.0 lässt sich über die devx-Android-App steuern. Ein kleiner Host läuft im Hintergrund auf deinem Computer; das Handy verbindet sich mit ihm und sieht und steuert dieselben Sitzungen: jeden Chat, Live-Antworten und Denkvorgänge, Freigabekarten, Fragen, den Plan, die Modi plan/agent/YOLO, Modell und Anbieter, Kontext und Kosten, `/compact` und die übrigen Befehle. Dateien und Fotos lassen sich vom Handy senden, und der Agent kann Dateien und Bilder zurückschicken: Sie erscheinen im Handy-Chat als Karten. Alles funktioniert auch bei geschlossenem Terminal.

Die App ist außerdem ein eigener Taschenassistent, ohne Konto und ohne einen Server von uns:

- **Chat** mit jedem Anbieter, den du hinzufügst (OpenAI- oder Anthropic-kompatibel): Denkstufen, Fotos und Dateien, Projekte, Gedächtnis, Skills und MCP-Server.
- **Sprache**: Stelle sie als Assistent des Handys ein, und die Einschalttaste öffnet sie. Sie stellt Wecker, Timer und Erinnerungen, öffnet Apps, findet Kontakte, bereitet Anrufe und Nachrichten vor und steuert Helligkeit, „Bitte nicht stören“, Musik und Lautstärke.
- **Orte und Wetter** von OpenStreetMap, ohne Schlüssel; auf Wunsch schaut sie sich auch deine Fotos und Benachrichtigungen an.
- **Geplante Aufgaben**: „schick mir jeden Morgen die KI-Nachrichten“.
- **Antworten als Karten**: Tabellen, Diagramme, Karten, Wetter, Timer, Checklisten, Schaubilder und App-Karten aus Google Play und F-Droid.
- **Notizbücher**: ein Ort zum Lernen oder Sammeln von Ideen zu einem Thema, mit eigenen Quellen (Dateien, Fotos, Webseiten; einen Link kannst du aus jeder App teilen). Ein Lern-Notizbuch hat ein Ziel und einen Lektionsplan, Fragen als Formulare, Lernkarten mit verteilter Wiederholung (eigener Bildschirm, Widget, Erinnerung), Lernstatistik, einen Audio-Überblick zum Anhören und Antworten, die ihre Quellen als antippbare Chips nennen.
- **`@` in der Nachricht** verweist auf ein Notizbuch, ein Projekt, eine Datei oder eine geplante Aufgabe; der Assistent bekommt, was darin steckt. Eine Suche durchsucht Chats, Notizbücher, Dateien und den Text darin.
- **Bibliothek und Backup**: jede Datei, die du angehängt hast oder der Assistent erstellt hat, mit eingebautem Viewer für PDF, Word, Folien und Tabellen; alles in einer Backup-Datei oder als automatische wöchentliche Kopie in einen Ordner deiner Wahl.
- **Eigenes Aussehen und Charakter**: schwarzes, dunkelgraues oder helles Design, Art und Name des Assistenten, App-Sperre mit Bildschirmsperre oder Biometrie und Sperren für einzelne Chats. Chats lassen sich anheften, archivieren und auf den Startbildschirm legen; vier Widgets und zwei Schnelleinstellungs-Kacheln.
- **Neun Sprachen**, dieselben wie bei devx.

```
devx remote start     # den Host starten (läuft ohne Terminal weiter)
devx remote pair      # zeigt einen QR-Code: in der App scannen, die sechs Ziffern hier bestätigen
devx remote status    # wer verbunden ist, welche Sitzungen offen sind
devx remote devices   # gekoppelte Handys      devx remote revoke <name>   # eines entfernen
devx remote service   # mit dem Computer starten (systemd / launchd / Autostart-Ordner); auch in /settings. `service off` schaltet es aus
```

Wie das Handy den Computer erreicht, ohne einen Server von uns: zuerst direkt (gleiches WLAN, ein VPN wie Tailscale oder ein weitergeleiteter Port 47821), und wenn das nicht geht (mobiles Internet, Carrier-NAT) über ein kostenloses öffentliches MQTT-Relay. Das Handy versucht beides gleichzeitig und probiert weiter; das Relay sieht immer nur verschlüsselte Pakete. Das Übertragungsformat und seine Sicherheit stehen in [docs/remote-protocol.md](docs/remote-protocol.md).

Sicherheit: Ein Handy wird nur mit einem Einmalcode von `devx remote pair` (5 Minuten gültig) **und** deiner Bestätigung hier hinzugefügt, wobei beide Bildschirme dieselben sechs Ziffern zeigen. Die Schlüssel sind P-256 (der des Handys verlässt nie seinen Keystore), jede Verbindung wird mit frischen Schlüsseln signiert und verschlüsselt (AES-256-GCM). Fernzugriff ist Shell-Zugriff auf diesen Computer: Standardmäßig fragt jeder Befehl auf dem Handy um Freigabe, und der Modus „Everything“ ist aus, bis du ihn für eine Sitzung einschaltest.

Hat das Handy das devx-Gedächtnis eingeschaltet, geht sein Name in den Chat ein, und der Agent hier bekommt ein Werkzeug `phone_memory`, das das Handy nach seinen Notizen fragt: Nichts wird auf diesen Computer kopiert.

## Slash-Befehle

Tippe `/` im Prompt, um die Befehlspalette mit Autovervollständigung zu öffnen:

| Befehl | Beschreibung |
| :--- | :--- |
| **`/plan`** | In den **PLAN**-Modus wechseln (Architekt und Anforderungsplaner) |
| **`/agent`** | In den **AGENT**-Modus wechseln (Coder und autonomer Ausführer) |
| **`/mcp`** | Model-Context-Protocol-(MCP-)Server und externe Werkzeuge verwalten (`/mcp reload`) |
| **`/usage`** | Ein Panel (schließen mit `Esc`): was das Kontextfenster füllt (Systemprompt, Werkzeugschemata, Nachrichten, Werkzeugergebnisse, Bilder), die Sitzungskosten und wie sicher sie sind, Tokens, gecachte Eingabe, Datenverkehr |
| **`/export`** | Die Sitzung als saubere GitHub-Flavored-Markdown-Mitschrift exportieren |
| **`/theme [name]`** | Das Farbschema wechseln: durchsuchbare Liste mit 18 Paletten und Live-Vorschau (siehe [Farbschemata](#farbschemata)) |
| **`/doctor`** | Diagnose der Umgebung (Node, Termux, Compiler, API-Schlüssel) |
| **`/image`** | Das Bild aus der Zwischenablage einfügen (wie `Ctrl+V` / `Alt+V`) |
| **`/serve [port]`** | Lokalen HTTP-Server für Web-/Spielvorschau starten (`/serve stop` beendet ihn) |
| **`/memory`** | Das Projektgedächtnis ansehen, ergänzen (`/memory add <Fakt>`) oder leeren |
| **`/copy`** | Die **letzte Antwort** der KI vollständig in die Zwischenablage kopieren (auch über ssh) |
| **`/rename`** | Chat umbenennen: `/rename <Name>`, ohne Namen benennt die KI ihn neu (`/title` ist dasselbe) |
| **`/permissions`** | Was ohne Rückfrage erlaubt ist („Session“ / „Always“ auf einer Freigabekarte); `/permissions clear` setzt zurück |
| **`/phones`** | Die mit devx Remote gekoppelten Handys: Wähle eines zum Entfernen, es wird sofort getrennt und kann sich nicht mehr verbinden, bis es neu gekoppelt wird |
| **`/rewind`** | Zu einer **deiner Nachrichten** zurückgehen: der Chat danach wird entfernt (das Modell vergisst ihn auch), die Nachricht kommt zum Bearbeiten in die Eingabezeile zurück; auch die Dateien lassen sich zurücksetzen (mit git) |
| **`/tasks`** | Hintergrundaufgaben: Liste, `/tasks <id>` für die Ausgabe, `/tasks stop <id\|all>`, `/tasks clear` |
| **`/undo`** | Alle Dateiänderungen des letzten KI-Durchgangs rückgängig machen |
| **`/diff`** | Farbigen Git-Diff der geänderten Projektdateien anzeigen |
| **`/commit`** | Von der KI erzeugte semantische Commit-Nachricht und Git-Commit |
| **`/status`** | Git-Repository-Status anzeigen |
| **`/session`** | Aktive Sitzungs-ID, Statistik und Metadaten anzeigen |
| **`/resume`** | Gespeicherte Chat-Sitzungen durchsuchen und fortsetzen |
| **`/session del`** | Gespeicherte Sitzungen gezielt oder gesammelt löschen |
| **`/settings`** | Farbschema, Oberfläche, YOLO-Auto-Freigabe, Projektgedächtnis, Updateprüfung und Sprache der Oberfläche einstellen |
| **`/update`** | Nach einer neuen Version suchen: eine Karte mit den Neuerungen, dann **Jetzt aktualisieren**, **Später** oder (beim Start) **Diese Version überspringen**. Die Installation läuft Schritt für Schritt auf einem Live-Bildschirm, prüft, dass npm die Version wirklich hat, und startet devx neu. Bei einem Fehler siehst du den Grund und den Befehl für die Handarbeit |
| **`/model`** | Das Modell des aktiven Anbieters mit Live-Suche wechseln |
| **`/effort [level]`** | Den Denkaufwand setzen. Angeboten werden nur Stufen, die das aktuelle Modell unterstützt (siehe [Denkaufwand](#denkaufwand)) |
| **`/provider`** | Den Anbieter wechseln (OpenRouter, Gemini, Groq, DeepSeek, Local) |
| **`/compact`** | Chat-Kontext-Tokens verdichten und zusammenfassen (der verdichtete Chat wird sofort gespeichert, `/resume` liefert also den kurzen) |
| **`/clear`** | Den Kontext leeren und eine neue Sitzung beginnen (die alte bleibt gespeichert, siehe `/resume`) |
| **`/init`** | Einen `AGENTS.md`-Entwicklerleitfaden im Projektstamm erzeugen |
| **`/help`** | Alle verfügbaren Befehle anzeigen |
| **`/exit`** | devx sauber beenden |

---

## Tastenkürzel

- **`Tab`** — wechselt sofort zwischen **PLAN** und **AGENT**, ohne deinen Entwurfstext zu verlieren.
- **`Ctrl+V`** / **`Alt+V`** — Bild aus der Zwischenablage direkt in den Prompt einfügen.
- **`@filename`** — schnelle unscharfe Dateivervollständigung und vollständiges Einbetten der Datei.
- **`Backspace`** — löscht Badges wie `[Pasted text #1]` und `[1.png 203kb]` mit einem Druck ganz.
- **`Ctrl+Backspace`** / **`Alt+Backspace`** — löscht den Text wortweise (auch während der Agent arbeitet).
- **`Ctrl+Enter`** — Beim Tippen: neue Zeile (neuer Absatz) statt Senden. `Alt+Enter` und `Shift+Enter` tun dasselbe, wenn das Terminal sie meldet; in einem Terminal, das sie nicht von `Enter` unterscheiden kann (etwa eine Handytastatur), tippe `\` vor `Enter`. devx bittet das Terminal nur dort, sie zu unterscheiden, wo das nachweislich funktioniert (kitty, Ghostty, WezTerm, iTerm2, foot, Alacritty); Termux und andere Terminals, die diese Anfrage als Cursor- oder Stilcode lesen, bekommen sie nie. `DEVX_EXTENDED_KEYS=1` (oder `0`) erzwingt sie (oder schaltet sie aus).
- **`Ctrl+P`** / **`Ctrl+O`** — Klappt das Denken des Modells (`Ctrl+P`) und die Befehle mit ihrer Ausgabe (`Ctrl+O`) im ganzen Chat auf oder zu, auch was schon weiter oben steht. Standardmäßig ist alles eingeklappt; funktioniert während der Agent arbeitet und im Prompt.
- **`Esc`** — die laufende Antwort und alles, was sie gestartet hat (einen laufenden Befehl), stoppen; das Getippte wartet im nächsten Prompt. Esc schließt außerdem jede Liste und Karte.
- **`Ctrl+C`** — die laufende Generierung sofort stoppen oder beenden.

---

## Denkaufwand

`/effort` legt fest, wie viel das Modell vor der Antwort nachdenkt: `off`, `low`, `normal`, `high`, `xhigh`, `max` oder `auto` (der eigene Standard des Modells, es wird nichts gesendet). Modelle unterscheiden sich, daher bietet das Menü **nur die Stufen, die das aktuelle Modell unterstützt**:

```
╭─ effort ─────────────────────────────────── claude-sonnet-4-5 ─╮
│  ❯ ● auto    model default, nothing is sent                     │
│    ○ off     no reasoning                                       │
│    ○ normal  balanced (~6k thinking tokens)                     │
│    ○ high    deep reasoning (~16k thinking tokens)              │
│                                                                 │
│   Not offered by this model: max.                               │
╰─────────────────────────────────────────────────────────────────╯
```

- `/effort` öffnet das Menü, `/effort high` setzt eine Stufe direkt (`medium`, `none`, `maximum` und Ähnliches funktionieren als Aliase), `/effort auto` kehrt zum Standard des Modells zurück, `/effort refresh` aktualisiert die Daten, aus denen das Menü entsteht (siehe unten).
- Die Wahl wird **pro Modell** gespeichert. Nach dem Wechsel zu einem Modell, das die gespeicherte Stufe nicht anbietet, wird nichts gesendet. Die aktuelle Stufe steht neben dem Modellnamen (`o3 · think high`; `think auto` heißt: Standard des Modells). Während der Agent arbeitet, zeigt die Statuszeile, wie viele Tokens das Modell bisher für deine Anfrage geschrieben hat (`↓ 364 tokens`), und die Zeile nach dem Durchgang fasst es zusammen (`18.1k in · 59 out`).
- `devx -p "task" --effort high` setzt eine Stufe nur für einen Lauf (sie wird nie gespeichert).
- Antwortet ein Endpunkt mit 400 und beanstandet den Reasoning-Parameter, wiederholt devx die Anfrage einmal ohne ihn, sagt dir Bescheid und sendet ihn nicht mehr.

**Woher die Stufen kommen** (die erste Quelle, die das Modell kennt, gewinnt; das Menü nennt die verwendete):

1. **Dein eigenes Profil** in `~/.devxrc.json` (`reasoningProfiles`, unten).
2. **Der Endpunkt selbst.** OpenRouter listet Reasoning-Metadaten für jedes Modell (welche Stufen es akzeptiert, ob es abschaltbar ist); Ollama meldet eine „thinking“-Fähigkeit; LiteLLM und viele Gateways ergänzen Flags in ihrer Modellliste. Ein **eigener oder fremder Anbieter wird einmal gefragt**: einige kleine Anfragen an *seine eigene* Base-URL mit dem konfigurierten Schlüssel (`GET /models`, auf einem lokalen Rechner auch `POST /api/show`, bei LiteLLM `GET /model/info`). Die Antwort wird einen Tag in `~/.devx/capabilities.json` zwischengespeichert. Die großen Hersteller (OpenAI, Anthropic, Google ...) werden nicht gefragt, ihre Listen sagen nichts über Reasoning. Abschalten mit `"probeEndpoints": false` in `~/.devxrc.json`.
3. **Der [models.dev](https://models.dev)-Katalog**: Reasoning-Optionen von etwa 5700 Modellen bei 213 Anbietern (Stufen, Schalter, Token-Budgets). Ein Snapshot wird mit devx ausgeliefert, daher wird standardmäßig nichts heruntergeladen. `/effort refresh` lädt den aktuellen nach `~/.devx/` und fragt den Endpunkt erneut; nur dann kontaktiert devx models.dev, und nur weil du den Befehl ausgeführt hast. Derselbe Download aktualisiert die Preise für die Kostenanzeige (siehe Kosten).
4. **Regeln nach Modellnamen**, der Rückfall für alles, was die Quellen nicht beschreiben (Tabelle unten).

Wie eine Stufe in die Anfrage geschrieben wird, hängt weiterhin von der API deiner Base-URL ab; dieser Teil ist in devx eingebaut.

Regeln nach Modellnamen (Rückfall):

| Modell | Stufen | Gesendet als |
| :--- | :--- | :--- |
| OpenAI o-Serie, gpt-oss | low, normal, high | `reasoning_effort` (`reasoning.effort` bei OpenRouter) |
| GPT-5, 5.1, 5.2 | off (minimal bei GPT-5), low, normal, high, ab 5.2 zusätzlich xhigh | `reasoning_effort` |
| Grok 3 mini | low, high | `reasoning_effort` |
| Claude 3.7 / 4 / 4.5 | off, low, normal, high, xhigh, max (Opus 4 und 4.1 ohne max) | `thinking.budget_tokens` (Anthropic), `reasoning.max_tokens` (OpenRouter) |
| Gemini 2.5 Pro / Flash | low bis max (Flash auch off) | `thinking_budget` (Google), `reasoning.max_tokens` (OpenRouter) |
| Qwen3 | off, low, normal, high, max | `enable_thinking` / `thinking_budget` (DashScope) |
| DeepSeek V3.1+, GLM 4.5+ | off, normal | `reasoning.enabled`, `thinking.type` |
| DeepSeek-R1, QwQ, Grok 4, Magistral | keine: sie denken immer | nichts |

Ein Modell, das keine Quelle beschreibt, bekommt keine Stufen statt einer Vermutung. Wenn du weißt, wie es eine Reasoning-Einstellung annimmt, beschreibe es in `~/.devxrc.json`:

```json
{
  "reasoningProfiles": {
    "my-model": {
      "levels": ["off", "normal", "high"],
      "param": "reasoning_effort",
      "values": { "off": "none", "normal": "medium", "high": "high" }
    }
  }
}
```

`param` darf verschachtelt sein (`"reasoning.effort"`). Ein Profil überschreibt für diese Modell-ID auch die eingebaute Tabelle.

---

## Tippen, während der Agent arbeitet

Der Prompt-Rahmen bleibt während einer Aufgabe am unteren Terminalrand (mit Live-Kontext und Kosten), und die Arbeit des Agenten scrollt darüber. Tippe hinein, während der Agent Dateien liest, Befehle ausführt oder nachdenkt, und drücke Enter: Die Nachricht kommt in die Warteschlange, und der Agent erhält sie **bei seinem nächsten Schritt**, zwischen zwei Werkzeugaufrufen; er arbeitet also weiter, berücksichtigt aber deine Anmerkung. Eine Nachricht, die du tippst, während das Modell seine Endantwort schreibt, bekommt eine eigene Antwort, statt verworfen zu werden. `Ctrl+Enter` beginnt in deiner Nachricht eine neue Zeile, statt zu senden (auch `Alt+Enter` und `Shift+Enter`; ein `\` vor `Enter` tut das in Terminals, die sie nicht unterscheiden können). `Ctrl+Backspace` löscht ein Wort. Tippe dort `/`, und die Liste aller Befehle öffnet sich (Pfeile scrollen, `Tab` vervollständigt, `Enter` führt aus): Die Befehle, die nur eine Einstellung zeigen oder ändern (`/settings`, `/model`, `/theme`, `/usage`, `/mcp`, `/tasks`, `/help` und andere), öffnen sich sofort, während der Agent arbeitet, und er wartet; die, die die Unterhaltung ändern (`/compact`, `/new`, `/undo`, `/plan` ...), laufen, wenn der Agent fertig ist. Mit den Pfeiltasten hoch und runter holst du früher gesendete Zeilen zurück, auch aus früheren Chats (gespeichert in `~/.devx/history.jsonl`). `↓` holt eine wartende Nachricht zum Bearbeiten in die Zeile zurück; `Enter` auf einer leeren Zeile sendet sie **jetzt** (die aktuelle Antwort wird gestoppt). Die Pfeiltasten funktionieren auch bei eingeschaltetem Caps Lock und Num Lock. Eine Nachricht, die mit einem Dateipfad beginnt (`/home/me/boot.img flash this`), ist eine Nachricht, kein Befehl. `Esc` (oder `Ctrl+C`) stoppt den Agenten und legt das Getippte in den nächsten Prompt zurück. `@file`-Erwähnungen funktionieren auch in diesen Nachrichten. Solange eine Freigabekarte oder eine Frage auf dem Bildschirm ist, tritt die Zeile beiseite, sodass dein `y` / `n` nie in der Nachricht landet. Ausschalten mit `"ui": { "typeAhead": false }` in `~/.devxrc.json`.

---

## Kontextfenster

Die Fenstergröße wird nachgeschlagen, nicht erfunden. In dieser Reihenfolge: `contextLimits` in `~/.devxrc.json` (deine eigene Zahl je Modell-ID), der [models.dev](https://models.dev)-Katalog (die Zahl des Herstellers für etwa 5000 Modelle; ein Snapshot wird mit devx ausgeliefert, `/effort refresh` lädt den aktuellen), der bei der Modellwahl gespeicherte Wert, devx' Modelltabelle, eine Regel nach Modellnamen und erst dann der Standardwert 128k. `/usage` sagt, welcher verwendet wurde, und warnt, wenn es eine Schätzung ist. Alte Nachrichten werden ab 85 % des Fensters verworfen: Ein zu kleines Fenster lässt devx zu früh vergessen, ein zu großes lässt den Anbieter die Anfrage ablehnen (devx kürzt dann den Verlauf und versucht es erneut).

```json
{ "contextLimits": { "my-model": 65536 } }
```

---

## API-Formate (OpenAI und Anthropic)

Es gibt zwei Übertragungsformate: **OpenAI** (`/v1/chat/completions`) und **Anthropic** (`/v1/messages`). Das native Anthropic-Format trägt signierte Reasoning-Blöcke, Prompt-Caching und genaue Cache-Abrechnung, daher laufen Claude-Modelle darüber am besten. devx spricht beide und wählt das Format **pro Modell selbst, mit einem Schlüssel**:

- `api.anthropic.com` und Base-URLs mit dem Pfad `/anthropic/` (MiniMax, Z.ai und ähnliche) verwenden das Anthropic-Format.
- Bei jedem anderen eigenen Gateway fragt devx die Modellliste zweimal ab (einige kleine `GET {root}/v1/models`-Anfragen an deine eigene Base-URL mit deinem eigenen Schlüssel): einmal als OpenAI-Client und einmal als Anthropic-Client. Modelle, die nur die zweite Liste hat (typisch Claude), gehen über das Anthropic-Format, alles andere über das OpenAI-Format. `/model` zeigt beide Listen in einer Auswahl und markiert die Anthropic-Modelle. Setze die Base-URL einmal (`https://gateway.example.com/v1` oder ohne `/v1`); pro Modell ist nichts zu konfigurieren.
- Die Antwort wird einen Tag in `~/.devx/formats.json` zwischengespeichert (nur Modell-IDs, nie der Schlüssel). `"probeEndpoints": false` schaltet die Abfrage ab, `"apiFormat": "openai"` oder `"anthropic"` in `~/.devxrc.json` erzwingt ein Format für alles.
- Reasoning-Stufen (`/effort`) werden so geschrieben, wie die Anthropic-API sie erwartet (`thinking.budget_tokens`), sobald das Anthropic-Format verwendet wird – auch hinter einem Gateway. Reasoning-Blöcke werden aufbewahrt und mit der nächsten Anfrage zurückgesendet, damit das Modell zwischen Werkzeugaufrufen den Faden nicht verliert. Lehnt ein Gateway sie, die Cache-Marker, ein zu großes `max_tokens` oder die Reasoning-Einstellung ab, wiederholt devx die Anfrage einmal ohne den betroffenen Teil und sagt es dir.

## Kosten

Jede Anfrage sendet die ganze Unterhaltung erneut, und die Zahl neben der Kontextanzeige summiert, was jede dieser Anfragen gekostet hat (zwischengespeicherte Eingabe ist günstiger und wird so berechnet). Die Kontextanzeige selbst zeigt 0 für einen leeren Chat und ab der ersten Nachricht alles, was die nächste Anfrage trägt: Systemprompt und Werkzeugschemata eingeschlossen.

Die Zahl neben der Kontextanzeige sind die in dieser Sitzung ausgegebenen Kosten. Sie zeigt, wie sicher devx ist:

| Angezeigt | Bedeutung |
| :--- | :--- |
| `$0.0123` | exakt: vom Anbieter gemeldet oder nach der Preisliste des Herstellers berechnet |
| `~$0.0123` | eine Schätzung: berechnet mit dem [models.dev](https://models.dev)-Preis desselben Modells (ein Gateway kann anders abrechnen) |
| `$0.0123+` | mindestens so viel: für manche Anfragen ist kein Preis bekannt, sie sind nicht eingerechnet |
| `n/a` | für das Modell ist kein Preis bekannt |

Preise werden in dieser Reihenfolge nachgeschlagen, die erste Quelle, die das Modell kennt, gewinnt: die Kosten, die der Anbieter in seine Antwort schrieb (OpenRouter `usage.cost`, LiteLLM) – deine eigenen `prices` in `~/.devxrc.json` – die eigene Preisliste des Endpunkts (OpenRouter, LiteLLM) – der models.dev-Katalog (etwa 8500 Modelle; ein Snapshot wird mit devx ausgeliefert, `/effort refresh` lädt den aktuellen). Zwischengespeicherte Eingabe-Tokens, Cache-Schreibvorgänge und Long-Context-Stufen werden getrennt berechnet, wie es die Hersteller tun. Eine Anfrage, die rausging, aber keine Antwort bekam (mit Ctrl+C abgebrochen, Verbindung verloren, ein Gateway-Timeout bei einem langsamen Modell), wird vom Anbieter meist trotzdem berechnet, und ein Neuversuch sendet die ganze Unterhaltung erneut: Auch solche Anfragen werden gezählt, als Schätzung (`~`). Die kleinen Nebenaufgaben (Chat-Titel, `/commit`-Nachricht, `/compact`-Zusammenfassung) laufen mit der leichtesten Denkstufe, die das Modell bietet, und ihre Kosten sind Teil der Summe. Ein Modell, das nicht gefunden wird, erscheint nie als kostenlos: Nur `:free`-Modelle und lokale Server kosten nichts. `/usage` listet außerdem die zwischengespeicherte Eingabe auf und wie viele Anfragen nicht bepreist werden konnten.

```json
{
  "prices": {
    "my-model": { "input": 0.5, "output": 2.0, "cacheRead": 0.05 }
  }
}
```

Die Werte sind USD pro eine Million Tokens.

---

## Farbschemata

`/theme` öffnet eine Liste, die du durch Tippen filtern kannst; das markierte Schema wird unter der Liste in seinen eigenen Farben gezeigt. `/theme nord` (oder ein Alias wie `tokyo`, `mocha`, `gh`, oder eine Zahl von 1 bis 18) wechselt direkt, und die Wahl wird gespeichert.

Ein Schema ist eine vollständige Palette: Akzent, eine zweite Farbe für den Plan-Modus, Erfolgs-/Warn-/Fehlerfarben, eine Farbe für den YOLO-Modus, vier Graustufen für Text, Nebentext, Hinweise und Rahmen sowie der Hintergrund des Echos deiner Nachricht. Jedes Schema wird in den Tests auf lesbaren Kontrast auf dunklem Hintergrund geprüft.

| Schema | Charakter |
| :--- | :--- |
| `cyan` Cyan Cyber (Standard) | elektrisches Cyan und Obsidian |
| `purple` Synthwave Purple | Neon-Magenta, Retro-Violett |
| `matrix` Matrix Hacker | Phosphorgrün |
| `amber` Solar Amber | warmes CRT-Gold |
| `crimson` Ruby Crimson | Scharlachrot |
| `monochrome` Pure Monochrome | nur Weiß und Grau |
| `nord` Nord Frost | arktische Blautöne |
| `dracula` Dracula | Lila mit Rosa und Grün |
| `tokyonight` Tokyo Night | Indigo und Blau |
| `gruvbox` Gruvbox | Retro-Gelb und Orange |
| `catppuccin` Catppuccin Mocha | pastelliges Malve |
| `solarized` Solarized Dark | blendarmes Petrol und Oliv |
| `rosepine` Rose Pine | sanftes Rosé und Gold |
| `monokai` Monokai | Limette und Magenta |
| `github` GitHub Dark | klares Blau und Grün |
| `ocean` Deep Ocean | Tiefsee-Cyan |
| `sunset` Sunset Glow | Koralle und Violett |
| `forest` Forest Moss | moosige Grüntöne |

Farben sind auf Terminals mit Truecolor-Unterstützung exakt (`COLORTERM=truecolor`); auf 256-Farben-Terminals wird jede Farbe auf den nächsten Paletteneintrag abgebildet. Mit `DEVX_COLOR=256` oder `DEVX_COLOR=truecolor` erzwingst du eines von beiden.

---

## Hooks, Limits und Berechtigungen

- **Kostenlimit.** `/settings` → `Cost limit` (oder `"maxCostPerSession": 5` in `~/.devxrc.json`, USD). Bei 80 % warnt devx; am Limit stoppt es die Aufgabe, und vor der nächsten Nachricht fragt es, ob mehr ausgegeben werden soll (Weitermachen erhöht nur das Limit dieser Sitzung).
- **Ohne Rückfrage erlaubt.** Auf einer Freigabekarte erlaubt `Session` einen Befehl (nach Programm und Unterbefehl, etwa `npm test`) oder ein MCP-Werkzeug, bis du devx schließt; `Always` behält es in `~/.devxrc.json` (`bashAllowlist`, `allowedTools`). Gefährliche Befehle, das Lesen von Geheimnissen, Ketten (`&&`, `;`, `|`) und Umleitungen werden nie gemerkt. `/permissions` listet es auf, `/permissions clear` entfernt es.
- **Hooks.** Befehle, die um die Werkzeuge des Agenten herum laufen, gesetzt in `~/.devxrc.json`:

  ```json
  {
    "hooks": {
      "PreToolUse":  [{ "matcher": "bash", "command": "./check-command.sh" }],
      "PostToolUse": [{ "matcher": "write_file|edit_file", "command": "npx prettier --write ." }]
    }
  }
  ```

  `matcher` ist ein regulärer Ausdruck für den Werkzeugnamen (keiner oder `*`: jedes Werkzeug). Der Befehl erhält den Aufruf als JSON auf stdin (`event`, `tool_name`, `tool_input` und bei `PostToolUse` `tool_result`) sowie `DEVX_HOOK_EVENT` / `DEVX_TOOL_NAME` in der Umgebung. Exit-Code `2` eines `PreToolUse`-Hooks stoppt das Werkzeug und sagt dem Modell, was der Befehl auf stderr schrieb; Exit-Code `2` eines `PostToolUse`-Hooks hängt diesen Text an das Ergebnis an. Hooks eines Projekts (`.devx.json`) zählen nur in einem vertrauenswürdigen Arbeitsbereich; ein Hook hat 10 Sekunden.
- **Geheimnisse in der Ausgabe.** Schlüssel und Tokens in der Ausgabe eines Werkzeugs (`sk-…`, `ghp_…`, `Bearer …`, `"apiKey": "…"`, private Schlüssel) werden durch `[hidden secret]` ersetzt, bevor das Modell, der Chat und die gespeicherte Sitzung sie sehen.
- **Rückgängig für alles.** Mit git setzt `/undo` den ganzen Ordner auf den Stand vor dem letzten Durchgang zurück, einschließlich dessen, was Shell-Befehle getan haben (Dateien in `.gitignore` werden nicht angefasst).
- **Der freigegebene Plan** wird in `.devx/plan.md` gespeichert und dem Agenten 24 Stunden lang gegeben. **Das Projektgedächtnis** wird, wenn es länger als ~4000 Zeichen ist, für das Modell auf die neuesten Fakten gekürzt.
- **MCP.** `/mcp add <name> <command> [args]` oder `/mcp add <name> <https-url>` (ein entfernter Server über HTTP; ein Token gehört in `headers` als `"Bearer ${TOKEN}"`), `/mcp remove <name>`, `/mcp import` (aus Claude Code).
- **MCP-Werkzeuge bei Bedarf.** Jedes MCP-Werkzeugschema wird mit jeder Anfrage gesendet, und ein großer Server (Notion hat ~80 Werkzeuge) kann 100k Tokens kosten, bevor du etwas tippst. Deshalb sendet devx die Werkzeuge kleiner Server sofort und hält die großen zurück: Der Agent sieht die Liste der Server in seinem Werkzeug `mcp_search`, findet und lädt nur die Werkzeuge, die eine Aufgabe braucht (`mcp_search` mit Stichwörtern, Werkzeugnamen oder einfach einem Servernamen), und ruft sie ab seinem nächsten Schritt auf. Geladene Werkzeuge bleiben für den Chat (ein fortgesetzter Chat bekommt die zurück, die er benutzt hat). Welche Server warten, entscheidet die Größe (alle zusammen gesendeten Werkzeuge bleiben unter ~4k Tokens); pro Server erzwingst du es mit `"lazy": true` oder `"lazy": false` in `mcp.json`.

## Sicherheitsmodell

`devx` führt im Auftrag eines KI-Modells Befehle aus und ändert Dateien auf deinem Rechner, daher ist es standardmäßig vorsichtig:

- **Vertrauen in den Arbeitsordner.** Beim ersten Öffnen eines Ordners wirst du gefragt, ob du ihm vertraust (Standard: nein). In einem nicht vertrauten Ordner werden `provider`, `apiKey`, `baseUrl`, `autoApprove` und `bashAllowlist` aus der `.devx.json` des Projekts **ignoriert**. Ein geklontes Repository kann deinen API-Schlüssel nicht auf seinen eigenen Server umleiten oder YOLO einschalten.
- **Projekt-MCP-Server brauchen Freigabe.** MCP-Server, die ein Projekt deklariert (`.mcp.json`, `.devx/mcp.json`, `.claude/mcp.json`, `.devx.json`), sind Programme, die auf deinem Rechner laufen würden. Du prüfst die genauen Befehle einmal; ändern sie sich, wirst du erneut gefragt. Im Headless-Modus (`-p`) werden nicht freigegebene Projektserver übersprungen. Server aus `~/.devxrc.json` und `~/.devx/mcp.json` starten immer.
- **Bestätigungen.** Im sicheren Modus verlangen `bash`, `install_package`, MCP-Werkzeuge, der Vorschau-Server und jedes Lesen oder Schreiben **außerhalb des Projektordners** eine Bestätigung.
- **YOLO (`--yolo`) ist keine Sandbox.** Es überspringt Routinebestätigungen, fragt aber weiterhin vor gefährlichen Shell-Befehlen (rekursives Löschen außerhalb des Projekts, `find -delete` auf breiten Pfaden, `curl … | sh`, `sudo`, `git push --force`, …) und vor dem Zugriff auf Zugangsdaten (`~/.ssh`, `~/.aws`, `~/.devxrc.json`, `.env`, private Schlüssel). Die Shell-Prüfung analysiert Befehle Token für Token, aber ein absichtlich verschleierter Befehl kann trotzdem durchrutschen – nutze YOLO daher nur in Ordnern und Umgebungen, deren Verlust du verkraften kannst. Im Headless-Modus lassen sich diese Bestätigungen nicht anzeigen, die Aktion wird dann abgelehnt.
- **Extra (`--extra`, oder `/settings` bei eingeschaltetem YOLO) fragt gar nichts mehr.** Weder gefährliche Befehle noch Zugangsdaten-Dateien noch einen neuen MCP-Server: Der Agent darf jeden Befehl ausführen. Beim Einschalten erscheint eine Warnung, der Prompt-Rahmen zeigt `extra`, und wie YOLO ist es beim nächsten Start wieder aus. Nur in einer Umgebung verwenden, die du verlieren darfst.
- **`fetch_url`** lehnt localhost, private Netze und Cloud-Metadaten-Adressen ab (auch nach Weiterleitungen). Mit `DEVX_ALLOW_PRIVATE_NETWORK=1` erlaubst du lokale Dokumentationsserver.
- **`/serve`** lauscht in deinem lokalen Netz (so funktioniert der QR-Code fürs Handy). Er liefert nie Dotfiles, Schlüssel, `node_modules` oder Symlinks aus, die das Projekt verlassen, sendet keine großzügigen CORS-Header und weist Anfragen an öffentliche Hostnamen ab.
- **Geheimnisse auf der Platte.** `~/.devxrc.json`, `~/.devx/` und gespeicherte Sitzungen werden nur für dich lesbar angelegt (`0600` / `0700`). Sitzungsdateien enthalten die ganze Unterhaltung, auch Dateiinhalte, die der Agent gelesen hat.
- **Stille Verbindungen.** Eine Anfrage, die weder Antwort noch neue Daten bekommt, wird nach 300 s aufgegeben und neu gesendet (ein Handy kann die Verbindung verlieren, ohne dass die App es merkt). `"requestTimeoutSeconds": 600` in `~/.devxrc.json` ändert die Zeit, `0` schaltet es aus. Ein Modell, das minutenlang nachdenkt, ohne ein Byte zu senden, braucht einen größeren Wert.
- **Bilder in gespeicherten Chats** liegen in Dateien neben dem Chat (`~/.devx/sessions/<id>.images/`), nicht in seinem JSON, damit das Speichern schnell bleibt und `/resume` zügig öffnet. Sie werden mit dem Chat gelöscht.
- **Limits.** `bash` beendet Befehle standardmäßig nach 120 s (das Modell kann `timeout_seconds` übergeben, maximal 1800) und schließt stdin. Lange Ausgaben behalten Anfang und Ende. `read_file` liefert höchstens ~60 000 Zeichen pro Aufruf und unterstützt `startLine` / `endLine`.

---

## Das Projekt unterstützen

Wenn dir **devx** nützt, gib dem Projekt gern einen Stern auf [GitHub](https://github.com/apvcode/Termux-Dev) – das hilft dem Projekt zu wachsen und motiviert zur Weiterentwicklung!

---

## License

Dieses Projekt steht unter der [MIT-Lizenz](LICENSE).

---

<div align="center">

Entwickelt von [ApvCode](https://github.com/ApvCode) für Entwickler, Vibe-Coder und Termux-Hacker.

</div>
