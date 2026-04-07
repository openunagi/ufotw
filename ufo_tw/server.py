"""FastAPI server — REST API + WebSocket + Static files + LINE Webhook."""

from __future__ import annotations

import asyncio
import base64
import hashlib
import hmac
import json
import logging
import os
import re
import secrets
import traceback
from pathlib import Path
from typing import Optional

import httpx
from fastapi import FastAPI, HTTPException, Query, Request, WebSocket, WebSocketDisconnect
from fastapi.responses import Response
from fastapi.staticfiles import StaticFiles
from starlette.middleware.base import BaseHTTPMiddleware
from pydantic import BaseModel, Field

from .controller import UfoTwController
from .line_bot import handle_line_event
from .patterns import PatternEngine, generate_arc_steps

logger = logging.getLogger(__name__)

# ── Firebase Admin (optional) ─────────────────────────────────────────────────
# Used only for moderation deletes on the shared pattern plaza. If the SDK is
# not installed or no credentials are configured, the rest of the server keeps
# working and /api/shared/{id} returns 503.
_firestore = None
try:
    import firebase_admin
    from firebase_admin import credentials as _fb_credentials, firestore as _fb_firestore

    _cred_path = os.environ.get("FIREBASE_ADMIN_CREDENTIALS", "").strip()
    if _cred_path and Path(_cred_path).is_file():
        if not firebase_admin._apps:
            firebase_admin.initialize_app(_fb_credentials.Certificate(_cred_path))
        _firestore = _fb_firestore.client()
        logger.info("firebase-admin initialized from %s", _cred_path)
    else:
        logger.warning(
            "firebase-admin disabled: set FIREBASE_ADMIN_CREDENTIALS to a "
            "service-account JSON path to enable /api/shared deletion."
        )
except ImportError:
    logger.warning("firebase-admin not installed; /api/shared deletion disabled.")
except Exception as _fb_err:  # noqa: BLE001
    logger.warning("firebase-admin init failed: %s", _fb_err)

# ── Paths ─────────────────────────────────────────────────────────────────────
_PATTERNS_DIR = Path(__file__).parent.parent / "patterns"

# ── Global state ──────────────────────────────────────────────────────────────
ctrls: list[UfoTwController] = []
pattern_engines: list[PatternEngine] = []
_ws_clients: set[WebSocket] = set()

LINE_CHANNEL_SECRET = os.environ.get("LINE_CHANNEL_SECRET", "")
LINE_CHANNEL_TOKEN = os.environ.get("LINE_CHANNEL_ACCESS_TOKEN", "")


# ── Basic Auth Middleware ─────────────────────────────────────────────────────
class BasicAuthMiddleware(BaseHTTPMiddleware):
    """HTTP Basic Auth — skips /webhook (LINE has its own signature check)."""

    def __init__(self, app, username: str, password: str):
        super().__init__(app)
        token = f"{username}:{password}".encode()
        self._expected = base64.b64encode(token).decode()

    async def dispatch(self, request: Request, call_next):
        if request.url.path == "/webhook":
            return await call_next(request)
        auth = request.headers.get("Authorization", "")
        if auth.startswith("Basic ") and secrets.compare_digest(auth[6:], self._expected):
            return await call_next(request)
        return Response(
            content="Unauthorized",
            status_code=401,
            headers={"WWW-Authenticate": 'Basic realm="UFO TW"'},
        )


def add_auth(username: str, password: str):
    app.add_middleware(BasicAuthMiddleware, username=username, password=password)


# ── App ───────────────────────────────────────────────────────────────────────
app = FastAPI(title="UFO TW Controller")


# ── Helpers ───────────────────────────────────────────────────────────────────
def _device_status(i: int) -> dict:
    if i >= len(ctrls):
        return {"device": i, "connected": False, "speed": 0, "direction": 0,
                "direction_label": "CW", "pattern": None}
    ctrl = ctrls[i]
    pe = pattern_engines[i] if i < len(pattern_engines) else None
    return {
        "device": i,
        "connected": ctrl.ble.is_connected,
        "speed": ctrl.speed,
        "direction": ctrl.direction,
        "direction_label": ctrl.direction_label,
        "pattern": pe.current_pattern if pe else None,
    }


