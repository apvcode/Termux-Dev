<div align="center">

<img src="assets/banner.svg" alt="DEVX" width="800" />

<br/><br/>

**Ultra-fast, autonomous AI pair-programmer & vibe-coding terminal agent.**  
Built specifically for **Android Termux**, **Windows**, **macOS**, and **Linux**.

[![License: MIT](https://img.shields.io/badge/License-MIT-blue.svg)](LICENSE)
[![CI](https://github.com/apvcode/Termux-Dev/actions/workflows/ci.yml/badge.svg)](https://github.com/apvcode/Termux-Dev/actions/workflows/ci.yml)
[![Node.js](https://img.shields.io/badge/Node.js-%3E%3D20.0.0-brightgreen.svg)](https://nodejs.org)
[![TypeScript](https://img.shields.io/badge/TypeScript-5.4-blue.svg)](https://www.typescriptlang.org)
[![Platform](https://img.shields.io/badge/Platform-Termux%20%7C%20Windows%20%7C%20macOS%20%7C%20Linux-orange.svg)](#)

<br/>

[🇬🇧 **English**](#-highlights) &nbsp;•&nbsp; [🇷🇺 **Русский**](#-русский)

<br/><br/>

<img src="assets/preview.png" alt="devx Terminal Interface" width="750" style="border-radius: 8px;" />

</div>

---

## 🌟 Highlights

- **🧠 Dual-Brain Architecture (PLAN & AGENT Modes):**  
  Instantly toggle between **PLAN** (safe architect, interactive requirements questionnaire, no code dumps) and **AGENT** (autonomous file edits, terminal commands, auto-installer).
- **🚀 One-Click Plan Approval (`[🚀 Go / ✏️ Other]`):**  
  When an architectural plan is finalized, review and confirm with `Go` to immediately unleash the coder agent.
- **🖼️ Multimodal Vision Attachments:**  
  Paste screenshots directly from clipboard (`/image` or `Ctrl+P`) with smart duplicate badges `[1.png 203kb]`, `[1(copy).png 421kb]`.
- **🌐 Built-in Live Web Server (`/serve`):**  
  Instantly preview web apps and HTML5 games on port 3000. Seamlessly opens on Android via `termux-open-url`.
- **⏪ Instant Snapshot Rollback (`/undo`):**  
  Every change made by the AI is recorded. Roll back unwanted edits cleanly with a single command.
- **🩺 Self-Healing Diagnostics & Auto-Installer:**  
  Built-in support for TypeScript (`tsc`), JavaScript (`node --check`), Python (`py_compile`), and Rust (`cargo check`). The AI automatically finds and fixes errors before finishing turns.
- **🧠 Project Memory Bank (`.devx/memory.md`):**  
  Persistent project context across sessions with `/memory add <rule>`.
- **🎨 Pure Black Obsidian Theme (`#0a0a0c`):**  
  Deep OLED black background designed for distraction-free CLI vibe-coding.
- **📜 Smart Session History (`/resume` & `/session`):**  
  Resume sessions with last 20 messages neatly rendered in full Markdown and track unique session IDs (`#a8f9z`).
- **⚡ Universal AI Provider Support:**  
  **OpenRouter**, **Google Gemini**, **DeepSeek**, **Groq**, **Mistral**, **OpenAI**, **Anthropic**, **Alibaba**, and local models (**Ollama**, **LM Studio**).

---

## 📦 Installation & Setup

### Prerequisites
- **Node.js**: `v20.0.0` or higher
- **npm**, **pnpm**, or **yarn**

### Quick Install (Windows / macOS / Linux)

```bash
# Clone the repository
git clone https://github.com/apvcode/Termux-Dev.git
cd Termux-Dev

# Install dependencies (auto-builds project) and link
npm install
chmod +x bin/*
npm link
```

### 📱 Android (Termux) Setup

```bash
# Update packages & install NodeJS + Git
pkg update && pkg install -y nodejs-lts git

# Clone, install, and link
git clone https://github.com/apvcode/Termux-Dev.git
cd Termux-Dev
npm install
chmod +x bin/*
npm link

# Launch devx anywhere!
devx
```

### ⚡ One-Liner Global Install (via Git)

```bash
npm install -g git+https://github.com/apvcode/Termux-Dev.git
```

---

## 🚀 Getting Started

Launch `devx` in any project folder:

```bash
devx
```

* On first run, `devx` launches an interactive setup wizard to configure your preferred AI provider and API key.
* Start directly in **PLAN mode**:
  ```bash
  devx --plan
  ```

---

## ⌨️ Slash Commands

Type `/` in the prompt to open the autocomplete command palette:

| Command | Description |
| :--- | :--- |
| **`/plan`** | Switch to **PLAN** mode (architect & requirements planner) |
| **`/agent`** | Switch to **AGENT** mode (coder & autonomous executor) |
| **`/image`** | Paste image from clipboard as `[1.png 203kb]` |
| **`/serve [port]`** | Start local HTTP server for web/game preview (`/serve stop` to halt) |
| **`/memory`** | View, add (`/memory add <fact>`), or clear project memory bank |
| **`/undo`** | Revert all file changes from the last AI turn |
| **`/diff`** | View colored git diff of modified project files |
| **`/commit`** | AI-generated semantic commit message & git commit |
| **`/status`** | View git repository status |
| **`/session`** | View active session ID, stats, and metadata |
| **`/resume`** | Browse and resume saved chat sessions |
| **`/session del`** | Selectively or bulk delete saved sessions |
| **`/settings`** | Configure YOLO auto-approve, project memory, theme, and update checks |
| **`/update`** | Check and install latest updates from GitHub |
| **`/model`** | Switch model for the active provider with live search |
| **`/provider`** | Switch provider (OpenRouter, Gemini, Groq, DeepSeek, Local) |
| **`/compact`** | Compact and summarize chat context tokens |
| **`/clear`** | Clear screen and redraw banner |
| **`/init`** | Generate `AGENTS.md` developer guide in project root |
| **`/help`** | Display all available commands |
| **`/exit`** | Exit devx cleanly |

---

## ⌨️ Keybindings

- **`Tab`** — Instantly toggle between **PLAN** and **AGENT** modes without losing your draft text.
- **`Ctrl+P`** / **`Ctrl+V`** — Paste image from clipboard directly into prompt.
- **`@filename`** — Fast fuzzy file autocomplete and full file embedding.
- **`Backspace`** — Atomically deletes entire badges `[Pasted text #1]` and `[1.png 203kb]` in one press.
- **`Ctrl+C`** — Stop current generation immediately or exit.

---

## ⭐️ Support the Project

If you find **devx** useful, please consider giving it a ⭐️ star on [GitHub](https://github.com/apvcode/Termux-Dev) — it helps the project grow and motivates further development!

<br/>
<hr/>
<br/>

# 🇷🇺 Русский

## 🌟 Главные возможности

- **🧠 Двухрежимная архитектура (PLAN и AGENT):**  
  Мгновенное переключение между **PLAN** (безопасный архитектор, интерактивные опросники, запрет на несанкционированные правки) и **AGENT** (автономное создание/редактирование файлов, выполнение bash-команд, авто-установка пакетов).
- **🚀 Утверждение плана в 1 клик (`[🚀 Go / ✏️ Other]`):**  
  Когда архитектурный план готов, подтвердите его кнопкой `Go` — агент мгновенно переключится в режим кодера и приступит к реализации.
- **🖼️ Мультимодальное зрение и вставка скриншотов:**  
  Вставляйте изображения прямо из буфера обмена (`/image` или `Ctrl+P`) с авто-бейджами `[1.png 203kb]`, `[1(copy).png 421kb]`.
- **🌐 Встроенный локальный веб-сервер (`/serve`):**  
  Мгновенный предпросмотр веб-приложений и HTML5-игр на порту 3000. На Android автоматически открывается через `termux-open-url`.
- **⏪ Мгновенный откат изменений (`/undo`):**  
  Каждое действие AI логируется в снимки состояния. Любые нежелательные правки отменяются одной командой.
- **🩺 Самодиагностика и авто-исправление ошибок:**  
  Встроенная проверка кода для TypeScript (`tsc`), JavaScript (`node --check`), Python (`py_compile`) и Rust (`cargo check`). AI сам находит и исправляет синтаксические ошибки перед завершением ответа.
- **🧠 Банк памяти проекта (`.devx/memory.md`):**  
  Долгосрочная память правил и архитектурных решений между сессиями (`/memory add <правило>`).
- **🎨 Чисто чёрная тема Obsidian Black (`#0a0a0c`):**  
  Глубокий OLED-чёрный фон в стиле OpenCode для комфортной ночной разработки.
- **📜 История сессий с уникальными ID (`/resume` и `/session`):**  
  Восстановление сессий с предпросмотром последних 20 сообщений в Markdown и короткими хэш-ID (`#a8f9z`).
- **⚡ Поддержка любых провайдеров:**  
  **OpenRouter**, **Google Gemini**, **DeepSeek**, **Groq**, **Mistral**, **OpenAI**, **Anthropic**, **Alibaba**, а также локальные **Ollama** и **LM Studio**.

---

## 📦 Установка и запуск

### Требования
- **Node.js**: `v20.0.0` или выше
- **npm**, **pnpm** или **yarn**

### Быстрая установка на ПК (Windows / macOS / Linux)

```bash
# Клонируем репозиторий
git clone https://github.com/apvcode/Termux-Dev.git
cd Termux-Dev

# Устанавливаем зависимости (автоматически собирает проект) и линкуем
npm install
chmod +x bin/*
npm link
```

### 📱 Установка на Android (Termux)

```bash
# Обновляем пакеты и ставим NodeJS + Git
pkg update && pkg install -y nodejs-lts git

# Клонируем, устанавливаем и линкуем
git clone https://github.com/apvcode/Termux-Dev.git
cd Termux-Dev
npm install
chmod +x bin/*
npm link

# Запускаем devx в любой папке!
devx
```

### ⚡ Установка в одну команду (через Git)

```bash
npm install -g git+https://github.com/apvcode/Termux-Dev.git
```

---

## 🚀 Начало работы

Запустите команду в папке любого вашего проекта:

```bash
devx
```

* При первом запуске откроется мастер настройки: выберите любимого AI-провайдера и введите API-ключ.
* Запуск сразу в режиме архитектора:
  ```bash
  devx --plan
  ```

---

## ⌨️ Слэш-команды

Напишите `/` в строке ввода, чтобы открыть интерактивное меню команд:

| Команда | Описание |
| :--- | :--- |
| **`/plan`** | Переключиться в режим архитектора (**PLAN**) |
| **`/agent`** | Переключиться в режим исполнителя (**AGENT**) |
| **`/image`** | Вставить картинку из буфера обмена как `[1.png 203kb]` |
| **`/serve [порт]`** | Запустить локальный веб-сервер (`/serve stop` для остановки) |
| **`/memory`** | Посмотреть, добавить (`/memory add <факт>`) или очистить банк памяти |
| **`/undo`** | Откатить все изменения файлов за последний шаг AI |
| **`/diff`** | Показать цветной git diff изменённых файлов |
| **`/commit`** | Сгенерировать сообщение коммита с помощью AI и закоммитить в git |
| **`/status`** | Показать текущий статус Git репозитория |
| **`/session`** | Показать ID, статистику и файл активной сессии |
| **`/resume`** | Выбрать и восстановить сохранённую сессию с историей |
| **`/session del`** | Выборочно или полностью удалить сессии |
| **`/settings`** | Настройки авто-подтверждения, памяти, темы и проверки обновлений |
| **`/update`** | Проверить и установить последние обновления с GitHub |
| **`/model`** | Сменить модель текущего провайдера с живым поиском |
| **`/provider`** | Сменить AI-провайдера (OpenRouter, Gemini, Groq, DeepSeek, Local) |
| **`/compact`** | Сжать контекст диалога для экономии токенов |
| **`/clear`** | Очистить экран терминала и перерисовать баннер |
| **`/init`** | Создать файл инструкций `AGENTS.md` в корне проекта |
| **`/help`** | Показать список всех доступных команд |
| **`/exit`** | Завершить работу devx |

---

## ⌨️ Горячие клавиши

- **`Tab`** — Мгновенное переключение между режимами **PLAN** и **AGENT** без потери набранного текста.
- **`Ctrl+P`** / **`Ctrl+V`** — Вставка скриншота/картинки из буфера обмена.
- **`@файл`** — Умное автодополнение файлов и прикрепление их содержимого к запросу.
- **`Backspace`** — Атомарное удаление бейджей `[Pasted text #1]` и `[1.png 203kb]` за одно нажатие.
- **`Ctrl+C`** — Мгновенная остановка генерации ответа или выход.

---

## ⭐️ Поддержите проект

Если вам понравился **devx**, пожалуйста, поставьте ⭐️ звёздочку на [GitHub](https://github.com/apvcode/Termux-Dev) — это помогает проекту расти и мотивирует добавлять новые крутые возможности!

---

## 📄 License

This project is licensed under the [MIT License](LICENSE).

---

<div align="center">

Made with ❤️ by [ApvCode](https://github.com/ApvCode) for developers, vibe-coders, and Termux hackers.

</div>
