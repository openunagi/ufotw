"""BLE scan, connect, and write for VORZE U.F.O. TW."""

from __future__ import annotations

import asyncio
import logging

from bleak import BleakClient, BleakScanner
from bleak.exc import BleakError

from .protocol import TX_CHAR_UUID, CW, build_command, stop_command

logger = logging.getLogger(__name__)

DEVICE_NAME = "UFO"
SCAN_TIMEOUT = 20.0
RECONNECT_DELAY = 3.0


async def find_ufo_tw(timeout: float = SCAN_TIMEOUT):
    """Scan for a UFO TW device and return the first match."""
    devices = await find_all_ufo_tw(timeout=timeout)
    return devices[0] if devices else None


async def find_all_ufo_tw(timeout: float = SCAN_TIMEOUT):
    """Scan and return all UFO TW devices found."""
    logger.info("Scanning for %s devices ...", DEVICE_NAME)
    devices = await BleakScanner.discover(timeout=timeout)
    found = [d for d in devices if d.name and DEVICE_NAME in d.name]
    for d in found:
        logger.info("Found: %s [%s]", d.name, d.address)
    return found


class UfoTwBleClient:
    """Manages BLE connection to a single UFO TW device.

    Both rotors are encoded in every packet:
      [0x05, (dir1<<7)|speed1, (dir2<<7)|speed2]
    This class tracks the current state of each rotor so that
    updating one rotor does not accidentally reset the other.
    """

    def __init__(self, address: str):
        self.address = address
        self._client: BleakClient | None = None
        self._lock: asyncio.Lock | None = None
        # Current state for each rotor: (speed, direction)
        self._rotor: list[tuple[int, int]] = [(0, CW), (0, CW)]

    @property
    def is_connected(self) -> bool:
        return self._client is not None and self._client.is_connected

    async def connect(self):
        logger.info("Scanning for device %s ...", self.address)
        device = await BleakScanner.find_device_by_address(self.address, timeout=20.0)
        if device is None:
            logger.warning("Address %s not found, scanning by name fallback...", self.address)
            device = await find_ufo_tw(timeout=20.0)
            if device is None:
                raise BleakError(f"Device with address {self.address} was not found")
            # On macOS the CoreBluetooth UUID can change between sessions.
            self.address = device.address
            logger.info("Resolved new device address: %s", self.address)
        self._client = BleakClient(
            device,
            disconnected_callback=self._on_disconnect,
            timeout=30.0,
        )
        await self._client.connect()
        self._lock = asyncio.Lock()
        logger.info("Connected to %s", self.address)
        # Give the connection time to stabilize
        await asyncio.sleep(1.0)
        logger.info("GATT ready")

    async def disconnect(self):
        if self._client and self._client.is_connected:
            await self._client.disconnect()
        logger.info("Disconnected from %s", self.address)

    async def set_rotor(self, rotor: int, speed: int, direction: int):
        """Update one rotor and send a combined packet for both rotors.

        rotor: 0 or 1
        """
        if not self.is_connected:
            logger.warning("Not connected, attempting reconnect...")
            await self.ensure_connected()

        self._rotor[rotor] = (speed, direction)
        s0, d0 = self._rotor[0]
        s1, d1 = self._rotor[1]
        cmd = build_command(s0, d0, s1, d1)

        async def _write():
            async with self._lock:
                await self._client.write_gatt_char(TX_CHAR_UUID, cmd, response=True)

        try:
            await asyncio.shield(_write())
            logger.debug("Sent rotor%d speed=%d dir=%d: %s", rotor, speed, direction, cmd.hex())
        except BleakError as e:
            logger.error("Send failed: %s", e)
            raise

    async def stop_all(self):
        """Stop both rotors."""
        self._rotor = [(0, CW), (0, CW)]
        cmd = stop_command()

        if not self.is_connected:
            return

        async def _write():
            async with self._lock:
                await self._client.write_gatt_char(TX_CHAR_UUID, cmd, response=True)

        try:
            await asyncio.shield(_write())
            logger.debug("Sent stop_all: %s", cmd.hex())
        except BleakError as e:
            logger.error("stop_all failed: %s", e)

    def _on_disconnect(self, client: BleakClient):
        logger.warning("Device disconnected: %s", self.address)

    async def ensure_connected(self):
        """Reconnect if disconnected."""
        if not self.is_connected:
            logger.info("Reconnecting to %s ...", self.address)
            await asyncio.sleep(RECONNECT_DELAY)
            await self.connect()
