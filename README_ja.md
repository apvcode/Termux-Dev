<div align="center">

<img src="assets/banner.svg" alt="DEVX" width="880" />

<br/>

**超高速で自律的な、ターミナル向け AI ペアプログラマー兼 Vibe Coding エージェント。**  
**Android Termux**、**Windows**、**macOS**、**Linux** 専用に作られています。

<br/>

[![npm](https://img.shields.io/npm/v/termux-dev.svg?style=flat-square&labelColor=0a0c11&color=00c2d1&label=npm)](https://www.npmjs.com/package/termux-dev)
[![CI](https://img.shields.io/github/actions/workflow/status/apvcode/Termux-Dev/ci.yml?style=flat-square&labelColor=0a0c11&label=CI)](https://github.com/apvcode/Termux-Dev/actions/workflows/ci.yml)
[![License: MIT](https://img.shields.io/badge/license-MIT-4f8cff.svg?style=flat-square&labelColor=0a0c11)](LICENSE)
[![Node.js](https://img.shields.io/badge/node-%3E%3D20-3fb950.svg?style=flat-square&labelColor=0a0c11)](https://nodejs.org)
[![TypeScript](https://img.shields.io/badge/TypeScript-5.4-4f8cff.svg?style=flat-square&labelColor=0a0c11)](https://www.typescriptlang.org)
[![Platform](https://img.shields.io/badge/platform-Termux%20%7C%20Windows%20%7C%20macOS%20%7C%20Linux-d97a3a.svg?style=flat-square&labelColor=0a0c11)](#quick-start)
[![Android app](https://img.shields.io/badge/Android-app%20%28APK%29-3ddc84.svg?style=flat-square&labelColor=0a0c11)](https://github.com/apvcode/Termux-Dev/releases/tag/android-app)

<br/>

[English](README.md) &nbsp;•&nbsp; [Русский](README_ru.md) &nbsp;•&nbsp; [Українська](README_uk.md) &nbsp;•&nbsp; [Deutsch](README_de.md) &nbsp;•&nbsp; [Español](README_es.md) &nbsp;•&nbsp; [Français](README_fr.md) &nbsp;•&nbsp; [Português](README_pt.md) &nbsp;•&nbsp; [Chinese](README_zh.md) &nbsp;•&nbsp; **Japanese**

<br/><br/>

<a href="assets/demo.mp4"><img src="assets/demo.gif" alt="devx デモ：プラン、ビルド、テスト、QR コード付きプレビュー" width="880" /></a>

<br/>

<img src="assets/preview.svg" alt="devx のターミナル画面" width="880" />

</div>

---

## ひと目で

<table>
<tr><td valign="top" width="33%"><b>まず計画、それから実行</b><br/>PLAN と AGENT モード、ワンクリック承認、タスクの大きさに合わせた計画</td><td valign="top" width="33%"><b>標準で安全</b><br/>危険な操作は事前に確認、出力の秘密情報を隠す、git で取り消し、任意の費用上限</td><td valign="top" width="33%"><b>どのモデルでも</b><br/>OpenRouter、Gemini、DeepSeek、Groq、Mistral、OpenAI、Anthropic、Ollama など</td></tr><tr><td valign="top" width="33%"><b>MCP 内蔵</b><br/>パネル、HTTP のリモートサーバー、エージェント自身がサーバーを設定</td><td valign="top" width="33%"><b>ターミナルのために</b><br/>スマホ向けレイアウト、18 のテーマ、折りたためる思考と出力</td><td valign="top" width="33%"><b>費用が見える</b><br/>セッションの費用をリアルタイム表示、モデルごとの推論の強さ、必要なら上限</td></tr>
</table>

## クイックスタート

```bash
npm install -g termux-dev
devx
```

Android では、まず Termux で `pkg install nodejs-lts` を実行してください。

---

## 主な特長

ターミナルの画面は、枠付きのプロンプトを中心に構成されています。枠線にはステータス（モード `agent` / `plan` / `yolo`、モデル、フォルダ、git ブランチ、コンテキストメーター、コスト）が表示されます。そのほか、ヘッダーカード、陰影付きのメッセージ表示、アシスタント用の `●` ガター、静かな 1 行の結果を持つ `◆` ツール呼び出し行、編集用の diff ボックス、リアルタイムの作業インジケーターがあります。どのレイアウトにも縦持ちの Termux 向けの狭い画面用バリアントがあり、絵文字や CJK 文字の幅も正しく計算します。元の ASCII レイアウトには `/settings` → `Interface` で戻せます。

- **ツインブレイン構成（PLAN と AGENT モード）：**  
  **PLAN**（安全なアーキテクト、対話式の要件ヒアリング、コードの丸出しなし）と **AGENT**（ファイルの自律編集、ターミナルコマンド、自動インストール）を即座に切り替えられます。
- **ワンクリックのプラン承認（`[Go / Go and compact context / Other]`）：**
  アーキテクチャのプランが固まったら、内容を確認して `Go` で承認すると、すぐにコーダーエージェントが動き出します。`Go and compact context` は、まず長いプラン作成の会話を要約し、承認済みのプランを短い新しいコンテキストで実行します（スマートフォンからも同じように使えます）。
- **マルチモーダルな画像添付：**  
  `Ctrl+V` または `Alt+V` でクリップボードからスクリーンショットをそのまま貼り付けられます（`/image` やファイルパスの貼り付けも可）。`[Image #1 203kb]` のようなバッジで表示され、テキスト専用と分かっているモデルでなければ、本物の画像としてモデルに送られます。Wayland では `wl-clipboard`、X11 では `xclip`、macOS では `pngpaste` が必要です。
- **エージェントはディスク上の画像を見られます：**
  どのフォルダの `screenshot.png` やモックアップについて尋ねても、添付した画像を見るときと同じように `read_file` でファイルを開きます。PNG、JPEG、WebP、GIF（5 MBまで）。ファイルの中身が本当に画像である必要があります。最新の画像だけがそのまま送られ、古いものはチャット内のメモとして残ります（モデルはファイルをもう一度開けます）。画像を扱えないモデルには、代わりにわかりやすいメッセージが返ります。
- **内蔵のライブ Web サーバー（`/serve`）：**  
  Web アプリや HTML5 ゲームをポート 3000 で即座にプレビューできます。Android では `termux-open-url` でスムーズに開きます。
- **バックグラウンドタスク（`/tasks`）：**
  エージェントは開発サーバー、ウォッチャー、長いテストをバックグラウンドで起動して作業を続けられます。特定の行（`ready|error`）を待ち、出力を後で読み、タスクが終了したときや尋ねた内容が出力されたときに通知を受けます。そのときプロンプトにいれば、自分でその知らせを拾います。プロンプト枠の `◈ 2 bg` バッジで実行中のものがわかり、タスクが異常終了した場合は devx がエージェントに伝えるまで `· 1 failed` が加わります。`/tasks` は一覧、`/tasks <id>` は出力、`/tasks stop <id|all>` は停止、`/tasks clear` は消去です。タスクの開始は `bash` と同様に承認を求められ、devx を終了するとすべて停止します。`"backgroundAutoWake": false` にすると、エージェントは自分から反応しなくなります。
- **即時スナップショットによるロールバック（`/undo`）：**  
  ファイルツール（`write_file`、`edit_file`）で行った編集はすべて記録され、1 つのコマンドで元に戻せます。シェルコマンド（`bash`）による変更は記録**されません**。スナップショットは現在のセッションのメモリ内にのみ保持されます。
- **自己修復する診断と自動インストーラー：**  
  TypeScript（`tsc`）、JavaScript（`node --check`）、Python（`py_compile`）、Rust（`cargo check`）を標準でサポート。AI がターンを終える前に、エラーを自動で見つけて修正します。
- **プロジェクトメモリバンク（`.devx/memory.md`）：**  
  `/memory add <ルール>` で、セッションをまたいでプロジェクトの文脈を保持します。
- **devx があなたを待っているときの通知：**
  エージェントが承認を必要としたり質問（`◆ Clarify`）をしたりして、ターミナルが前面にないときは通知が届きます（kitty 独自のプロトコル、Linux では `notify-send`、macOS では `osascript`、Termux では短い振動つきの Android 通知）。完了したタスクも、回答の冒頭つきで知らせます。ターミナルがフォーカスの変化を報告する必要があります（最近のほとんどのターミナルは対応）。あなたが見ているかどうか devx にわからないときは、目の前に割り込まず静かにしています。
- **過去のチャット（`search_chats`）：**
  「前に決めたとおり」や「パーサーについてのあのチャットで」と言うと、エージェントは保存したチャットをキーワードで検索し（どの言語でも、語形が違っても一致します）、必要なものを数メッセージずつ読めます。読み取り専用でローカルなので、確認は求めません。開いているチャットは検索されず、ツールの出力は索引化されず、読んだ内容は指示ではなくデータとしてモデルに渡されます。通常のタスクではエージェントはこれを使いません。
- **純黒背景（`#0a0a0c`）の 18 種類のカラーテーマ：**  
  気が散らない CLI での Vibe Coding のための深い OLED ブラック。単一のアクセントではなく完全なパレットを備えます：Cyan Cyber、Synthwave Purple、Matrix Hacker、Solar Amber、Ruby Crimson、Pure Monochrome、Nord Frost、Dracula、Tokyo Night、Gruvbox、Catppuccin Mocha、Solarized Dark、Rose Pine、Monokai、GitHub Dark、Deep Ocean、Sunset Glow、Forest Moss。[テーマ](#テーマ)を参照してください。
- **スマートなセッション履歴（`/resume` と `/session`）：**  
  直近 20 件のメッセージを完全な Markdown で表示してセッションを再開し、固有のセッション ID（`#a8f9z`）を追跡できます。
- **スマートな複数行エディター（`edit_file 2.0`）：**  
  衝突に強いコード編集。重複一致のときは行番号付きでエラーを報告し、`replaceAll` にも対応します。
- **充実したテストスイート（Vitest）：**  
  エージェントループ、ファイルシステム、セキュリティガード、スナップショット、コンテキスト履歴を網羅し、CI で自動実行します。
- **9 言語のインターフェース：**  
  English（既定）、Русский、Українська、Deutsch、Español、Français、Português、中文、日本語。`/settings` → `Language` で選ぶと即座に反映され、記憶されます。メッセージ、ヒント、設定画面が翻訳され、コード、ツール名、モデル自身の回答は翻訳されません。
- **あらゆる AI プロバイダーに対応：**  
  **OpenRouter**、**Google Gemini**、**DeepSeek**、**Groq**、**Mistral**、**OpenAI**、**Anthropic**、**Alibaba**、そしてローカルモデル（**Ollama**、**LM Studio**）。
- **自分のプロバイダ：**  
  `/provider` → **プロバイダを追加**：名前、アドレス（OpenAI または Anthropic 互換の任意の API）、キーを入力します。`~/.devxrc.json` に保存され、既知のプロバイダと並んで一覧に表示されます。もう一度選ぶと、使用、名前・アドレス・キーの変更、削除ができます。自分のマシン上のサーバー（LM Studio、ローカルゲートウェイ）はキーなしで追加できます。

---

## インストールとセットアップ

### 前提条件
- **Node.js**：`v20.0.0` 以上
- **npm**、**pnpm**、または **yarn**

### Node.js と npm のインストール

devx には Node.js 20 以降が必要です（npm は同梱）。お使いのシステムを選んでください：

```bash
# Windows (PowerShell)
winget install OpenJS.NodeJS.LTS

# macOS (Homebrew)
brew install node

# Ubuntu / Debian（NodeSource：ディストリビューション標準リポジトリのバージョンは 20 より古いことが多いため）
curl -fsSL https://deb.nodesource.com/setup_lts.x | sudo -E bash -
sudo apt-get install -y nodejs

# Fedora
sudo dnf install -y nodejs

# Arch Linux
sudo pacman -S nodejs npm

# Android (Termux)
pkg update && pkg install -y nodejs-lts
```

ターミナルを開き直して確認します：`node -v`（`v20` 以上であること）と `npm -v`。どのシステムでも：[nodejs.org](https://nodejs.org/en/download) のインストーラーまたは [nvm](https://github.com/nvm-sh/nvm)。

### グローバルへの簡単インストール（推奨）

```bash
npm install -g termux-dev
```

### Android（Termux）

```bash
# 1. パッケージを更新して NodeJS をインストール
pkg update && pkg install -y nodejs-lts

# 2. npm で devx をグローバルにインストール
npm install -g termux-dev

# 3. どこでも devx を起動！
devx
```

### ソースからビルド（開発者向け）

```bash
git clone https://github.com/apvcode/Termux-Dev.git
cd Termux-Dev
npm install
npm link
```

### 完全なアンインストール

設定、保存したセッション、データも含めて `devx` を完全に削除するには：

```bash
# 1. グローバルの npm パッケージをアンインストール
npm uninstall -g termux-dev

# 2. 設定とセッション履歴を削除（Linux / macOS / Termux）
rm -rf ~/.devx ~/.devxrc.json

# （Windows PowerShell）
Remove-Item -Recurse -Force "$HOME\.devx", "$HOME\.devxrc.json" -ErrorAction SilentlyContinue
```

---

## はじめに

任意のプロジェクトフォルダで `devx` を起動します：

```bash
devx
```

* 初回起動時、`devx` は AI プロバイダーと API キーを設定する対話式のセットアップウィザードを開きます。
* 最初から **PLAN モード**で起動する：
  ```bash
  devx --plan
  ```
* **ヘッドレス単発モード**（スクリプト、CI/CD、Termux:Widget）：
  ```bash
  devx -p "review latest git diff and find security bugs"
  devx -p "fix build errors" --yolo
  ```

---

## スマートフォンからのリモート操作

**アプリの入手：** Android アプリには Termux-Dev に専用のページがあります：**[Releases → devx for Android](https://github.com/apvcode/Termux-Dev/releases/tag/android-app)**。各バージョンはそこに `devx-android-X.Y.Z.apk` というファイルとして置かれます（最新のものをスマートフォンで開き、この提供元からのインストールを許可してください）。新しいバージョンは古いものの隣に追加されます。アプリのソースコードと専用の README は [`android` ブランチ](https://github.com/apvcode/Termux-Dev/tree/android)にあります。 アプリ内では、devx の `/phones` または `devx remote revoke <name>` でスマートフォンをコンピューターから削除できます。

devx 2.0 は devx の Android アプリから操作できます。小さなホストがコンピューターのバックグラウンドで動き、スマートフォンがそれに接続して同じセッションを見て操作します。すべてのチャット、ライブの回答と推論、承認カード、質問、プラン、plan/agent/YOLO モード、モデルとプロバイダー、コンテキストとコスト、`/compact` などのコマンドです。ファイルや写真はスマートフォンから送れ、エージェントもファイルや画像を送り返せます。それらはスマートフォンのチャットにカードとして表示されます。ターミナルを閉じていてもすべて動作します。

このアプリは、アカウントも当方のサーバーも不要な、それ自体で完結するポケットアシスタントでもあります。

- **チャット**：追加した任意のプロバイダー（OpenAI 互換または Anthropic 互換）で、推論レベル、写真とファイル、プロジェクト、メモリ、スキル、MCP サーバーが使えます。
- **音声**：スマートフォンのアシスタントに設定すると、電源ボタンで開きます。アラーム、タイマー、リマインダーの設定、アプリの起動、連絡先の検索、通話やメッセージの準備、明るさ・サイレントモード・音楽・音量の操作ができます。
- **場所と天気**は OpenStreetMap から、キー不要で取得します。頼めば写真や通知も見られます。
- **スケジュールタスク**：「毎朝AIのニュースを送って」。
- **カードでの回答**：表、グラフ、地図、天気、タイマー、チェックリスト、図、Google Play と F-Droid のアプリカード。
- **ノートブック**：ひとつのテーマで学んだりアイデアを集めたりする場所で、独自のソース（ファイル、写真、ウェブページ。どのアプリからでもリンクを共有できます）を持ちます。学習ノートブックには目標とレッスン計画、フォーム形式の質問、間隔反復のカード（専用画面、ホーム画面ウィジェット、リマインダー）、学習の統計、聞くための音声の概要、タップできるチップでソースを示す回答があります。
- **メッセージ中の `@`** はノートブック、プロジェクト、ファイル、スケジュール済みタスクを指します。アシスタントはその中身を受け取ります。ひとつの検索でチャット、ノートブック、ファイル、その中の文章を横断できます。
- **ライブラリとバックアップ**：添付したファイルやアシスタントが作ったファイルをすべて保管し、PDF・Word・スライド・表計算の内蔵ビューアで開けます。すべてを1つのバックアップファイルに、または選んだフォルダへの毎週の自動コピーにできます。
- **自分らしい見た目と性格**：黒・ダークグレー・ライトのテーマ、アシスタントの話し方と名前、画面ロックや生体認証によるアプリロック、個別チャットのロック。チャットはピン留め、アーカイブ、ホーム画面への追加ができ、ウィジェット4つとクイック設定タイル2つがあります。
- **9つの言語**：devx と同じです。

```
devx remote start     # ホストを起動（ターミナルなしで動き続けます）
devx remote pair      # QRコードを表示：アプリでスキャンし、ここで6桁を確認します
devx remote status    # 誰が接続中か、どのセッションが開いているか
devx remote devices   # ペアリング済みスマートフォン   devx remote revoke <name>   # 1台を削除
devx remote service   # コンピューターと同時に起動（systemd / launchd / スタートアップ）。/settings にもあります。`service off` で無効
```

スマートフォンが当方のサーバーなしでコンピューターに届く方法：まず直接（同じ Wi-Fi、Tailscale などの VPN、転送したポート 47821）、それができない場合（モバイル回線、キャリア NAT）は無料の公開 MQTT リレーを通します。スマートフォンは両方を同時に試し、試行を続けます。リレーが見るのは暗号化されたパケットだけです。通信形式とそのセキュリティは [docs/remote-protocol.md](docs/remote-protocol.md) にあります。

セキュリティ：スマートフォンは、`devx remote pair` のワンタイムコード（5分間有効）**と**、両方の画面に同じ6桁が表示されるここでのあなたの承認があって初めて追加されます。鍵は P-256（スマートフォンの鍵は Keystore の外に出ません）で、すべての接続は新しい鍵で署名・暗号化（AES-256-GCM）されます。リモートアクセスはこのコンピューターへのシェルアクセスです。デフォルトではすべてのコマンドでスマートフォンに承認を求め、「Everything」モードはセッションごとに自分で有効にするまでオフです。

スマートフォン側で devx のメモリがオンなら、その名前がチャットに添えられ、こちらのエージェントはスマートフォンのノートを尋ねる `phone_memory` ツールを使えます。このコンピューターには何もコピーされません。

## スラッシュコマンド

プロンプトで `/` を入力すると、オートコンプリート付きのコマンドパレットが開きます：

| コマンド | 説明 |
| :--- | :--- |
| **`/plan`** | **PLAN** モードに切り替え（アーキテクト兼要件プランナー） |
| **`/agent`** | **AGENT** モードに切り替え（コーダー兼自律実行者） |
| **`/mcp`** | Model Context Protocol（MCP）サーバーと外部ツールを管理（`/mcp reload`） |
| **`/usage`** | パネル（`Esc` で閉じる）：コンテキストウィンドウを何が占めているか（システムプロンプト、ツールスキーマ、メッセージ、ツール結果、画像）、セッションのコストとその確からしさ、トークン、キャッシュされた入力、通信量 |
| **`/export`** | セッションの会話を整った GitHub-Flavored Markdown の記録として書き出す |
| **`/theme [name]`** | UI テーマを切り替え：検索できる 18 種類のパレット一覧とライブプレビュー（[テーマ](#テーマ)を参照） |
| **`/doctor`** | 環境の健全性を診断（Node、Termux、コンパイラ、API キー） |
| **`/image`** | クリップボードの画像を貼り付け（`Ctrl+V` / `Alt+V` と同じ） |
| **`/serve [port]`** | Web / ゲームのプレビュー用にローカル HTTP サーバーを起動（`/serve stop` で停止） |
| **`/memory`** | プロジェクトメモリバンクの表示、追加（`/memory add <事実>`）、消去 |
| **`/copy`** | AI の**最後の回答**を全文クリップボードにコピー（ssh 経由でも動作） |
| **`/rename`** | チャット名を変更: `/rename <名前>`。名前なしなら AI が付け直します（`/title` も同じ） |
| **`/permissions`** | 確認なしで許可されているもの（承認カードの Session / Always）。`/permissions clear` でリセット |
| **`/phones`** | devx Remote とペアリングしたスマートフォン：1台を選んで削除すると、すぐに切断され、再びペアリングするまで接続できません |
| **`/rewind`** | **自分のメッセージ**の一つまで戻る：それ以降のチャットは削除され（モデルも忘れます）、メッセージは編集できるよう入力欄に戻ります。ファイルも戻せます（git 使用時） |
| **`/tasks`** | バックグラウンドタスク：一覧、出力は `/tasks <id>`、`/tasks stop <id\|all>`、`/tasks clear` |
| **`/undo`** | AI の直前のターンによるファイル変更をすべて元に戻す |
| **`/diff`** | 変更されたプロジェクトファイルの色付き git diff を表示 |
| **`/commit`** | AI が生成するセマンティックなコミットメッセージで git commit |
| **`/status`** | git リポジトリの状態を表示 |
| **`/session`** | 現在のセッション ID、統計、メタデータを表示 |
| **`/resume`** | 保存したチャットセッションを閲覧して再開 |
| **`/session del`** | 保存したセッションを個別または一括で削除 |
| **`/settings`** | テーマ、インターフェース、YOLO 自動承認、プロジェクトメモリ、更新確認、インターフェース言語を設定 |
| **`/update`** | 新しいバージョンを確認：新機能のカードの後に **今すぐ更新**、**あとで**、または（起動時）**このバージョンをスキップ** を選びます。インストールはライブ画面で順に進み、npm に本当にそのバージョンがあるか確認してから devx を再起動します。失敗時は理由と手動で実行するコマンドを表示します |
| **`/model`** | 現在のプロバイダーのモデルをライブ検索で切り替え |
| **`/effort [level]`** | 推論の強さを設定。現在のモデルが対応するレベルだけが表示されます（[推論の強さ](#推論の強さ)を参照） |
| **`/provider`** | プロバイダーを切り替え（OpenRouter、Gemini、Groq、DeepSeek、Local） |
| **`/compact`** | チャットのコンテキストのトークンを圧縮して要約（圧縮したチャットはすぐ保存されるので、`/resume` で短い版が戻ります） |
| **`/clear`** | コンテキストを消去して新しいセッションを開始（古いセッションは保存されたまま、`/resume` を参照） |
| **`/init`** | プロジェクトのルートに開発者ガイド `AGENTS.md` を生成 |
| **`/help`** | 利用可能なコマンドをすべて表示 |
| **`/exit`** | devx を正常に終了 |

---

## キー操作

- **`Tab`** — 入力中の下書きを失わずに **PLAN** と **AGENT** を即座に切り替えます。
- **`Ctrl+V`** / **`Alt+V`** — クリップボードの画像をプロンプトに直接貼り付けます。
- **`@filename`** — 高速なあいまいファイル補完と、ファイル全体の埋め込み。
- **`Backspace`** — `[Pasted text #1]` や `[1.png 203kb]` などのバッジを 1 回で丸ごと削除します。
- **`Ctrl+Backspace`** / **`Alt+Backspace`** — テキストを単語単位で削除します（エージェントの作業中も使えます）。
- **`Ctrl+Enter`** — 入力中：送信せず改行（新しい段落）します。ターミナルが報告できる環境では `Alt+Enter` と `Shift+Enter` も同じです。`Enter` と区別できないターミナル（たとえばスマートフォンのキーボード）では、`Enter` の前に `\` を入力します。devx は、動作することがわかっているターミナル（kitty、Ghostty、WezTerm、iTerm2、foot、Alacritty）にだけ区別を求めます。Termux など、その要求をカーソルやスタイルのコードとして読むターミナルには送りません。`DEVX_EXTENDED_KEYS=1`（または `0`）で強制的にオン（またはオフ）にできます。
- **`Ctrl+P`** / **`Ctrl+O`** — チャット全体で、モデルの思考（`Ctrl+P`）とコマンドおよびその出力（`Ctrl+O`）を展開・折りたたみます。すでに上にある内容も対象です。既定ではすべて折りたたみ。エージェントの作業中にも入力欄でも使えます。
- **`Esc`** — 現在の回答と、それが開始したもの（実行中のコマンド）を停止します。入力した内容は次のプロンプトで待機します。Esc はあらゆる一覧やカードも閉じます。
- **`Ctrl+C`** — 現在の生成をただちに停止、または終了します。

---

## 推論の強さ

`/effort` は、モデルが回答の前にどれだけ考えるかを設定します：`off`、`low`、`normal`、`high`、`xhigh`、`max`、または `auto`（モデル自身の既定値、何も送りません）。モデルごとに異なるため、メニューには**現在のモデルが対応するレベルだけ**が表示されます：

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

- `/effort` でメニューを開き、`/effort high` でレベルを直接設定します（`medium`、`none`、`maximum` などもエイリアスとして使えます）。`/effort auto` でモデルの既定値に戻し、`/effort refresh` でメニューの元になるデータを更新します（後述）。
- 選択は**モデルごと**に記憶されます。保存したレベルを提供しないモデルに切り替えると、何も送られません。現在のレベルはモデル名の横に表示されます（`o3 · think high`。`think auto` はモデル自身の既定値）。エージェントの作業中、ステータス行にはモデルがこれまでにあなたのリクエストのために書いたトークン数（`↓ 364 tokens`）が表示され、ターン後の行で合計が示されます（`18.1k in · 59 out`）。
- `devx -p "task" --effort high` は 1 回の実行にだけレベルを設定します（保存されません）。
- エンドポイントが 400 を返して推論パラメーターに文句を言った場合、devx はそれを外して 1 回だけ再試行し、その旨を伝え、以後は送らなくなります。

**レベルの出どころ**（そのモデルを知っている最初の情報源が優先されます。メニューはどれを使ったかを表示します）：

1. **あなた自身のプロファイル**（`~/.devxrc.json` の `reasoningProfiles`、後述）。
2. **エンドポイント自体。** OpenRouter は全モデルの推論メタデータ（受け付ける強さ、オフにできるか）を一覧にし、Ollama は「thinking」機能を報告し、LiteLLM や多くのゲートウェイはモデル一覧にフラグを追加します。**カスタムまたはサードパーティのプロバイダーには一度だけ問い合わせます**：設定したキーを使って*そのプロバイダー自身の* base URL に数回の小さなリクエストを送ります（`GET /models`、ローカルマシンでは `POST /api/show` も、LiteLLM では `GET /model/info`）。回答は `~/.devx/capabilities.json` に 1 日キャッシュされます。大手ベンダー（OpenAI、Anthropic、Google など）には問い合わせません。それらの一覧には推論の情報がないためです。`~/.devxrc.json` の `"probeEndpoints": false` で無効にできます。
3. **[models.dev](https://models.dev) カタログ**：213 プロバイダー・約 5700 モデルの推論オプション（強さ、切り替え、トークン予算）。スナップショットが devx に同梱されているため、既定では何もダウンロードしません。`/effort refresh` は最新版を `~/.devx/` に取得し、エンドポイントにもう一度問い合わせます。devx が models.dev に接続するのはこのときだけで、あなたがそのコマンドを実行したからです。同じダウンロードでコスト表示に使う価格も更新されます（コストを参照）。
4. **モデル名によるルール**。情報源が説明していないものすべてに対するフォールバックです（下の表）。

レベルがリクエストにどう書かれるかは、引き続き base URL の API によって決まるため、その部分は devx に組み込まれています。

モデル名によるルール（フォールバック）：

| モデル | レベル | 送信形式 |
| :--- | :--- | :--- |
| OpenAI o シリーズ、gpt-oss | low、normal、high | `reasoning_effort`（OpenRouter では `reasoning.effort`） |
| GPT-5、5.1、5.2 | off（GPT-5 は minimal）、low、normal、high、5.2 以降は xhigh も | `reasoning_effort` |
| Grok 3 mini | low、high | `reasoning_effort` |
| Claude 3.7 / 4 / 4.5 | off、low、normal、high、xhigh、max（Opus 4 と 4.1 は max なし） | `thinking.budget_tokens`（Anthropic）、`reasoning.max_tokens`（OpenRouter） |
| Gemini 2.5 Pro / Flash | low から max（Flash は off も） | `thinking_budget`（Google）、`reasoning.max_tokens`（OpenRouter） |
| Qwen3 | off、low、normal、high、max | `enable_thinking` / `thinking_budget`（DashScope） |
| DeepSeek V3.1+、GLM 4.5+ | off、normal | `reasoning.enabled`、`thinking.type` |
| DeepSeek-R1、QwQ、Grok 4、Magistral | なし：常に推論する | 送信しない |

どの情報源も説明していないモデルには、推測ではなくレベルなしが割り当てられます。推論設定をどう受け取るか分かっている場合は、`~/.devxrc.json` に記述してください：

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

`param` は入れ子にできます（`"reasoning.effort"`）。プロファイルは、そのモデル ID に限り組み込みの表も上書きします。

---

## エージェントの作業中に入力する

タスク中、プロンプトの枠はターミナルの下部に残り（ライブのコンテキストとコストつき）、エージェントの作業はその上をスクロールします。エージェントがファイルを読んだり、コマンドを実行したり、考えたりしている間にそこへ入力して Enter を押すと、メッセージはキューに入り、エージェントは**次のステップで**、2つのツール呼び出しの間にそれを受け取ります。作業を続けながらも、あなたの指摘を考慮します。モデルが最終回答を書いている最中に入力したメッセージは、捨てられずに専用の回答が返ります。`Ctrl+Enter` は送信せずにメッセージ内で改行します（`Alt+Enter` と `Shift+Enter` も同様。区別できないターミナルでは `Enter` の前に `\`）。`Ctrl+Backspace` は1語を消します。そこで `/` と入力するとすべてのコマンドの一覧が開きます（矢印でスクロール、`Tab` で補完、`Enter` で実行）。設定を表示または変更するだけのコマンド（`/settings`、`/model`、`/theme`、`/usage`、`/mcp`、`/tasks`、`/help` など）は、エージェントの作業中でもすぐに開き、エージェントは待ちます。会話を変えるコマンド（`/compact`、`/new`、`/undo`、`/plan` など）は、エージェントが終わってから実行されます。上下の矢印キーで、以前に送った行を呼び戻せます（以前のチャットのものも。`~/.devx/history.jsonl` に保存されます）。`↓` は待機中のメッセージを編集のために入力行に戻し、空の行で `Enter` を押すと**今すぐ**送信します（現在の回答は停止します）。矢印キーは Caps Lock や Num Lock がオンでも動きます。ファイルパスで始まるメッセージ（`/home/me/boot.img flash this`）は、コマンドではなくメッセージです。`Esc`（または `Ctrl+C`）はエージェントを止め、入力した内容を次のプロンプトに戻します。`@file` のメンションもこれらのメッセージで使えます。承認カードや質問が画面にある間は入力行が脇に退くので、`y` / `n` がメッセージに入り込むことはありません。`~/.devxrc.json` の `"ui": { "typeAhead": false }` でオフにできます。

---

## コンテキストウィンドウ

ウィンドウのサイズは、でっち上げではなく調べて決めます。次の順序です：`~/.devxrc.json` の `contextLimits`（モデル ID ごとのあなた自身の数値）、[models.dev](https://models.dev) カタログ（約 5000 モデルについてのメーカー自身の数値。スナップショットが devx に同梱され、`/effort refresh` で最新版を取得）、モデル選択時に保存された値、devx のモデル表、モデル名によるルール、そして最後にようやく既定の 128k。`/usage` はどれが使われたかを示し、推測の場合は警告します。古いメッセージはウィンドウの 85% を超えると破棄されるため、小さすぎるウィンドウでは devx が早く忘れ、大きすぎるとプロバイダーがリクエストを拒否します（その場合、devx は履歴を短くして再試行します）。

```json
{ "contextLimits": { "my-model": 65536 } }
```

---

## API 形式（OpenAI と Anthropic）

通信形式は 2 つあります：**OpenAI**（`/v1/chat/completions`）と **Anthropic**（`/v1/messages`）。Anthropic のネイティブ形式は、署名付きの推論ブロック、プロンプトキャッシュ、正確なキャッシュ計測を備えているため、Claude モデルはこの形式が最適です。devx は両方を話し、形式を**モデルごとに自動で、キー 1 つで**選びます：

- `api.anthropic.com` と、パスに `/anthropic/` を含む base URL（MiniMax、Z.ai など）は Anthropic 形式を使います。
- それ以外のカスタムゲートウェイでは、devx はモデル一覧を 2 回取得します（あなた自身のキーで、あなた自身の base URL に数回の小さな `GET {root}/v1/models` リクエスト）：1 回は OpenAI クライアントとして、もう 1 回は Anthropic クライアントとして。2 つ目の一覧にしかないモデル（通常は Claude）は Anthropic 形式で、それ以外は OpenAI 形式で送られます。`/model` は両方の一覧を 1 つの選択画面に表示し、Anthropic のものに印を付けます。base URL は一度設定するだけです（`https://gateway.example.com/v1` または `/v1` なし）。モデルごとの設定は不要です。
- 回答は `~/.devx/formats.json` に 1 日キャッシュされます（モデル ID のみで、キーは決して保存しません）。`"probeEndpoints": false` で問い合わせを無効にでき、`~/.devxrc.json` の `"apiFormat": "openai"` または `"anthropic"` はすべてに 1 つの形式を強制します。
- Anthropic 形式が使われる場合は常に（ゲートウェイの背後でも）、推論レベル（`/effort`）は Anthropic API が期待する形（`thinking.budget_tokens`）で書かれます。推論ブロックは保持されて次のリクエストで送り返されるため、モデルはツール呼び出しの間で思考の流れを失いません。ゲートウェイがそれらやキャッシュマーカー、大きすぎる `max_tokens`、推論設定を拒否した場合、devx は問題のある部分を外して 1 回だけ再試行し、その旨を伝えます。

## コスト

リクエストのたびに会話全体が再送され、コンテキストメーターの横の数字は、それらのリクエストそれぞれのコストを合計したものです（キャッシュされた入力は安く、その価格で計算されます）。コンテキストメーター自体は、空のチャットでは 0 を示し、最初のメッセージからは、次のリクエストが運ぶすべて（システムプロンプトとツールスキーマを含む）を示します。

コンテキストメーターの横の数字は、このセッションで使った金額です。devx がどれだけ確信しているかも示します：

| 表示 | 意味 |
| :--- | :--- |
| `$0.0123` | 正確：プロバイダーが報告した、またはベンダー自身の価格表で算出した金額 |
| `~$0.0123` | 推定：同じモデルの [models.dev](https://models.dev) の価格で算出（ゲートウェイは異なる請求をする場合があります） |
| `$0.0123+` | 少なくともこの額：一部のリクエストは価格が不明で、含まれていません |
| `n/a` | そのモデルの価格は不明 |

価格は次の順で調べられ、モデルを知っている最初のものが採用されます：プロバイダーが応答に入れたコスト（OpenRouter の `usage.cost`、LiteLLM）→ `~/.devxrc.json` のあなた自身の `prices` → エンドポイント自身の価格表（OpenRouter、LiteLLM）→ models.dev のカタログ（約8500モデル。スナップショットが devx に同梱され、`/effort refresh` で最新版を取得します）。キャッシュされた入力トークン、キャッシュへの書き込み、長いコンテキストの段階は、各ベンダーと同様に別々に計算されます。送信されたのに応答がなかったリクエスト（Ctrl+C での中止、接続切断、遅いモデルのゲートウェイのタイムアウト）も、通常はプロバイダーに課金され、再試行は会話全体を再送します。そのようなリクエストも推定値（`~`）として数えられます。小さな補助作業（チャットのタイトル、`/commit` のメッセージ、`/compact` の要約）は、モデルが提供する最も軽い推論レベルで実行され、そのコストも合計に含まれます。見つからないモデルが無料と表示されることはありません。無料なのは `:free` モデルとローカルサーバーだけです。`/usage` には、キャッシュされた入力と、価格を付けられなかったリクエスト数も表示されます。

```json
{
  "prices": {
    "my-model": { "input": 0.5, "output": 2.0, "cacheRead": 0.05 }
  }
}
```

値は 100 万トークンあたりの USD です。

---

## テーマ

`/theme` は、入力で絞り込める一覧を開きます。ハイライトされたテーマは一覧の下にそのテーマ自身の色でプレビューされます。`/theme nord`（または `tokyo`、`mocha`、`gh` などのエイリアス、あるいは 1〜18 の番号）で直接切り替わり、選択は保存されます。

テーマは完全なパレットです：アクセント、plan モード用の第 2 の色、成功 / 警告 / エラーの色、YOLO モードの色、テキスト・補助テキスト・ヒント・枠線用の 4 段階のグレー、そしてメッセージ表示の背景。すべてのテーマは、暗い背景で読みやすいコントラストがあるかをテストで確認しています。

| テーマ | 特徴 |
| :--- | :--- |
| `cyan` Cyan Cyber（既定） | エレクトリックシアンと黒曜石 |
| `purple` Synthwave Purple | ネオンマゼンタ、レトロなバイオレット |
| `matrix` Matrix Hacker | 蛍光グリーン |
| `amber` Solar Amber | 温かな CRT のゴールド |
| `crimson` Ruby Crimson | 緋色 |
| `monochrome` Pure Monochrome | 白とグレーのみ |
| `nord` Nord Frost | 北極のブルー |
| `dracula` Dracula | 紫にピンクとグリーン |
| `tokyonight` Tokyo Night | インディゴとブルー |
| `gruvbox` Gruvbox | レトロな黄色とオレンジ |
| `catppuccin` Catppuccin Mocha | パステルモーヴ |
| `solarized` Solarized Dark | まぶしさを抑えたティールとオリーブ |
| `rosepine` Rose Pine | やわらかなローズとゴールド |
| `monokai` Monokai | ライムとマゼンタ |
| `github` GitHub Dark | すっきりしたブルーとグリーン |
| `ocean` Deep Ocean | 深海のシアン |
| `sunset` Sunset Glow | コーラルとバイオレット |
| `forest` Forest Moss | 苔のグリーン |

色は truecolor 対応のターミナル（`COLORTERM=truecolor`）では正確で、256 色のターミナルでは各色が最も近いパレット項目に割り当てられます。`DEVX_COLOR=256` または `DEVX_COLOR=truecolor` を設定すると、どちらかを強制できます。

---

## フック、上限、権限

- **コスト上限。** `/settings` → `Cost limit`（または `~/.devxrc.json` の `"maxCostPerSession": 5`、USD）。80% で devx が警告し、上限に達するとタスクを止め、次のメッセージの前にさらに使うかを尋ねます（続行すると、そのセッションの上限だけが引き上げられます）。
- **確認なしで許可。** 承認カードで `Session` を選ぶと、devx を閉じるまでコマンド（`npm test` のようにプログラムとサブコマンド単位）または MCP ツールが許可されます。`Always` は `~/.devxrc.json`（`bashAllowlist`、`allowedTools`）に保存します。危険なコマンド、秘密情報の読み取り、連結（`&&`、`;`、`|`）、リダイレクトは決して記憶されません。`/permissions` で一覧、`/permissions clear` で削除します。
- **フック。** エージェントのツールの前後に実行されるコマンドで、`~/.devxrc.json` に設定します：

  ```json
  {
    "hooks": {
      "PreToolUse":  [{ "matcher": "bash", "command": "./check-command.sh" }],
      "PostToolUse": [{ "matcher": "write_file|edit_file", "command": "npx prettier --write ." }]
    }
  }
  ```

  `matcher` はツール名の正規表現です（なし、または `*` ならすべてのツール）。コマンドは呼び出しを stdin の JSON（`event`、`tool_name`、`tool_input`、`PostToolUse` では `tool_result` も）と、環境変数 `DEVX_HOOK_EVENT` / `DEVX_TOOL_NAME` で受け取ります。`PreToolUse` フックの終了コード `2` はツールを止め、コマンドが stderr に書いた内容をモデルに伝えます。`PostToolUse` フックの終了コード `2` は、そのテキストを結果に追加します。プロジェクトのフック（`.devx.json`）は信頼されたワークスペースでのみ有効で、フックの制限時間は10秒です。
- **出力内の秘密情報。** ツールの出力に含まれる鍵やトークン（`sk-…`、`ghp_…`、`Bearer …`、`"apiKey": "…"`、秘密鍵）は、モデル、チャット、保存されたセッションに見える前に `[hidden secret]` に置き換えられます。
- **すべてを元に戻す。** git があれば、`/undo` はシェルコマンドが行ったことも含め、フォルダ全体を直前のターンの前の状態に戻します（`.gitignore` のファイルには触れません）。
- **承認済みのプラン**は `.devx/plan.md` に保存され、24時間エージェントに渡されます。約4000文字を超える**プロジェクトメモリ**は、モデルには最新の事実だけに切り詰められます。
- **MCP。** `/mcp add <name> <command> [args]` または `/mcp add <name> <https-url>`（HTTP のリモートサーバー。トークンは `headers` に `"Bearer ${TOKEN}"` として入れます）、`/mcp remove <name>`、`/mcp import`（Claude Code から）。
- **オンデマンドの MCP ツール。** すべての MCP ツールのスキーマはリクエストごとに送られ、大きなサーバー（Notion は約80ツール）は、何も入力する前に10万トークンかかることがあります。そのため devx は小さなサーバーのツールはすぐに送り、大きなものは保留します。エージェントは `mcp_search` ツールでサーバーの一覧を見て、タスクに必要なツールだけを探して読み込み（キーワード、ツール名、またはサーバー名だけで `mcp_search`）、次のステップから呼び出します。読み込んだツールはチャットの間残ります（再開したチャットは、使っていたものが戻ります）。どのサーバーが待機するかはサイズで決まります（まとめて送るツールが約4kトークン以内に収まるように）。サーバーごとに `mcp.json` の `"lazy": true` または `"lazy": false` で強制できます。

## セキュリティモデル

`devx` は AI モデルの代わりに、あなたのマシン上でコマンドを実行しファイルを編集します。そのため、既定では慎重に動作します：

- **ワークスペースの信頼。** フォルダを初めて開くとき、信頼するかどうかを尋ねられます（既定：いいえ）。信頼していないフォルダでは、プロジェクトの `.devx.json` にある `provider`、`apiKey`、`baseUrl`、`autoApprove`、`bashAllowlist` は**無視**されます。クローンしたリポジトリが、あなたの API キーを自分のサーバーへ向け直したり、YOLO を有効にしたりすることはできません。
- **プロジェクトの MCP サーバーは承認が必要です。** プロジェクトが宣言する MCP サーバー（`.mcp.json`、`.devx/mcp.json`、`.claude/mcp.json`、`.devx.json`）は、あなたのマシンで実行されるプログラムです。正確なコマンドを一度確認し、変更されると再度尋ねられます。ヘッドレスモード（`-p`）では、未承認のプロジェクトサーバーはスキップされます。`~/.devxrc.json` と `~/.devx/mcp.json` のサーバーは常に起動します。
- **確認。** 安全モードでは、`bash`、`install_package`、MCP ツール、プレビューサーバー、そして**プロジェクトフォルダの外**へのあらゆる読み書きに確認が必要です。
- **YOLO（`--yolo`）はサンドボックスではありません。** 通常の確認は省略しますが、危険なシェルコマンド（プロジェクト外の再帰的な削除、広いパスへの `find -delete`、`curl … | sh`、`sudo`、`git push --force` など）の前や、認証情報（`~/.ssh`、`~/.aws`、`~/.devxrc.json`、`.env`、秘密鍵）に触れる前には、引き続き確認します。シェルのチェックはコマンドをトークン単位で解析しますが、意図的に難読化されたコマンドはすり抜ける可能性があるため、失っても構わないフォルダや環境でのみ YOLO を使ってください。ヘッドレスモードではこれらの確認を表示できないため、その操作は拒否されます。
- **Extra（`--extra`、または YOLO オン時の `/settings`）は一切確認しません。** 危険なコマンドも、認証情報ファイルも、新しい MCP サーバーも確認なしです。エージェントはどんなコマンドも実行できます。オンにすると警告が表示され、プロンプトの枠に `extra` と出ます。YOLO と同様、次回起動時にはオフに戻ります。すべて失ってもよい環境でのみ使ってください。
- **`fetch_url`** は localhost、プライベートネットワーク、クラウドのメタデータアドレスを拒否します（リダイレクト後も同様）。ローカルのドキュメントサーバーを許可するには `DEVX_ALLOW_PRIVATE_NETWORK=1` を設定します。
- **`/serve`** はローカルネットワークで待ち受けます（スマートフォン用の QR コードはこの仕組みで動きます）。ドットファイル、鍵、`node_modules`、プロジェクト外へ出るシンボリックリンクは決して配信せず、緩い CORS ヘッダーも送らず、公開ホスト名宛てのリクエストは拒否します。
- **保存時の秘密情報。** `~/.devxrc.json`、`~/.devx/`、保存されたセッションは、あなただけが読める権限（`0600` / `0700`）で作成されます。セッションファイルには、エージェントが読んだファイルの内容を含む会話全体が入っています。
- **無応答の接続。** 応答も新しいデータも300秒来ないリクエストは諦めて再送されます（スマートフォンは、アプリが気づかないまま接続を失うことがあります）。`~/.devxrc.json` の `"requestTimeoutSeconds": 600` で時間を変更でき、`0` でオフになります。1バイトも送らずに何分も考えるモデルには、大きな値が必要です。
- **保存されたチャット内の画像**は、チャットの JSON の中ではなく、チャットの隣のファイル（`~/.devx/sessions/<id>.images/`）に保存されるので、チャットの保存は速いままで、`/resume` もすぐ開きます。チャットを削除すると一緒に削除されます。
- **制限。** `bash` は既定で 120 秒後にコマンドを停止し（モデルは `timeout_seconds` を渡せます。最大 1800）、stdin を閉じます。長い出力は先頭と末尾が残ります。`read_file` は 1 回の呼び出しで最大約 60 000 文字を返し、`startLine` / `endLine` に対応します。

---

## プロジェクトを応援する

**devx** が役に立ったら、[GitHub](https://github.com/apvcode/Termux-Dev) でスターを付けていただけると嬉しいです。プロジェクトの成長と今後の開発の励みになります！

---

## License

このプロジェクトは [MIT ライセンス](LICENSE) で提供されています。

---

<div align="center">

開発者、Vibe Coder、Termux ハッカーのために [ApvCode](https://github.com/ApvCode) が作りました。

</div>