def _all_status() -> dict:
    return {"devices": [_device_status(i) for i in range(len(ctrls))]}


async def broadcast_status():
    if not _ws_clients:
        return
    data = json.dumps({"type": "status", **_all_status()})
    dead: set[WebSocket] = set()
    for ws in list(_ws_clients):
        try:
            await ws.send_text(data)
        except Exception:
            dead.add(ws)
    _ws_clients.difference_update(dead)


# ── Startup ───────────────────────────────────────────────────────────────────
@app.on_event("startup")
async def _start_broadcaster():
    async def _loop():
        while True:
            await asyncio.sleep(1.0)
            await broadcast_status()

    asyncio.create_task(_loop())


# ── Models ────────────────────────────────────────────────────────────────────
class ControlRequest(BaseModel):
    device: int = 0
    speed: int = Field(0, ge=0, le=100)
    direction: int = Field(0, ge=0, le=1)


class TimerRequest(BaseModel):
    device: int = 0
    minutes: float = Field(..., gt=0, le=300)


class PatternSaveRequest(BaseModel):
    name: str
    steps: list
    loop: bool = False


# ── REST API ──────────────────────────────────────────────────────────────────
@app.get("/api/status")
async def api_status():
    return _all_status()


@app.post("/api/control")
async def api_control(req: ControlRequest):
    if req.device >= len(ctrls):
        raise HTTPException(503, "Device not connected")
    await ctrls[req.device].set(req.speed, req.direction)
    await broadcast_status()
    return _all_status()


@app.post("/api/stop")
async def api_stop(device: Optional[int] = Query(default=None)):
    targets = list(range(len(ctrls))) if device is None else [device]
    for i in targets:
        if i < len(pattern_engines):
            pattern_engines[i].cancel()
            pattern_engines[i].cancel_timer()
        if i < len(ctrls):
            await ctrls[i].stop()
    await broadcast_status()
    return _all_status()


@app.get("/api/patterns")
async def api_patterns():
    presets = sorted(p.stem for p in _PATTERNS_DIR.glob("*.json")) if _PATTERNS_DIR.exists() else []
    return {"patterns": presets + ["random"]}


@app.get("/api/patterns/{name}")
async def api_pattern_get(name: str):
    path = _PATTERNS_DIR / f"{name}.json"
    if not path.exists():
        raise HTTPException(404, f"Pattern '{name}' not found")
    return json.loads(path.read_text())


@app.post("/api/patterns")
async def api_patterns_save(req: PatternSaveRequest):
    import re
    name = req.name.strip()
    if not name or not re.match(r'^[\w\-]+$', name):
        raise HTTPException(400, "Invalid pattern name (use letters, numbers, _ or -)")
    _PATTERNS_DIR.mkdir(exist_ok=True)
    path = _PATTERNS_DIR / f"{name}.json"
    path.write_text(json.dumps({"steps": req.steps, "loop": req.loop}, indent=2))
    return {"ok": True, "name": name}


@app.delete("/api/patterns/{name}")
async def api_patterns_delete(name: str):
    path = _PATTERNS_DIR / f"{name}.json"
    if not path.exists():
        raise HTTPException(404, f"Pattern '{name}' not found")
    path.unlink()
    return {"ok": True, "name": name}


@app.delete("/api/shared/{doc_id}")
async def api_shared_delete(doc_id: str):
    """Admin-only delete for shared_patterns. Protected by the global Basic Auth
    middleware. Requires FIREBASE_ADMIN_CREDENTIALS env var at startup."""
    if _firestore is None:
        raise HTTPException(503, "Firebase admin not initialized")
    if not re.match(r"^[A-Za-z0-9_-]{1,80}$", doc_id):
        raise HTTPException(400, "Invalid doc id")
    try:
        await asyncio.to_thread(
            lambda: _firestore.collection("shared_patterns").document(doc_id).delete()
        )
    except Exception as e:  # noqa: BLE001
        logger.warning("Firestore delete failed for %s: %s", doc_id, e)
        raise HTTPException(500, "Firestore delete failed")
    return {"ok": True, "id": doc_id}


