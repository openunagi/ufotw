#!/usr/bin/env python3
"""Minimal BLE connection debug script."""

import asyncio
import logging
from bleak import BleakClient, BleakScanner

logging.basicConfig(level=logging.DEBUG)

SERVICE_UUID = "40ee1111-63ec-4b7f-8ce7-712efd55b90e"
TX_CHAR_UUID = "40ee2222-63ec-4b7f-8ce7-712efd55b90e"


async def main():
    print("Scanning...")
    devices = await BleakScanner.discover(timeout=10.0)
    target = None
    for d in devices:
        if d.name and "UFO" in d.name.upper():
            target = d
            print(f"Found: {d.name} [{d.address}]")
            break

    if not target:
        print("Not found.")
        return

    def on_disconnect(client):
        print(f"*** DISCONNECTED ***")

    print(f"\nConnecting to {target.address}...")
    async with BleakClient(target.address, disconnected_callback=on_disconnect, timeout=30.0) as client:
        print(f"Connected: {client.is_connected}")

        print("\n--- Services ---")
        for service in client.services:
            print(f"  Service: {service.uuid}")
            for char in service.characteristics:
                props = ", ".join(char.properties)
                print(f"    Char: {char.uuid} [{props}]")

        print("\n--- Sending stop command (05 01 00) ---")
        cmd = bytes([0x05, 0x01, 0x00])
        try:
            await client.write_gatt_char(TX_CHAR_UUID, cmd, response=True)
            print("Sent OK (with response)")
        except Exception as e:
            print(f"Write (no response) failed: {e}")
            try:
                await client.write_gatt_char(TX_CHAR_UUID, cmd, response=True)
                print("Sent OK (with response)")
            except Exception as e2:
                print(f"Write (with response) also failed: {e2}")

        print("\nHolding connection for 10 seconds...")
        await asyncio.sleep(10)
        print(f"Still connected: {client.is_connected}")

        CHAR2_UUID = "40ee0202-63ec-4b7f-8ce7-712efd55b90e"

        if client.is_connected:
            print("\n--- Test A: 4-byte on 40ee2222 (05 01 1E 1E) ---")
            cmd_a = bytes([0x05, 0x01, 0x1E, 0x1E])
            try:
                await client.write_gatt_char(TX_CHAR_UUID, cmd_a, response=True)
                print("Sent OK - did BOTH rotors move?")
            except Exception as e:
                print(f"Failed: {e}")
            await asyncio.sleep(5)

            print("\n--- Stopping ---")
            await client.write_gatt_char(TX_CHAR_UUID, bytes([0x05, 0x01, 0x00]), response=True)
            await asyncio.sleep(2)

            print("\n--- Test B: write to 40ee0202 (05 01 1E) ---")
            try:
                await client.write_gatt_char(CHAR2_UUID, bytes([0x05, 0x01, 0x1E]), response=True)
                print("Sent OK - did the OTHER rotor move?")
            except Exception as e:
                print(f"Failed: {e}")
            await asyncio.sleep(5)

            print("\n--- Stopping all ---")
            try:
                await client.write_gatt_char(TX_CHAR_UUID, bytes([0x05, 0x01, 0x00]), response=True)
            except Exception:
                pass
            try:
                await client.write_gatt_char(CHAR2_UUID, bytes([0x05, 0x01, 0x00]), response=True)
            except Exception:
                pass
            print("Stopped.")

    print("Done.")


if __name__ == "__main__":
    asyncio.run(main())
