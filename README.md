# UFO TW Controller

VORZE U.F.O. TWをMac/Androidからリモート操作するための非公式ツール。

- **Python CLI / FastAPI サーバー** — BLE接続、Web UI、REST/WebSocket API、LINE Webhook
- **Android アプリ (Kotlin)** — 同じプロトコルでBLE直結、Firebase経由のリモートセッション対応
- **Web SPA / PWA** — Drift、Beat Sync、Narrative Arc などの拡張UI
- **パターンエンジン** — JSON で振動シーケンスを定義、共有可能

> ⚠️ **Disclaimer**: このソフトウェアは VORZE 公式とは無関係の非公式ツールです。本ツールの使用によるデバイス・身体・人間関係へのいかなる影響についても責任を負いません。MIT ライセンスのもと「現状のまま」提供されます。

---

## 必要環境

- macOS（CoreBluetooth 経由でBLE通信）
- Python 3.10 以上
- VORZE U.F.O. TW 本体
- （任意）Android Studio + 実機 — Android アプリをビルドする場合
- （任意）Firebase プロジェクト — リモートセッション / 共有プラザ機能を使う場合

---

## セットアップ

### 1. リポジトリをクローン

```bash
git clone https://github.com/<your-account>/ufo-tw-controller.git
cd ufo-tw-controller
```

### 2. Python 依存関係をインストール

```bash
python3 -m venv .venv
source .venv/bin/activate
pip install -r requirements.txt
```

### 3. macOS の Bluetooth 権限を許可

**システム設定 → プライバシーとセキュリティ → Bluetooth** で、使用するターミナルアプリ（Terminal / iTerm2 など）を有効化する。これがないとスキャン時に `zsh: abort` で落ちる。

### 4. （任意）Firebase を使う場合の環境変数

リモートセッション機能や共有プラザの管理機能を使う場合のみ:

