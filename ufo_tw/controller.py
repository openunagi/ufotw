"""High-level controller for VORZE U.F.O. TW."""

from __future__ import annotations

import asyncio
import json
import logging
from pathlib import Path

from .ble import UfoTwBleClient
from .protocol import CW, CCW

logger = logging.getLogger(__name__)

SPEED_STEP = 5


class UfoTwController:
    """High-level API for one rotor of a UFO TW device.

    rotor=0 controls rotor 1, rotor=1 controls rotor 2.
    Both are sent as a combined packet via UfoTwBleClient.
    """

    def __init__(self, ble_client: UfoTwBleClient, rotor: int = 0):
        self.ble = ble_client
        self.rotor = rotor
        self.speed: int = 0
        self.direction: int = CW
        self._pattern_task: asyncio.Task | None = None

    @property
    def direction_label(self) -> str:
        return "CW" if self.direction == CW else "CCW"

    async def set(self, speed: int, direction: int | None = None):
        if direction is not None:
            self.direction = direction
        self.speed = max(0, min(100, speed))
        await self.ble.set_rotor(self.rotor, self.speed, self.direction)

    async def stop(self):
        self.speed = 0
        if not self.ble.is_connected:
            return
        await self.ble.set_rotor(self.rotor, 0, CW)

    async def speed_up(self):
        await self.set(self.speed + SPEED_STEP)

    async def speed_down(self):
        await self.set(self.speed - SPEED_STEP)

    async def toggle_direction(self):
        self.direction = CCW if self.direction == CW else CW
        if self.speed > 0:
            await self.set(self.speed)

    async def play_pattern(self, pattern_path: str):
        """Load and play a JSON pattern file."""
        path = Path(pattern_path)
        with open(path) as f:
            pattern = json.load(f)

        loop = pattern.get("loop", False)
        steps = pattern["steps"]

        async def _run():
            try:
                while True:
                    for step in steps:
                        speed = step.get("speed", 0)
                        direction = step.get("direction", CW)
                        duration = step.get("duration", 0.5)
                        await self.set(speed, direction)
                        logger.info(
                            "Pattern: speed=%d dir=%s hold=%.1fs",
                            speed,
                            "CW" if direction == CW else "CCW",
                            duration,
                        )
                        await asyncio.sleep(duration)
                    if not loop:
                        break
                await self.stop()
            except asyncio.CancelledError:
                await self.stop()

        self._pattern_task = asyncio.create_task(_run())
        return self._pattern_task

    def cancel_pattern(self):
        if self._pattern_task and not self._pattern_task.done():
            self._pattern_task.cancel()
