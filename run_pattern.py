#!/usr/bin/env python3
"""Run a pattern on UFO TW directly."""

import asyncio
import signal
from ufo_tw.ble import UfoTwBleClient
from ufo_tw.controller import UfoTwController

PATTERN = "patterns/pleasure.json"


async def main():
    from ufo_tw.ble import find_ufo_tw
    print("Scanning for UFO TW...")
    device = await find_ufo_tw(timeout=15.0)
    if device is None:
        print("UFO TW not found. Make sure the device is powered on.")
        return
    print(f"Found: {device.name} [{device.address}]")

    ble = UfoTwBleClient(device.address)
    print("Connecting...")
    await ble.connect()
    print("Connected!")

    ctrl = UfoTwController(ble)

    stop_event = asyncio.Event()

    def on_signal():
        print("\nStopping...")
        stop_event.set()

    loop = asyncio.get_event_loop()
    for sig in (signal.SIGINT, signal.SIGTERM):
        loop.add_signal_handler(sig, on_signal)

    print(f"Playing pattern: {PATTERN}")
    print("Press Ctrl+C to stop.\n")

    task = await ctrl.play_pattern(PATTERN)

    # Wait for either pattern to finish or Ctrl+C
    done, pending = await asyncio.wait(
        [task, asyncio.create_task(stop_event.wait())],
        return_when=asyncio.FIRST_COMPLETED,
    )

    ctrl.cancel_pattern()
    try:
        await ctrl.stop()
    except Exception:
        pass
    print("Stopped.")
    try:
        await ble.disconnect()
    except Exception:
        pass
    print("Disconnected.")


if __name__ == "__main__":
    asyncio.run(main())
