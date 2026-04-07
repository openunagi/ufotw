#!/usr/bin/env python3
"""VORZE U.F.O. TW BLE Controller — Entry point."""

from __future__ import annotations

import argparse
import asyncio
import logging
import secrets
import subprocess
import sys


async def main():
    parser = argparse.ArgumentParser(description="VORZE U.F.O. TW BLE Controller")
    parser.add_argument(
        "command",
        nargs="?",
        choices=["server"],
        help="'server' to start the web server (default: interactive CLI)",
    )
    parser.add_argument("--address", help="BLE address of device 1 (skip scan)")
    parser.add_argument("--address2", help="BLE address of device 2 (optional)")
    parser.add_argument("--pattern", help="Path to a JSON pattern file to play")
    parser.add_argument("--scan", action="store_true", help="Scan and list BLE devices")
    parser.add_argument("--port", type=int, default=8080, help="Web server port (server mode)")
    parser.add_argument("--host", default="0.0.0.0", help="Web server host (server mode)")
    parser.add_argument("--ngrok", action="store_true", help="Expose via ngrok tunnel")
    parser.add_argument("--user", default="admin", help="Basic Auth username (default: admin)")
    parser.add_argument("--password", default=None, help="Basic Auth password (auto-generated if omitted)")
    parser.add_argument("-v", "--verbose", action="store_true", help="Enable debug logging")
    args = parser.parse_args()

    logging.basicConfig(
        level=logging.DEBUG if args.verbose else logging.INFO,
        format="%(asctime)s [%(levelname)s] %(name)s: %(message)s",
    )

    if args.command == "server":
        await _run_server(args)
    else:
        await _run_cli(args)


async def _run_cli(args):
    from bleak import BleakScanner

    from ufo_tw.ble import UfoTwBleClient, find_ufo_tw
    from ufo_tw.cli import run_cli
    from ufo_tw.controller import UfoTwController

    if args.scan:
        print("Scanning all BLE devices for 15 seconds...")
        devices = await BleakScanner.discover(timeout=15.0)
        if not devices:
            print("No BLE devices found.")
            print("Check: System Settings > Privacy & Security > Bluetooth")
        else:
            print(f"\nFound {len(devices)} device(s):\n")
            for d in sorted(devices, key=lambda x: x.name or ""):
                print(f"  {d.address}  {d.name or '(no name)'}")
            ufo = [d for d in devices if d.name and "UFO" in d.name.upper()]
            if ufo:
                print(f"\nUFO device found! Use: python main.py --address {ufo[0].address}")
            else:
                print("\nNo UFO device found in scan results.")
        sys.exit(0)

    address = args.address or await _find_device()
    if not address:
        sys.exit(1)

    ble_client = UfoTwBleClient(address)
    try:
        await ble_client.connect()
    except Exception as e:
        print(f"Failed to connect: {e}")
        sys.exit(1)

    ctrl = UfoTwController(ble_client)
    try:
        if args.pattern:
            print(f"Playing pattern: {args.pattern}")
            print("Press Ctrl+C to stop.")
            task = await ctrl.play_pattern(args.pattern)
            await task
        else:
            await run_cli(ctrl)
    except KeyboardInterrupt:
        print("\r\nInterrupted.")
        ctrl.cancel_pattern()
        await ctrl.stop()
    finally:
        await ble_client.disconnect()


async def _run_server(args):
    import os

    import uvicorn

    import ufo_tw.server as srv
    from ufo_tw.ble import UfoTwBleClient
    from ufo_tw.controller import UfoTwController
    from ufo_tw.patterns import PatternEngine

    # Find device address
    address = args.address
    if not address:
        device = await _find_device()
        if not device:
            sys.exit(1)
        address = device

    # Connect (single BLE connection; two rotors = two controllers)
    ble = UfoTwBleClient(address)
    try:
        await ble.connect()
    except Exception as e:
        print(f"Failed to connect: {e}")
        sys.exit(1)

    ble_clients = [ble]
    ctrls_list = [UfoTwController(ble, rotor=0), UfoTwController(ble, rotor=1)]
    pes = [PatternEngine(ctrls_list[0]), PatternEngine(ctrls_list[1])]

    # Credentials
    password = args.password or os.environ.get("UFO_PASSWORD") or secrets.token_urlsafe(8)
    username = args.user or os.environ.get("UFO_USER", "admin")

    srv.ctrls = ctrls_list
    srv.pattern_engines = pes
    srv.add_auth(username, password)

    _print_startup_banner(username, password, args.port, ngrok=args.ngrok)

    ngrok_proc = None
    try:
        config = uvicorn.Config(
            srv.app,
            host=args.host,
            port=args.port,
            log_level="debug" if logging.getLogger().level == logging.DEBUG else "info",
        )
        server = uvicorn.Server(config)
        # Start uvicorn in background so ngrok can poll it
        serve_task = asyncio.create_task(server.serve())

        if args.ngrok:
            ngrok_proc = await _start_ngrok(args.port, username, password)

        await serve_task
    except KeyboardInterrupt:
        pass
    finally:
        if ngrok_proc:
            ngrok_proc.terminate()
        for pe in pes:
            await pe.acancel()
            if pe._timer_task and not pe._timer_task.done():
                pe._timer_task.cancel()
                try:
                    await pe._timer_task
                except (asyncio.CancelledError, Exception):
                    pass
        for ctrl in ctrls_list:
            await ctrl.stop()
        for ble in ble_clients:
            await ble.disconnect()


def _print_startup_banner(username: str, password: str, port: int, ngrok: bool):
    sep = "─" * 44
    print(sep)
    print("  UFO TW Controller")
    print(sep)
    print(f"  Local URL : http://localhost:{port}")
    print(f"  Username  : {username}")
    print(f"  Password  : {password}")
    if ngrok:
        print("  ngrok     : starting tunnel...")
    print(sep)
    print("  Press Ctrl+C to stop.")
    print()


async def _start_ngrok(port: int, username: str, password: str) -> subprocess.Popen | None:
    """Start ngrok tunnel and print the public URL."""
    import httpx

    proc = subprocess.Popen(
        ["ngrok", "http", str(port), "--log", "stdout"],
        stdout=subprocess.DEVNULL,
        stderr=subprocess.DEVNULL,
    )

    # Poll ngrok local API (up to 15s) for the public HTTPS URL
    for _ in range(15):
        await asyncio.sleep(1.0)
        try:
            async with httpx.AsyncClient() as client:
                resp = await client.get("http://localhost:4040/api/tunnels", timeout=2.0)
                for tunnel in resp.json().get("tunnels", []):
                    if tunnel.get("proto") == "https":
                        url = tunnel["public_url"]
                        sep = "─" * 44
                        print(sep)
                        print(f"  Public URL: {url}")
                        print(f"  Share this with your partner:")
                        print(f"  {url}  ({username} / {password})")
                        print(sep)
                        print()
                        return proc
        except Exception:
            pass

    print("[ngrok] Could not get public URL. Is ngrok authenticated? Run: ngrok config add-authtoken <token>")
    proc.terminate()
    return None


async def _find_device() -> str | None:
    from ufo_tw.ble import find_ufo_tw

    device = await find_ufo_tw()
    if device is None:
        print("UFO TW not found. Make sure the device is powered on.")
        return None
    return device.address


if __name__ == "__main__":
    asyncio.run(main())
