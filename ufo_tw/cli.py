"""Interactive CLI for VORZE U.F.O. TW control."""

import asyncio
import sys
import termios
import tty
from typing import Callable, Coroutine

from .controller import UfoTwController


def _print_status(ctrl: UfoTwController):
    bar_len = ctrl.speed // 2  # 0-50 chars
    bar = "#" * bar_len + "-" * (50 - bar_len)
    sys.stdout.write(
        f"\r  Speed: {ctrl.speed:>3}  Dir: {ctrl.direction_label:<3}  "
        f"[{bar}]    "
    )
    sys.stdout.flush()


HELP_TEXT = """
--- UFO TW Controller ---
  Up    / k : Speed +5
  Down  / j : Speed -5
  Left  / h : Direction CW
  Right / l : Direction CCW
  Space     : Emergency stop
  q         : Stop & quit
-------------------------
"""


async def run_cli(ctrl: UfoTwController):
    """Run the interactive key-based CLI."""
    print(HELP_TEXT)
    _print_status(ctrl)

    fd = sys.stdin.fileno()
    old_settings = termios.tcgetattr(fd)

    try:
        tty.setraw(fd)

        while True:
            # Non-blocking read with asyncio
            ch = await asyncio.get_event_loop().run_in_executor(
                None, lambda: sys.stdin.read(1)
            )

            if ch == "q":
                print("\r\nStopping and disconnecting...")
                await ctrl.stop()
                break

            if ch == " ":
                await ctrl.stop()
            elif ch == "k":
                await ctrl.speed_up()
            elif ch == "j":
                await ctrl.speed_down()
            elif ch == "h":
                if ctrl.direction != 0:
                    ctrl.direction = 0
                    if ctrl.speed > 0:
                        await ctrl.set(ctrl.speed)
            elif ch == "l":
                if ctrl.direction != 1:
                    ctrl.direction = 1
                    if ctrl.speed > 0:
                        await ctrl.set(ctrl.speed)
            elif ch == "\x1b":
                # Arrow key escape sequence: ESC [ A/B/C/D
                seq1 = await asyncio.get_event_loop().run_in_executor(
                    None, lambda: sys.stdin.read(1)
                )
                if seq1 == "[":
                    seq2 = await asyncio.get_event_loop().run_in_executor(
                        None, lambda: sys.stdin.read(1)
                    )
                    if seq2 == "A":  # Up
                        await ctrl.speed_up()
                    elif seq2 == "B":  # Down
                        await ctrl.speed_down()
                    elif seq2 == "D":  # Left -> CW
                        if ctrl.direction != 0:
                            ctrl.direction = 0
                            if ctrl.speed > 0:
                                await ctrl.set(ctrl.speed)
                    elif seq2 == "C":  # Right -> CCW
                        if ctrl.direction != 1:
                            ctrl.direction = 1
                            if ctrl.speed > 0:
                                await ctrl.set(ctrl.speed)
            else:
                continue

            _print_status(ctrl)

    finally:
        termios.tcsetattr(fd, termios.TCSADRAIN, old_settings)
        print()