@app.post("/api/pattern/stop")
async def api_pattern_stop(device: Optional[int] = Query(default=None)):
    targets = list(range(len(ctrls))) if device is None else [device]
    for i in targets:
        if i < len(pattern_engines):
            pattern_engines[i].cancel()
        if i < len(ctrls):
            await ctrls[i].stop()
    await broadcast_status()
    return _all_status()


@app.post("/api/pattern/{name}")
async def api_pattern(name: str, device: int = Query(default=0),
                      max_speed: int = Query(default=100)):
    if device >= len(ctrls):
        raise HTTPException(503, "Device not connected")
    if device >= len(pattern_engines):
        raise HTTPException(503, "Pattern engine not ready")
    pe = pattern_engines[device]
    if name == "random":
        await pe.play_random(max_speed=max_speed)
    else:
        try:
            await pe.play_preset(name, max_speed=max_speed)
        except FileNotFoundError:
            raise HTTPException(404, f"Pattern '{name}' not found")
    await broadcast_status()
    return _all_status()


@app.post("/api/timer")
async def api_timer_set(req: TimerRequest):
    if req.device >= len(pattern_engines):
        raise HTTPException(503, "Pattern engine not ready")
    pattern_engines[req.device].set_timer(req.minutes, on_stop=broadcast_status)
    return {"ok": True, "device": req.device, "minutes": req.minutes}


@app.delete("/api/timer")
async def api_timer_cancel(device: Optional[int] = Query(default=None)):
    targets = list(range(len(pattern_engines))) if device is None else [device]
    for i in targets:
        if i < len(pattern_engines):
            pattern_engines[i].cancel_timer()
    return {"ok": True}


# ── WebSocket ─────────────────────────────────────────────────────────────────
@app.websocket("/ws")
async def websocket_endpoint(ws: WebSocket):
    await ws.accept()
    _ws_clients.add(ws)
    try:
        await ws.send_text(json.dumps({"type": "status", **_all_status()}))
        while True:
            raw = await ws.receive_text()
            try:
                await _handle_ws(json.loads(raw))
            except Exception as e:
                logger.warning("WS handler error: %s\n%s", e, traceback.format_exc())
    except WebSocketDisconnect:
        pass
    finally:
        _ws_clients.discard(ws)


