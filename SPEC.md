# VORZE U.F.O. TW — Mac BLE制御ツール仕様書

## 概要

VORZE U.F.O. TW（乳首刺激デバイス）をMacからBLE経由で制御するPython CLIツールを作成する。

---

## BLE通信仕様（実機確認済み）

| 項目 | 値 |
|---|---|
| BLEデバイス名 | `UFO TW` |
| MACアドレス例 | `C6:8D:E8:97:72:4D` |
| Service UUID | `40ee1111-63ec-4b7f-8ce7-712efd55b90e` |
| TX Characteristic UUID | `40ee2222-63ec-4b7f-8ce7-712efd55b90e` |
| RX/Read Characteristic 1 | `40ee3333-63ec-4b7f-8ce7-712efd55b90e` (READ, 用途未確認) |
| RX/Read Characteristic 2 | `40ee4444-63ec-4b7f-8ce7-712efd55b90e` (READ, 用途未確認) |
| 通信方式 | BLE GATT Write（一方向） |
| ペアリング | 不要（NOT BONDED） |

## コマンドフォーマット

3バイト固定長パケットをTX Characteristicに書き込む。

```
[Byte0: デバイスID] [Byte1: 予約] [Byte2: 方向+速度]
```

### Byte 0 — デバイスID

| デバイス | ID |
|---|---|
| A10 Cyclone SA | `0x01` |
| UFO SA | `0x02` |
| Piston SA | `0x03` |
| **UFO TW** | **`0x05`** ← 実機で確認済み |

### Byte 1 — 予約

常に `0x01`。

### Byte 2 — 方向 + 速度

```
bit7: 方向 (0 = 時計回り CW, 1 = 反時計回り CCW)
bit6-0: 速度 (0〜100, 100超は無視される)
```

計算式:
```python
byte2 = (direction << 7) | speed
# direction: 0=CW, 1=CCW
# speed: 0-100
```

### コマンド例

| 操作 | HEXバイト列 | 説明 |
|---|---|---|
| 停止 | `05 01 00` | speed=0 |
| CW 50% | `05 01 32` | 0x32 = 50 |
| CW 100% | `05 01 64` | 0x64 = 100 |
| CCW 50% | `05 01 B2` | 0x80 \| 50 = 0xB2 |
| CCW 100% | `05 01 E4` | 0x80 \| 100 = 0xE4 |

---

## 実装要件

### 技術スタック

- Python 3.10+
- bleak（BLEライブラリ、macOS CoreBluetooth対応）
- asyncio

### 機能一覧

1. **BLEスキャン** — `UFO TW` をデバイス名で検出
2. **接続** — 自動接続、接続状態の監視・再接続
3. **回転制御**
   - 速度設定（0〜100）
   - 方向切替（CW / CCW）
   - 停止
4. **インタラクティブCLI**
   - `↑` / `↓` キーで速度増減（5刻み推奨）
   - `←` / `→` キーで方向切替
   - `Space` で即停止
   - `q` で停止＆切断＆終了
5. **パターン再生**（オプション）
   - 速度の時系列パターンをJSONで定義して再生
   - 例: 徐々に加速→減速のウェーブパターン

### コード構造案

```
ufo-tw-controller/
├── README.md
├── requirements.txt          # bleak
├── ufo_tw/
│   ├── __init__.py
│   ├── ble.py               # BLEスキャン・接続・書き込み
│   ├── protocol.py          # コマンド組み立て（3バイトパケット生成）
│   ├── controller.py        # 高レベルAPI（speed_up, speed_down, stop, etc.）
│   └── cli.py               # インタラクティブCLI（キーボード入力）
├── patterns/
│   └── wave.json            # パターン定義例
└── main.py                  # エントリポイント
```

### protocol.py の核心部分

```python
SERVICE_UUID = "40ee1111-63ec-4b7f-8ce7-712efd55b90e"
TX_CHAR_UUID = "40ee2222-63ec-4b7f-8ce7-712efd55b90e"
DEVICE_ID = 0x05  # UFO TW
RESERVED = 0x01

def build_command(speed: int, direction: int = 0) -> bytes:
    """
    speed: 0-100
    direction: 0=CW, 1=CCW
    """
    assert 0 <= speed <= 100
    assert direction in (0, 1)
    byte2 = (direction << 7) | speed
    return bytes([DEVICE_ID, RESERVED, byte2])
```

### ble.py の要点

```python
from bleak import BleakClient, BleakScanner

async def find_ufo_tw():
    devices = await BleakScanner.discover()
    for d in devices:
        if d.name and "UFO TW" in d.name:
            return d
    return None

async def send_command(client: BleakClient, cmd: bytes):
    await client.write_gatt_char(TX_CHAR_UUID, cmd)
```

---

## 未解明事項（追加調査が必要な場合）

1. **ヒーティング（加熱）機能の制御コマンド**
   - U.F.O. TWにはヒーター内蔵だが、BLEコマンドは未確認
   - `40ee3333-...` / `40ee4444-...` のREAD値に手がかりがある可能性
   - 公式Androidアプリのヒーター操作時のHCIスヌープログで判明する見込み
2. **2台同時制御**
   - U.F.O. TWは2個セットで使う前提の製品
   - bleakは複数デバイスへの同時接続が可能
   - 各デバイスに個別のBleakClientを持てばよい

---

## 参考情報

- buttplug.io STPIHKAL: https://buttplug-spec.docs.buttplug.io/docs/stpihkal/protocols/vorze-sa/
- buttplug device config (旧SA系): Service/Characteristic UUIDはTWと共通
- buttplug Rust CHANGELOG: v6.0.1でUFO TWサポート追加、v7.0.2でrotation commandバグフィックス
- IoST Index: https://iostindex.com/devices/vorze/u.f.o.%20tw/