1. [Firebase Console](https://console.firebase.google.com/) で自分のプロジェクトを作成
2. **プロジェクト設定 → サービスアカウント → 新しい秘密鍵の生成**
3. ダウンロードした JSON を安全な場所に保存:
   ```bash
   mkdir -p ~/.config/ufo-tw
   mv ~/Downloads/<project>-firebase-adminsdk-*.json ~/.config/ufo-tw/firebase-admin.json
   chmod 600 ~/.config/ufo-tw/firebase-admin.json
   ```
4. 環境変数を設定（`~/.zshrc` などに追加）:
   ```bash
   export FIREBASE_ADMIN_CREDENTIALS=$HOME/.config/ufo-tw/firebase-admin.json
   ```

---

## 起動方法

### 1. BLEアドレスを調べる（初回 or ペアリング後）

```bash
python main.py --scan
```

デバイス名 `UFO TW` の UUID をメモする。macOS は再ペアリングごとに UUID が変わるので、接続できなくなったらまずスキャンし直す。

### 2. サーバー起動

```bash
python main.py server --address <your-device-uuid> --port 8080
```

### 3. ブラウザでアクセス

```
http://localhost:8080
```

### Basic 認証を付ける場合

```bash
python main.py server --address <your-device-uuid> --port 8080 --user admin --password <your-password>
```

`--password` を省略すると起動時にランダム生成されてターミナルに表示される。環境変数 `UFO_USER` / `UFO_PASSWORD` でも指定可能。

### ngrok で外部公開する場合

```bash
python main.py server --address <your-device-uuid> --ngrok
```

事前に `ngrok config add-authtoken <token>` で認証しておくこと。

---

## デバイス接続がうまくいかないとき

- デバイスの電源を一度切ってから再投入する
- `--scan` で再スキャンしてUUIDを確認する（macOSはペアリングごとにUUIDが変わる）

---

## UI の使い方

### 基本操作

| 要素 | 説明 |
|---|---|
| **STOP ALL** | 全ローター即時停止 |
| **STOP 1 / STOP 2** | 個別ローター停止 |
| **Speed スライダー** | 速度 0〜100 を設定して送信（80ms デバウンス） |
| **CW / CCW** | 回転方向を切り替え（即時送信） |

---

### Smooth（なめらかな遷移）

Speed カード内の `[Smooth]` トグルをONにすると、スライダーを急に動かしても速度が滑らかに補間される。

- 補間式: `current += (target - current) * 0.25`（100ms ごと）
- OFF 時は従来通り即時送信

---

### Beat Sync 🎵

マイク入力から低音域エネルギーをリアルタイム解析し、速度にマッピングする。

1. `[🎵 Beat]` をタップ → マイクのアクセス許可
2. 音楽を流すとスピードが音量に追従
3. **Sens スライダー**で感度を調整（0.5x〜3.0x）
4. Beat Sync 中はスライダー操作が無効になる

**Bass mode**: FFT の 0〜430 Hz 帯域の平均エネルギーを使用。

---

### Drift（揺らぎ） ← NEW

微小な変化を加え続けることで知覚を維持する。

#### Speed Drift

`[Drift]` トグルをONにすると、設定速度にブラウン運動的なノイズを加えて送信し続ける。

```
スライダー設定: 50
実際の送信値:  48, 52, 47, 53, 50, 55, 48 ...（毎100ms）
```

- **幅スライダー**: ±2〜±15（どれだけ揺れるか）
- Smooth と併用可能（Smooth のターゲットに対してノイズを乗せる）
- Perceptual Mode と併用可能

#### Groove

パターン再生時にタイミングを**一貫して遅らせる**。ランダムなブレではなく、一定の「重力感」。

```
groove=0.2 の場合:
  本来 0.5秒のステップ → 実際は 0.58〜0.63秒で実行
```

- スライダー範囲: 0.00〜0.40
- パターンボタン・エディタプレビュー・Narrative Arc すべてに適用される
- **0.15〜0.25** あたりが「人間っぽい遅れ感」
- 2ローターに異なる Groove 値を設定するとポリリズム的なずれ感が生まれる

> **参照:** D'Angelo "Voodoo" のドラムトラック。ビートが一貫してわずかに遅れており、「落ちそうで落ちない」引力感を持つ。

---

### Perceptual Mode（対数スケール） ← NEW

ヘッダーの `[⊙ Perceptual]` をONにすると、スライダー値を **Weber-Fechner 則** に基づく対数スケールで変換してデバイスに送信する。

```
変換式: deviceSpeed = round(100 × log(1 + slider × (e−1) / 100))

slider=10  → device≈18  （低速域の解像度が高い）
slider=50  → device≈62
slider=100 → device=100 （変わらず）
```

- **効果**: スライダーの低〜中速域で細かいコントロールができる
- マニュアルスライダー操作のみ適用。パターン・アークには非適用
- Drift とも併用可能

---

### Patterns（プリセットパターン）

**Max スライダー**で最高速度を制限できる（10〜100）。

| パターン | 説明 |
|---|---|
| wave | 緩やかな波 |
| pulse | 短いパルスの繰り返し |
| escalate | 徐々に加速 |
| surge | 急激な加速・減速 |
| heartbeat | 心拍リズム |
| tease | 焦らし系の不規則な変化 |
| storm | 高速激しい揺れ |
| breath | 呼吸のように緩やかな波 |
| gentle | ゆっくりとした穏やかな動き |
| tide | 7分間のジャーニー（静寂→焦らし→絶頂→余韻） |
| random | ランダム |

---

### Linked Patterns（連動パターン）

2つのローターを同時に異なるパターンで起動するショートカット。

| ボタン | Rotor 1 | Rotor 2 |
|---|---|---|
| Chase | escalate | pulse |
| Mirror | wave | surge |
| Contrast | tease | storm |
| Breath | breath | breath |
| Storm Sync | storm | storm |
| Tidal | breath | wave |
| Soft | gentle | gentle |
| Rhythm | heartbeat | pulse |
| Tide | tide | tide |

---

### Auto-stop Timer

指定した分数後に自動停止する。

- 1〜120分で設定
- カウントダウン表示あり
- `Cancel` でキャンセル

---

### Pattern Editor（パターンエディタ）

`✏ Pattern Editor` セクションを開くと、ステップを自由に組んでパターンを作成・保存できる。

**ステップのパラメータ:**
- **Speed**: 0〜100
- **Dir**: CW（正転） / CCW（逆転）
- **Dur**: ステップの持続時間（秒）

**操作:**
- `+ Add Step`: ステップを追加
- `↑ / ↓`: ステップの順序を入れ替え
- `✕`: ステップを削除
- `▶ Preview`: その場で再生（対象ローターに送信）
- `💾 Save`: サーバーに `patterns/<name>.json` として保存
- `Load preset`: 既存パターンをエディタに読み込んで編集

**Groove**: デバイスカードの Groove スライダーの値がプレビュー時に適用される。

---

### Narrative Arc（ナラティブ・アーク） ← NEW

時間軸上のウェイポイントを定義し、長時間の「ストーリー」を自動再生するエンジン。

**根拠**: 快楽はストーリー構造（予期→緊張→解放）で増幅される。

#### 設定項目

| 項目 | 説明 |
|---|---|
| Preset | 組み込みプリセットを読み込む |
| Total | アーク全体の長さ（秒） |
| Waypoints | 時間軸上の制御点 |
| Groove | 全体のタイミングドラッグ（0.00〜0.40） |
| 2-Rotor | ローター2の動作モード |
| R2 Groove | ローター2専用 Groove 値 |

#### ウェイポイント

各ウェイポイントは以下を持つ:

- **t**: 0.00〜1.00（全体のどの位置か）
- **Speed**: 0〜100
- **Dir**: CW / CCW
- **Easing**: 前のウェイポイントからこのウェイポイントへの補間方法

| Easing | 説明 | 用途 |
|---|---|---|
| linear | 直線補間 | 機械的な変化 |
| ease_in ↗ | ゆっくり始まり加速 | 緊張の高まり |
| ease_out ↘ | 速く始まりゆっくり終わる | 余韻・解放 |
| ease_in_out ∫ | S字カーブ | 滑らかな転換 |

#### 組み込みプリセット

| プリセット | 時間 | 説明 |
|---|---|---|
| 焦らし型 | 10分 | 低速→微ピーク→低速→微ピーク... を繰り返す |
| 急上昇型 | 3分 | ease_in で最大速度まで一気に上昇 |
| 潮の満ち引き | 8分 | ease_in_out で緩やかな波を繰り返す |
| 追いかけっこ | 5分 | Rotor2 が Rotor1 を30秒遅れで追いかける |

#### 2-Rotor モード

| モード | 動作 |
|---|---|
| Same | 両ローター同じアークを同時再生 |
| 追いかけ | Rotor2 が指定秒数遅れて同じアークを再生 |
| 反転 | Rotor2 の方向が逆になる |

**追いかけモードの推奨設定:**
- Rotor1 Groove: 0.15、Rotor2 Groove: 0.25〜0.30
- → 片方が「重く追いかけている」感覚

---

## REST API

| メソッド | エンドポイント | 説明 |
|---|---|---|
| GET | `/api/status` | 全デバイスの状態取得 |
| POST | `/api/control` | 速度・方向を直接制御 |
| POST | `/api/stop?device=0` | 停止（device 省略で全停止） |
| GET | `/api/patterns` | パターン一覧 |
| GET | `/api/patterns/{name}` | パターン詳細取得 |
| POST | `/api/patterns` | パターン保存 |
| DELETE | `/api/patterns/{name}` | パターン削除 |
| POST | `/api/pattern/{name}` | パターン再生 |
| POST | `/api/timer` | タイマー設定 |
| DELETE | `/api/timer` | タイマーキャンセル |

---

## WebSocket API

`ws://localhost:8080/ws` に接続。

### クライアント → サーバー

```jsonc
// 手動制御
{"action": "control", "device": 0, "speed": 50, "direction": 0}

// パターン再生（groove: D'Angelo感）
{"action": "pattern", "device": 0, "name": "wave", "max_speed": 80, "groove": 0.2}

// パターン停止
{"action": "pattern", "device": 0, "name": "stop"}

// エディタステップを直接再生
{"action": "play_steps", "device": 0, "steps": [...], "loop": false, "groove": 0.15}

// ナラティブ・アーク再生
{
  "action": "play_arc",
  "arc_spec": {
    "waypoints": [
      {"t": 0.0, "speed": 5,  "direction": 0, "easing": "linear"},
      {"t": 0.5, "speed": 80, "direction": 0, "easing": "ease_in"},
      {"t": 1.0, "speed": 10, "direction": 0, "easing": "ease_out"}
    ],
    "total_seconds": 600
  },
  "groove": 0.2,
  "rotor2_mode": "chase",    // "same" | "chase" | "invert"
  "rotor2_delay": 30,
  "rotor2_groove": 0.3
}

// 全停止
{"action": "stop", "device": "all"}

// タイマー設定
{"action": "timer", "device": 0, "minutes": 10}
```

### サーバー → クライアント（1秒ごとにブロードキャスト）

```jsonc
{
  "type": "status",
  "devices": [
    {
      "device": 0,
      "connected": true,
      "speed": 50,
      "direction": 0,
      "direction_label": "CW",
      "pattern": "wave"   // null if not playing
    }
  ]
}
```

---

## BLE 技術仕様

公式 Android アプリの HCI スヌープログを解析して確認した内容。

| 項目 | 値 |
|---|---|
| デバイス名 | `UFO TW` |
| Service UUID | `40ee1111-63ec-4b7f-8ce7-712efd55b90e` |
| TX Characteristic | `40ee2222-63ec-4b7f-8ce7-712efd55b90e` （write with response） |
| 方向 | 0=CW、1=CCW |
| 速度 | 0〜100 |

### コマンドフォーマット

3 バイト固定長のパケットを TX Characteristic 1 つに書き込むだけで、両ローターを同時に制御する。

```
[0x05, (dir1 << 7) | speed1, (dir2 << 7) | speed2]
```

| バイト | 内容 |
|---|---|
| Byte 0 | デバイス ID（UFO TW = `0x05`） |
| Byte 1 | Rotor 1 の方向＋速度 |
| Byte 2 | Rotor 2 の方向＋速度 |

例:

| 操作 | HEX |
|---|---|
| 全停止 | `05 00 00` |
| Rotor1 のみ CW 50% | `05 32 00` |
| 両ローター CCW 100% | `05 E4 E4` |

### macOS での注意点

- ペアリングごとに CoreBluetooth UUID が変わる。接続前に必ず `BleakScanner.find_device_by_address()` で `BLEDevice` オブジェクトを取得してから `BleakClient` に渡すこと（UUID 文字列を直接渡すと失敗する）
- `write_gatt_char` には `response=True` を必ず指定する（このCharacteristicは write-with-response のみサポート）

---

## ファイル構成

```
ufo-tw-controller/
├── main.py                  # エントリポイント（CLI + server モード）
├── run_pattern.py           # パターン単体実行スクリプト
├── debug_connect.py         # BLE 接続デバッグ用
├── requirements.txt
├── ufo_tw/
│   ├── ble.py               # BLE スキャン・接続・送信
│   ├── protocol.py          # コマンド生成・UUID定義
│   ├── controller.py        # 高レベルコントローラー
│   ├── patterns.py          # パターンエンジン・アークジェネレータ
│   ├── cli.py               # インタラクティブ CLI
│   ├── line_bot.py          # LINE Bot メッセージハンドラ
│   └── server.py            # FastAPI アプリ
├── static/
│   └── index.html           # SPA フロントエンド
├── pwa/                     # PWA（共有プラザ含む）
├── android/                 # Kotlin Android アプリ
│   └── app/src/main/java/com/example/ufotw/
│       ├── MainActivity.kt
│       ├── BleManager.kt
│       ├── PatternEngine.kt
│       └── ...
└── patterns/                # プリセットパターン JSON
    ├── wave.json
    ├── pulse.json
    └── ...
```

---

## Android アプリ

`android/` 以下に Kotlin で書かれた Android クライアントが入っている。BLE で直接デバイスを操作する単体クライアントとして動作し、Firebase Realtime Database を使った遠隔セッション機能も搭載している。

### ビルド方法

```bash
export JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home"
cd android
./gradlew assembleDebug
```

成果物: `android/app/build/outputs/apk/debug/app-debug.apk`

### Firebase の設定（任意）

Firebase 連携機能を使うには、自分の Firebase プロジェクトの `google-services.json` を `android/app/` に配置する必要がある。

1. [Firebase Console](https://console.firebase.google.com/) でプロジェクトを作成
2. Android アプリを追加（パッケージ名: `com.first.ufotw`）
3. `google-services.json` をダウンロードして `android/app/` に置く
4. Firestore のセキュリティルールは `firestore.rules` を参考にデプロイ:
   ```bash
   firebase deploy --only firestore:rules
   ```

### 必要な権限

- `BLUETOOTH_SCAN`, `BLUETOOTH_CONNECT`（Android 12+）
- `ACCESS_FINE_LOCATION`（BLE スキャンに必要）

---

## 依存関係

```bash
pip install -r requirements.txt
```

主要パッケージ:

| パッケージ | 用途 |
|---|---|
| `bleak` | BLE 通信（macOS / Linux / Windows 対応） |
| `fastapi` | Web サーバー |
| `uvicorn` | ASGI サーバー |
| `httpx` | LINE API への HTTP リクエスト |
| `firebase-admin` | 共有プラザの管理操作（任意） |

---

## トラブルシューティング

### `zsh: abort` でスキャンが落ちる

macOS の Bluetooth 権限が足りていない。**システム設定 → プライバシーとセキュリティ → Bluetooth** で使用しているターミナルを許可する。それでもダメなら:

```bash
tccutil reset Bluetooth
```

で権限ダイアログをリセットしてから再実行。

### スキャンしてもデバイスが見つからない

- デバイスの電源を一度切ってから再投入する
- 他の端末（Android / iOS）と接続済みの場合は切断する
- BLE デーモンを再起動: `sudo pkill bluetoothd`

### `firebase-admin disabled: set FIREBASE_ADMIN_CREDENTIALS ...`

環境変数 `FIREBASE_ADMIN_CREDENTIALS` がセットされていないか、ファイルパスが間違っている。Firebase 機能を使わないなら無視してOK。

---

## ライセンス

MIT License — 詳細は [LICENSE](./LICENSE) を参照。

---

## 謝辞

- [buttplug.io](https://buttplug.io/) — Vorze プロトコルの公開ドキュメント
- [bleak](https://github.com/hbldh/bleak) — クロスプラットフォーム BLE ライブラリ
- [iostindex.com](https://iostindex.com/) — デバイス情報のデータベース