async def _handle_ws(msg: dict):
    action = msg.get("action")
    device = msg.get("device", 0)

    if action == "stop":
        if device == "all":
            for pe in pattern_engines:
                pe.cancel()
            for c in ctrls:
                await c.stop()
        else:
            pe = pattern_engines[device] if isinstance(device, int) and device < len(pattern_engines) else None
            ctrl = ctrls[device] if isinstance(device, int) and device < len(ctrls) else None
            if pe:
                pe.cancel()
            if ctrl:
                await ctrl.stop()
        await broadcast_status()
        return

    if not isinstance(device, int) or device < 0 or device >= len(ctrls):
        return

    ctrl = ctrls[device]
    pe = pattern_engines[device] if device < len(pattern_engines) else None

    if action == "control":
        await ctrl.set(msg.get("speed", ctrl.speed), msg.get("direction", ctrl.direction))
        await broadcast_status()

    elif action == "pattern" and pe:
        name = msg.get("name", "")
        try:
            max_speed = max(0, min(100, int(msg.get("max_speed") or 100)))
        except (TypeError, ValueError):
            max_speed = 100
        try:
            groove = max(0.0, min(0.5, float(msg.get("groove") or 0.0)))
        except (TypeError, ValueError):
            groove = 0.0
        if name == "stop":
            pe.cancel()
            await ctrl.stop()
        elif name == "random":
            await pe.play_random(max_speed=max_speed)
        elif name:
            try:
                path = _PATTERNS_DIR / f"{name}.json"
                import json as _json
                data = _json.loads(path.read_text())
                await pe.play(data["steps"], loop=data.get("loop", False),
                              name=name, max_speed=max_speed, groove=groove)
            except FileNotFoundError:
                logger.warning("Pattern not found: %s", name)
        await broadcast_status()

    elif action == "play_steps" and pe:
        steps = msg.get("steps", [])
        loop = bool(msg.get("loop", False))
        try:
            groove = max(0.0, min(0.5, float(msg.get("groove") or 0.0)))
        except (TypeError, ValueError):
            groove = 0.0
        if steps:
            await pe.play(steps, loop=loop, name="__editor__", groove=groove)
        await broadcast_status()

    elif action == "play_arc":
        spec = msg.get("arc_spec", {})
        waypoints = spec.get("waypoints", [])
        total_seconds = float(spec.get("total_seconds", 300))
        try:
            groove = max(0.0, min(0.5, float(msg.get("groove") or 0.0)))
        except (TypeError, ValueError):
            groove = 0.0
        try:
            r2_groove = max(0.0, min(0.5, float(msg.get("rotor2_groove") or groove)))
        except (TypeError, ValueError):
            r2_groove = groove
        rotor2_mode = msg.get("rotor2_mode", "same")
        try:
            rotor2_delay = max(0.0, float(msg.get("rotor2_delay") or 0.0))
        except (TypeError, ValueError):
            rotor2_delay = 0.0

        if not waypoints:
            return

        base_steps = generate_arc_steps(waypoints, total_seconds)

        for idx, pe_i in enumerate(pattern_engines):
            if idx >= len(ctrls):
                break
            if idx == 1 and rotor2_mode == "chase" and rotor2_delay > 0:
                n_silence = max(1, int(round(rotor2_delay / 0.5)))
                silence = [{"speed": 0, "direction": 0, "duration": 0.5}] * n_silence
                dev_steps = silence + list(base_steps)
            elif idx == 1 and rotor2_mode == "invert":
                dev_steps = [dict(s, direction=1 - s["direction"]) for s in base_steps]
            else:
                dev_steps = base_steps
            dev_groove = r2_groove if idx == 1 else groove
            await pe_i.play(dev_steps, loop=False, name="__arc__", groove=dev_groove)

        await broadcast_status()

    elif action == "timer" and pe:
        minutes = msg.get("minutes", 0)
        if minutes > 0:
            pe.set_timer(minutes, on_stop=broadcast_status)
        else:
            pe.cancel_timer()


# ── LINE Webhook ──────────────────────────────────────────────────────────────
@app.post("/webhook")
async def line_webhook(request: Request):
    body = await request.body()

    if LINE_CHANNEL_SECRET:
        sig = request.headers.get("X-Line-Signature", "")
        digest = base64.b64encode(
            hmac.new(LINE_CHANNEL_SECRET.encode(), body, hashlib.sha256).digest()
        ).decode()
        if not hmac.compare_digest(sig, digest):
            raise HTTPException(400, "Invalid LINE signature")

    try:
        payload = json.loads(body)
    except json.JSONDecodeError:
        raise HTTPException(400, "Invalid JSON body")
    ctrl0 = ctrls[0] if ctrls else None
    pe0 = pattern_engines[0] if pattern_engines else None
    for event in payload.get("events", []):
        await handle_line_event(event, ctrl0, pe0, _line_reply)

    return {"ok": True}


async def _line_reply(reply_token: str, text: str):
    if not LINE_CHANNEL_TOKEN or not reply_token:
        return
    async with httpx.AsyncClient() as client:
        resp = await client.post(
            "https://api.line.me/v2/bot/message/reply",
            headers={"Authorization": f"Bearer {LINE_CHANNEL_TOKEN}"},
            json={
                "replyToken": reply_token,
                "messages": [{"type": "text", "text": text}],
            },
            timeout=10.0,
        )
        if resp.status_code != 200:
            logger.warning("LINE reply failed: %s %s", resp.status_code, resp.text)


# ── Static files (served last — SPA fallback) ─────────────────────────────────
_STATIC_DIR = Path(__file__).parent.parent / "static"
if _STATIC_DIR.exists():
    app.mount("/", StaticFiles(directory=_STATIC_DIR, html=True), name="static")
