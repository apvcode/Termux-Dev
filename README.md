# devx for Android

The Android app of [devx](https://github.com/apvcode/Termux-Dev). Two things in one:

- **A pocket assistant.** Chat with any model you choose, talk to it by voice, let it set alarms, find places, look at your photos. No account, no server of ours.
- **A remote for devx on your computer.** See and drive the same sessions you have in the terminal: live replies, approvals, questions, plans, files.

Black, calm interface. Your keys and chats stay on the phone.

## Download

The APK is attached to every release of Termux-Dev, next to devx itself:

**[github.com/apvcode/Termux-Dev/releases/latest](https://github.com/apvcode/Termux-Dev/releases/latest)** → `devx-android-x.y.z.apk`

Open it on the phone and allow installing from this source. Android 8.0 (API 26) or newer. Updates install over the old version: every release is signed with the same key.

## Start

1. Open the app, tell it your name.
2. **Settings → Providers → Add a provider.** Pick a preset or enter an address and an API key (OpenAI-compatible or Anthropic format; the format is found out by itself). Load the models and pick one.
3. Write, or tap the microphone.

The API key is kept encrypted on the phone (Android Keystore) and goes only to the provider you added.

## What it does

**Chat**
- Several providers and models, reasoning effort per model, branches when you edit a message, temporary chats, chats locked with the screen lock or a code, projects with their own instructions and knowledge.
- Photos, video, files and camera in a message; PDF and text viewer; export to Markdown or PDF.
- Memory: the assistant keeps useful notes and a summary about you, on the phone only. You can read, edit and delete them.
- Skills (written guides the assistant reads when a task matches): built in, imported (`.skill` or `SKILL.md`) or written by you.
- MCP servers over https (GitHub, documentation and others) with approvals and a read-only switch.
- Web search (it reads search pages itself; a free Tavily or Brave key in Settings makes it noticeably more accurate), files and archives, a terminal and Python inside the app, in a folder of its own. Each can be switched off.
- Forms the assistant draws for you: labelled questions with hints, choices (one or several), sliders and switches; what you fill in is kept, and a sent form stays sent.
- Chat names are written while the answer is, with a small animation; the chat you are in is marked in the list.
- Rich answers: cards, tables, charts, maps, weather, timers, checklists, Mermaid diagrams, app cards from Google Play and F-Droid. Rotating greetings on the home screen.
- A full-screen editor with Markdown preview for long messages. Select several chats to delete or archive.
- Paste pictures and files straight into the message field (from the clipboard or the keyboard). "Ask devx" on selected text does not touch your clipboard.
- Backup and restore of chats, projects, memory, skills and settings in one file.
- Widgets and a quick-settings tile.

**Voice assistant**
- Choose Devx as the phone's assistant: the power button or a swipe from a corner opens a see-through voice window over any app.
- Alarms, timers, reminders; opening apps and system settings; contacts, calls, messages and calendar (you press the last button); flashlight, music, volume; brightness and Do Not Disturb.
- Places, routes and weather from OpenStreetMap and Open-Meteo, no keys. 2GIS and Google Maps can be added with your own key.
- Looks at your gallery, reads on-screen notifications, works in one folder you pick, but only when you ask and only if you allowed it.
- Scheduled tasks: "every morning send me the news about AI". They run by themselves and arrive as a chat and a notification.
- Speech speed, voice and a separate fast model for talking are in **Settings → Voice assistant**.
- Tap the pill to write instead of speaking; hold it and swipe up to open the app with the same chat. The microphone has its own soft sounds instead of the speech service's beep.

**Devx Remote**
- Control devx running on your computer: every chat, live replies and reasoning, approval cards, questions, the plan (with *Go and compact context*), plan/agent/YOLO modes, model and provider, context and cost, `/compact` and other commands. Files and photos go both ways.
- Works with the terminal closed. The phone reaches the computer directly (same Wi-Fi, a VPN such as Tailscale, a forwarded port) or through a free public relay. Everything is end-to-end encrypted.

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

To attach the built APK to a release of Termux-Dev (the release must exist; it asks first):

```
node scripts/attach-android.mjs v2.0.0      # run in the devx folder
```

## Stack

Kotlin, Jetpack Compose, OkHttp, Android Keystore. The icons are [Tabler Icons](https://tabler.io/icons) (MIT); Mermaid is bundled for diagrams (MIT). Notices are in [NOTICE](NOTICE).

## License

The app has no license file of its own yet; devx itself is MIT. Third-party notices (Tabler Icons, Mermaid) are in [NOTICE](NOTICE), the fonts' licenses in [licenses/](licenses).
