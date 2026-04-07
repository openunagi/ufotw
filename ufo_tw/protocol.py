"""VORZE U.F.O. TW BLE protocol — 3-byte command builder.

Real packet format (confirmed via HCI snoop log):
  [0x05, (dir1<<7)|speed1, (dir2<<7)|speed2]
  → written to TX_CHAR_UUID (40ee2222) only.
"""

SERVICE_UUID = "40ee1111-63ec-4b7f-8ce7-712efd55b90e"
TX_CHAR_UUID = "40ee2222-63ec-4b7f-8ce7-712efd55b90e"

DEVICE_ID = 0x05  # UFO TW

CW  = 0  # 時計回り
CCW = 1  # 反時計回り


def build_command(speed1: int, dir1: int, speed2: int, dir2: int) -> bytes:
    """Build a 3-byte command controlling both rotors in one packet.

    Args:
        speed1: rotor 1 speed 0-100
        dir1:   rotor 1 direction (CW=0, CCW=1)
        speed2: rotor 2 speed 0-100
        dir2:   rotor 2 direction (CW=0, CCW=1)
    """
    speed1 = max(0, min(100, speed1))
    speed2 = max(0, min(100, speed2))
    return bytes([DEVICE_ID, (dir1 << 7) | speed1, (dir2 << 7) | speed2])


def stop_command() -> bytes:
    return build_command(0, CW, 0, CW)
