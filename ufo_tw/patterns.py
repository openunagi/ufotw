"""Pattern Engine for VORZE U.F.O. TW."""

from __future__ import annotations

import asyncio
import json
import logging
import random
from pathlib import Path

from .controller import UfoTwController
from .protocol import CW, CCW

logger = logging.getLogger(__name__)

PRESETS_DIR = Path(__file__).parent.parent / "patterns"


class PatternEngine:
    def __init__(self, ctrl: UfoTwController):
        self.ctrl = ctrl
        self._task: asyncio.Task | None = None
        self._timer_task: asyncio.Task | None = None
        self.current_pattern: str | None = None

    @property
    def is_running(self) -> bool:
        return self._task is not None and not self._task.done()

    async def play(self, steps: list, loop: bool = False, name: str | None = None,
                   max_speed: int = 100, groove: float = 0.0):
        """Play a sequence of steps.

        groove: 0.0–0.4 drag bias on step duration (D'Angelo feel).
        Each step duration becomes base * (1 + groove + small_noise).
        """
        self.cancel()
        self.current_pattern = name
        max_speed = max(0, min(100, max_speed))
        groove = max(0.0, min(0.5, groove))

        async def _run():
            try:
                while True:
                    for step in steps:
                        speed = min(step.get("speed", 0), max_speed)
                        direction = step.get("direction", CW)
                        base_dur = step.get("duration", 0.5)
                        if groove > 0:
                            noise = (random.random() * 2 - 1) * 0.05
                            duration = max(0.05, base_dur * (1.0 + groove + noise))
                        else:
                            duration = base_dur
                        await self.ctrl.set(speed, direction)
                        await asyncio.sleep(duration)
                    if not loop:
                        break
                await self.ctrl.stop()
                self.current_pattern = None
            except asyncio.CancelledError:
                try:
                    await self.ctrl.stop()
                except Exception:
                    pass
                self.current_pattern = None

        self._task = asyncio.create_task(_run())
        return self._task

    async def play_preset(self, name: str, max_speed: int = 100):
        path = PRESETS_DIR / f"{name}.json"
        with open(path) as f:
            data = json.load(f)
        await self.play(data["steps"], loop=data.get("loop", False), name=name,
                        max_speed=max_speed)

    async def play_random(self, max_speed: int = 100):
        self.cancel()
        self.current_pattern = "random"
        max_speed = max(5, min(100, max_speed))

        async def _run():
            try:
                while True:
                    lo = max(5, max_speed // 5)
                    speed = random.randint(lo, max_speed)
                    direction = random.choice([CW, CCW])
                    duration = random.uniform(0.3, 2.0)
                    await self.ctrl.set(speed, direction)
                    await asyncio.sleep(duration)
            except asyncio.CancelledError:
                try:
                    await self.ctrl.stop()
                except Exception:
                    pass
                self.current_pattern = None

        self._task = asyncio.create_task(_run())

    def cancel(self):
        if self._task and not self._task.done():
            self._task.cancel()
        self.current_pattern = None

    async def acancel(self):
        """Cancel and await task completion (use on shutdown)."""
        if self._task and not self._task.done():
            self._task.cancel()
            try:
                await self._task
            except (asyncio.CancelledError, Exception):
                pass
        self.current_pattern = None

    def set_timer(self, minutes: float, on_stop=None):
        self.cancel_timer()

        async def _timer():
            try:
                await asyncio.sleep(minutes * 60)
                logger.info("Timer expired (%.1f min), stopping.", minutes)
                self.cancel()
                await self.ctrl.stop()
                if on_stop:
                    try:
                        if asyncio.iscoroutinefunction(on_stop):
                            await on_stop()
                        else:
                            on_stop()
                    except Exception as e:
                        logger.warning("Timer on_stop callback failed: %s", e)
            except asyncio.CancelledError:
                pass

        self._timer_task = asyncio.create_task(_timer())

    def cancel_timer(self):
        if self._timer_task and not self._timer_task.done():
            self._timer_task.cancel()

    def list_presets(self) -> list[str]:
        return sorted(p.stem for p in PRESETS_DIR.glob("*.json"))


# ── Narrative Arc helpers ──────────────────────────────────────────────────

def _apply_easing(t: float, easing: str) -> float:
    """Map local progress t (0–1) through an easing curve."""
    if easing == "ease_in":
        return t * t
    elif easing == "ease_out":
        return 1.0 - (1.0 - t) ** 2
    elif easing == "ease_in_out":
        return t * t * (3.0 - 2.0 * t)
    return t  # linear


def generate_arc_steps(waypoints: list, total_seconds: float,
                       step_dur: float = 0.5) -> list:
    """Expand waypoints into a flat step list suitable for PatternEngine.play().

    waypoints: [{t: 0.0–1.0, speed: 0–100, direction: 0|1, easing: str}, ...]
    total_seconds: arc duration in seconds
    step_dur: granularity in seconds (default 0.5 s)

    Returns list of {speed, direction, duration} dicts.
    """
    if not waypoints:
        return []

    wps = sorted(waypoints, key=lambda w: float(w.get("t", 0)))

    # Pad to cover [0, 1]
    if float(wps[0].get("t", 0)) > 0.0:
        wps.insert(0, {**wps[0], "t": 0.0, "easing": "linear"})
    if float(wps[-1].get("t", 1)) < 1.0:
        wps.append({**wps[-1], "t": 1.0, "easing": "linear"})

    n_steps = max(1, int(round(total_seconds / step_dur)))
    steps = []

    for s in range(n_steps):
        progress = s / n_steps

        # Find surrounding waypoints
        prev_wp = wps[0]
        next_wp = wps[-1]
        for j in range(len(wps) - 1):
            t_j = float(wps[j].get("t", 0))
            t_j1 = float(wps[j + 1].get("t", 1))
            if t_j <= progress < t_j1:
                prev_wp = wps[j]
                next_wp = wps[j + 1]
                break

        seg_len = float(next_wp.get("t", 1)) - float(prev_wp.get("t", 0))
        if seg_len < 1e-9:
            local_t = 1.0
        else:
            local_t = (progress - float(prev_wp.get("t", 0))) / seg_len

        eased_t = _apply_easing(local_t, next_wp.get("easing", "linear"))

        spd_a = int(prev_wp.get("speed", 0))
        spd_b = int(next_wp.get("speed", 0))
        speed = max(0, min(100, round(spd_a + (spd_b - spd_a) * eased_t)))

        direction = (int(next_wp.get("direction", 0)) if eased_t >= 0.5
                     else int(prev_wp.get("direction", 0)))

        steps.append({"speed": speed, "direction": direction, "duration": step_dur})

    return steps
