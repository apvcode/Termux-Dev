<div align="center">

# devx for Android

**A pocket AI assistant, and a remote for devx on your computer.**  
No account, no server of ours. Your keys and chats stay on the phone.

<br/>

[![Android](https://img.shields.io/badge/Android-8.0%2B-3ddc84.svg?style=flat-square&labelColor=0a0c11)](https://github.com/apvcode/Termux-Dev/releases/tag/android-app)
[![Download APK](https://img.shields.io/badge/download-APK-00c2d1.svg?style=flat-square&labelColor=0a0c11)](https://github.com/apvcode/Termux-Dev/releases/tag/android-app)
[![Languages](https://img.shields.io/badge/languages-9-4f8cff.svg?style=flat-square&labelColor=0a0c11)](#)
[![devx: MIT](https://img.shields.io/badge/devx-MIT-4f8cff.svg?style=flat-square&labelColor=0a0c11)](https://github.com/apvcode/Termux-Dev/blob/main/LICENSE)

<br/>

**English** &nbsp;•&nbsp; [Русский](README_ru.md) &nbsp;•&nbsp; [Українська](README_uk.md) &nbsp;•&nbsp; [Deutsch](README_de.md) &nbsp;•&nbsp; [Español](README_es.md) &nbsp;•&nbsp; [Français](README_fr.md) &nbsp;•&nbsp; [Português](README_pt.md) &nbsp;•&nbsp; [中文](README_zh.md) &nbsp;•&nbsp; [日本語](README_ja.md)

</div>

---

## At a glance

<table>
<tr>
<td valign="top" width="25%"><b>Pocket assistant</b><br/>Any model you choose, voice, photos and files, memory, skills, MCP, web search.</td>
<td valign="top" width="25%"><b>Notebooks</b><br/>Learn or collect ideas: sources, a study plan, flashcards, an audio overview, answers that cite.</td>
<td valign="top" width="25%"><b>Voice and the phone</b><br/>Alarms, calls, messages, places and weather, scheduled tasks, widgets.</td>
<td valign="top" width="25%"><b>Remote for devx</b><br/>The same sessions as in the terminal: replies, approvals, plans, files.</td>
</tr>
</table>

## Download

The app has its own page on Termux-Dev, apart from devx's releases (the app and devx have separate versions). Every version is a file there with its checksum; new versions are added next to the old ones, and a file is never replaced:

**[Releases → devx for Android](https://github.com/apvcode/Termux-Dev/releases/tag/android-app)** → the newest file, `devx-android-X.Y.Z.apk`

Open it on the phone and allow installing from this source. Android 8.0 (API 26) or newer. Updates install over the old version: every release is signed with the same key, and a published version is never replaced.

## Start

1. Open the app, tell it your name.
2. **Settings → Providers → Add a provider.** Pick a preset or enter an address and an API key (OpenAI-compatible or Anthropic format; the format is found out by itself). Load the models and pick one.
3. Write, or tap the microphone.

The API key is kept encrypted on the phone (Android Keystore) and goes only to the provider you added.

## What it does

### Chat

- Several providers and models, reasoning effort per model, branches when you edit a message, temporary chats, chats locked with the screen lock or a code, projects (ongoing work with their own instructions and knowledge) and notebooks (below).
- Photos, video, files and camera in a message; PDF and text viewer; export to Markdown or PDF.
- Memory: the assistant keeps useful notes and a summary about you, on the phone only. You can read, edit and delete them. The summary renews itself in the background and shows what is new in it; a note that stops being true soon (an exam on Friday) can expire; when there are many notes, those that touch your question are sent first.
- Skills (written guides the assistant reads when a task matches): built in, imported (`.skill` or `SKILL.md`) or written by you.
- MCP servers over https (GitHub, documentation and others) with approvals and a read-only switch.
- Web search (it reads search pages itself; a free Tavily or Brave key in Settings makes it noticeably more accurate), files and archives, a terminal and Python inside the app, in a folder of its own. Each can be switched off.
- Forms the assistant draws for you: labelled questions with hints, choices (one or several), sliders and switches; what you fill in is kept, and a sent form stays sent.
- Chat names are written while the answer is, with a small animation; the chat you are in is marked in the list.
- Rich answers: cards, tables, charts, maps, weather, timers, checklists, Mermaid diagrams, app cards from Google Play and F-Droid. Rotating greetings on the home screen.
- A full-screen editor with Markdown preview for long messages. Select several chats to delete or archive.
- Type `@` in the field: a menu of what you can point at (a notebook, a project, a file of the library, a scheduled task). A notebook or a project becomes part of the sentence, in its colour, and the assistant is given what it holds. By voice just say its name.
- One search for chats, notebooks, projects, files and sources, also by what is written inside documents, PDFs and notes.
- Paste pictures and files straight into the message field (from the clipboard or the keyboard). "Ask devx" on selected text does not touch your clipboard.
- Backup and restore of everything in one file: chats, notebooks and projects, memory, skills, character, attachments and settings. API keys and tokens go in only if you set a password (they are encrypted with it). Before restoring, the app shows what is in the file and what it will replace. A weekly copy is made by itself into a folder you choose and, if you like, also uploaded to a WebDAV folder (Nextcloud, a NAS); the last four are kept.
- Pin and archive chats, lock a single chat, add a chat to the home screen. "Ask devx" on selected text quotes the passage as a card in your next message.
- The assistant works with files in a folder of its own: it creates and reads files, makes and unpacks archives, reads a PDF page by page and looks at its pictures, edits pictures, and unpacks, changes, repacks and signs APKs. Approvals can be given from the notification itself (Allow / Deny).
- Four widgets (an ask bar, quick buttons, the latest chats, the cards that are due) and two quick-settings tiles (ask, voice).

### Notebooks and the library

- A notebook is a place to learn or collect ideas on one topic. *Ideas* notebooks keep sources and chats on a topic; *study* notebooks add a goal, a plan of lessons and your progress. Projects are separate: they are for ongoing work.
- Sources: files, photos (also from the camera) and web pages. Each gets a short description from the assistant; ask it to find sources on the web and it adds the best pages.
- Answers about a notebook cite their sources as small chips (`notes.pdf · 12`); a tap opens the source, a PDF at that page.
- A study notebook teaches step by step, checks with forms, writes the plan itself and keeps cards: questions come back for review when it is time (the better you know one, the later it returns). The cards have a screen of their own: the question, a tap turns the card, four buttons (again, hard, good, easy) that show when it will come back, and a summary at the end; it works without the model and costs nothing. A daily reminder is a notification with the number of cards that are due.
- The assistant can change a notebook or a project (name, icon, colour, description, instructions, goal), and after talking things through it can propose a new notebook or project: you confirm on a card.
- Share a link, a text, a PDF or a picture from any app: devx offers a new chat or one of your notebooks, where it becomes a source (a link is read and kept).
- Study stats on the page: days in a row, the last seven days as bars; a home-screen widget shows how many cards are due and starts the review. Cards and the notebook's overview can be heard (an *audio overview* is written to be listened to, then read aloud), and a notebook exports as one Markdown file.
- Cards can have a picture, and can be answered aloud. They come in from Anki (`.apkg` or text) and from CSV, and go out as an Anki package with pictures, Anki text or CSV. A *concept map* shows the notebook's ideas and how they connect, coloured by how well you know each, with *Explain* and *Quiz me*. A notebook can be put in the archive, or copied to start again (its sources and lessons, not its chats).
- The library holds every file you ever attached or the assistant made: search, filters, where it was used, attach to a new chat, delete everywhere. PDF, Word, slides, spreadsheets, text, images and sites open in the built-in viewer; Office originals are kept.

### Look, character and security

- Appearance has its own page: three quick choices (system, black, light) and a screen of other themes (dark gray, Midnight, Forest, Dusk, Ember, Ocean, Paper, Sakura, Mint). The theme can change by itself with the time of day (a day one and a night one), and the switch is animated. Blurred glass can be turned off: the buttons then stay solid.
- Character: pick a manner (calm, no fluff, detailed, friendly, professional, blunt, mentor), fine-tune the tone, give the assistant a name and write your own instructions. One tap resets it.
- App lock with the screen lock, a code or biometrics; single chats can be locked too, and the list of recent apps hides the picture while a lock is on.
- Photos per message, notifications about answers and approvals, and whether a computer may run in the "everything" mode are in the settings.
- Data and security have their own page: the lock, the copies and the trash. Deleted chats, projects and notebooks stay in the trash for 30 days and can be put back (right after deleting, a message offers Undo). A chat file that cannot be read is put aside, not lost, and you are told. What the app recovered from is listed under **About → Diagnostics**.

### Voice assistant

- Choose Devx as the phone's assistant: the power button or a swipe from a corner opens a see-through voice window over any app.
- Alarms, timers, reminders; opening apps and system settings; contacts, calls, messages and calendar (you press the last button); flashlight, music, volume; brightness and Do Not Disturb.
- Places, routes and weather from OpenStreetMap and Open-Meteo, no keys. 2GIS and Google Maps can be added with your own key.
- Looks at your gallery, reads on-screen notifications, works in one folder you pick, but only when you ask and only if you allowed it.
- Scheduled tasks: "every morning send me the news about AI". They run by themselves and arrive as a chat and a notification.
- Speech speed, voice and a separate fast model for talking are in **Settings → Voice assistant**.
- A natural voice from your provider (OpenAI-compatible speech or Gemini) can replace the phone's robotic one: pick the provider, the speech model (from its list, or type a name) and the voice, and hear a sample. If it fails, the reason is shown and the phone's voice reads meanwhile.
- Tap the pill to write instead of speaking; hold it and swipe up to open the app with the same chat (a hint above the pill always shows how). The microphone has its own soft sounds instead of the speech service's beep.
- "Keep listening after an answer" (hands free) and the language you speak, chosen apart from the app's.
- Scheduled tasks have a screen of their own: a time, the days, the model for each one, "run now".

### Devx Remote

- Control devx running on your computer: every chat, live replies and reasoning, approval cards, questions, the plan (with *Go and compact context*), plan/agent/YOLO modes, model and provider, context and cost, `/compact` and other commands. Files and photos go both ways.
- Works with the terminal closed. The phone reaches the computer directly (same Wi-Fi, a VPN such as Tailscale, a forwarded port) or through a free public relay. Everything is end-to-end encrypted.
- Start a new session on the computer in a folder you pick; add a computer by an address (a VPN, a forwarded port) as well as by QR code.

On the computer:

```
devx remote start     # the host, keeps running without a terminal
devx remote pair      # a QR code: scan it in the app, confirm the six digits on the computer
```

A phone is removed with `/phones` in devx or `devx remote revoke <name>`. More in the [devx README](https://github.com/apvcode/Termux-Dev#readme).

## Languages

English, Русский, Deutsch, Español, Français, 日本語, Português, Українська, 中文: the same nine as devx. **Settings → Language**. The language also sets what the microphone listens for.

## Privacy

- Chats, memory, skills and keys are on the phone. Nothing goes to us; there is no server of ours.
- Your messages go to the provider you chose, and to an MCP server or a map service only when a tool needs it.
- Gallery, notifications, location, contacts and the folder are read only when you ask, and each can be switched off.
- Calls, messages and calendar events are never sent without your last tap.

## Build

```
./gradlew assembleRelease      # app/build/outputs/apk/release/app-release.apk
```

JDK 17 and the Android SDK. Signing: put `signing.properties` (storeFile, storePassword, keyAlias, keyPassword) in `~/.config/devx-android/`, or point `DEVX_SIGNING_PROPERTIES` at it, or use `keystore.properties` in the project folder. Without any of them the build is signed with the debug key and cannot update an installed release. Keep the key safe: it is what lets updates install over the old version.

The app has its own versions. One command builds it, checks the signing key and adds `devx-android-X.Y.Z.apk` to the page `android-app` of Termux-Dev (it asks first; a file that is already there is never replaced; `--dry-run` shows what it would do):

```
node scripts/release-android.mjs      # run in the devx folder
```

## Stack

Kotlin, Jetpack Compose, OkHttp, Android Keystore. The icons are [Tabler Icons](https://tabler.io/icons) (MIT); Mermaid is bundled for diagrams (MIT). Notices are in [NOTICE](NOTICE).

## License

The app has no license file of its own yet; devx itself is MIT. Third-party notices (Tabler Icons, Mermaid) are in [NOTICE](NOTICE), the fonts' licenses in [licenses/](licenses).
