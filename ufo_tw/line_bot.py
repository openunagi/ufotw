"""LINE Bot message handler for UFO TW control."""

from __future__ import annotations

import logging
import re
from typing import Awaitable, Callable

from .controller import UfoTwController
from .patterns import PatternEngine
from .protocol import CCW, CW

logger = logging.getLogger(__name__)

HELP_TEXT = (
    "UFO TW コマンド一覧\n"
    "━━━━━━━━━━━━━━━━━━\n"
    "speed <0-100>  速度設定\n"
    "cw / ccw       方向切替\n"
    "stop / 停止    緊急停止\n"
    "pattern <名前> パターン再生\n"
    "random         ランダム\n"
    "timer <分>     タイマー停止\n"
    "status / 状態  現在の状態\n"
    "help           このヘルプ"
)


async def handle_line_event(
    event: dict,
    ctrl: UfoTwController | None,
    pattern_engine: PatternEngine | None,
    reply_fn: Callable[[str, str], Awaitable[None]],
):
    if event.get("type") != "message":
        return
    msg = event.get("message", {})
    if msg.get("type") != "text":
        return

    text = msg.get("text", "").strip()
    reply_token = event.get("replyToken", "")
    response = await _dispatch(text.lower(), ctrl, pattern_engine)
    await reply_fn(reply_token, response)


async def _dispatch(
    text: str,
    ctrl: UfoTwController | None,
    pe: PatternEngine | None,
) -> str:
    if ctrl is None:
        return "デバイスに接続されていません。"

    if text in ("stop", "停止", "s"):
        if pe:
            pe.cancel()
            pe.cancel_timer()
        await ctrl.stop()
        return "停止しました。"

    if text in ("status", "状態", "st"):
        return (
            f"Speed  : {ctrl.speed}\n"
            f"Dir    : {ctrl.direction_label}\n"
            f"Pattern: {pe.current_pattern if pe else 'none'}\n"
            f"BLE    : {'connected' if ctrl.ble.is_connected else 'disconnected'}"
        )

    if text in ("help", "ヘルプ", "?"):
        return HELP_TEXT

    if text == "cw":
        await ctrl.set(ctrl.speed, CW)
        return f"時計回り (CW)  speed={ctrl.speed}"

    if text == "ccw":
        await ctrl.set(ctrl.speed, CCW)
        return f"反時計回り (CCW)  speed={ctrl.speed}"

    if text == "random" and pe:
        await pe.play_random()
        return "ランダムパターン開始"

    m = re.match(r"^(?:speed|速度|sp)\s*(\d+)$", text)
    if m:
        speed = min(100, int(m.group(1)))
        await ctrl.set(speed)
        return f"Speed: {speed}  Dir: {ctrl.direction_label}"

    m = re.match(r"^pattern\s+(\w+)$", text)
    if m and pe:
        name = m.group(1)
        try:
            await pe.play_preset(name)
            return f"パターン '{name}' 再生中"
        except FileNotFoundError:
            names = ", ".join(pe.list_presets())
            return f"'{name}' が見つかりません。\n利用可能: {names}"

    m = re.match(r"^timer\s+(\d+(?:\.\d+)?)$", text)
    if m and pe:
        minutes = float(m.group(1))
        pe.set_timer(minutes)
        return f"{minutes:.0f}分後に自動停止します。"

    return f"不明なコマンド: '{text}'\n'help' でコマンド一覧を表示。"
