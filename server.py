#!/usr/bin/env python3
"""OBS PhoneCam USB receiver and zero-transcode OBS relay (standard library only)."""

from __future__ import annotations

import argparse
import json
import os
import queue
import shutil
import socket
import struct
import subprocess
import sys
import threading
import time
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from pathlib import Path

PHONE_PORT = 8765
HTTP_PORT = 8766
UDP_PORT = 5600
VIDEO_PID = 0x100
PMT_PID = 0x1000
FLAG_KEY_FRAME = 1
FLAG_CODEC_CONFIG = 2
FLAG_MODE_UPDATE = 16


def crc32_mpeg(data: bytes) -> int:
    crc = 0xFFFFFFFF
    for byte in data:
        crc ^= byte << 24
        for _ in range(8):
            crc = ((crc << 1) ^ 0x04C11DB7) & 0xFFFFFFFF if crc & 0x80000000 else (crc << 1) & 0xFFFFFFFF
    return crc


def psi_packet(pid: int, section: bytes, continuity: int) -> bytes:
    payload = b"\x00" + section
    header = bytes((0x47, 0x40 | ((pid >> 8) & 0x1F), pid & 0xFF, 0x10 | (continuity & 0x0F)))
    return header + payload + b"\xFF" * (188 - len(header) - len(payload))


def encode_pts(pts: int) -> bytes:
    pts &= (1 << 33) - 1
    return bytes((
        0x21 | (((pts >> 30) & 0x07) << 1),
        (pts >> 22) & 0xFF,
        0x01 | (((pts >> 15) & 0x7F) << 1),
        (pts >> 7) & 0xFF,
        0x01 | ((pts & 0x7F) << 1),
    ))


def encode_pcr(base: int) -> bytes:
    base &= (1 << 33) - 1
    return bytes((
        (base >> 25) & 0xFF,
        (base >> 17) & 0xFF,
        (base >> 9) & 0xFF,
        (base >> 1) & 0xFF,
        ((base & 1) << 7) | 0x7E,
        0,
    ))


def annex_b(data: bytes) -> bytes:
    """Convert common four-byte AVCC NAL lengths to Annex B when needed."""
    if data.startswith(b"\x00\x00\x00\x01") or data.startswith(b"\x00\x00\x01"):
        return data
    out = bytearray()
    at = 0
    while at + 4 <= len(data):
        size = struct.unpack_from(">I", data, at)[0]
        at += 4
        if size <= 0 or at + size > len(data):
            return data
        out += b"\x00\x00\x00\x01"
        out += data[at:at + size]
        at += size
    return bytes(out) if at == len(data) and out else data


class TsMuxer:
    def __init__(self) -> None:
        self.cc = {0: 0, PMT_PID: 0, VIDEO_PID: 0}
        self.first_pts_us: int | None = None
        self.last_tables_pts = -90_000

    def tables(self) -> list[bytes]:
        pat = bytearray(b"\x00\xB0\x0D\x00\x01\xC1\x00\x00\x00\x01")
        pat += struct.pack(">H", 0xE000 | PMT_PID)
        pat += struct.pack(">I", crc32_mpeg(pat))

        pmt = bytearray(b"\x02\xB0\x12\x00\x01\xC1\x00\x00")
        pmt += struct.pack(">H", 0xE000 | VIDEO_PID)
        pmt += b"\xF0\x00\x1B"
        pmt += struct.pack(">H", 0xE000 | VIDEO_PID)
        pmt += b"\xF0\x00"
        pmt += struct.pack(">I", crc32_mpeg(pmt))

        packets = [psi_packet(0, bytes(pat), self.cc[0]), psi_packet(PMT_PID, bytes(pmt), self.cc[PMT_PID])]
        self.cc[0] = (self.cc[0] + 1) & 15
        self.cc[PMT_PID] = (self.cc[PMT_PID] + 1) & 15
        return packets

    def frame(self, access_unit: bytes, pts_us: int, key_frame: bool) -> bytes:
        if self.first_pts_us is None:
            self.first_pts_us = pts_us
        pts = 90_000 + max(0, pts_us - self.first_pts_us) * 90 // 1000
        packets: list[bytes] = []
        if key_frame or pts - self.last_tables_pts >= 45_000:
            packets.extend(self.tables())
            self.last_tables_pts = pts

        if not access_unit.startswith(b"\x00\x00\x00\x01\x09"):
            access_unit = b"\x00\x00\x00\x01\x09\xF0" + access_unit
        pes = b"\x00\x00\x01\xE0\x00\x00\x80\x80\x05" + encode_pts(pts) + access_unit
        packets.extend(self._packetize_pes(pes, pts, key_frame))
        return b"".join(packets)

    def _packetize_pes(self, pes: bytes, pcr: int, key_frame: bool) -> list[bytes]:
        packets: list[bytes] = []
        offset = 0
        first = True
        while offset < len(pes):
            with_pcr = first
            minimum_adaptation = 8 if with_pcr else 0
            capacity = 184 - minimum_adaptation
            take = min(capacity, len(pes) - offset)
            adaptation_total = minimum_adaptation
            if offset + take == len(pes):
                adaptation_total = 184 - take

            cc = self.cc[VIDEO_PID]
            self.cc[VIDEO_PID] = (cc + 1) & 15
            second = ((VIDEO_PID >> 8) & 0x1F) | (0x40 if first else 0)
            control = 0x30 if adaptation_total else 0x10
            packet = bytearray((0x47, second, VIDEO_PID & 0xFF, control | cc))

            if adaptation_total:
                length = adaptation_total - 1
                packet.append(length)
                if length:
                    flags = (0x40 if key_frame and first else 0) | (0x10 if with_pcr else 0)
                    packet.append(flags)
                    used = 1
                    if with_pcr:
                        packet += encode_pcr(pcr)
                        used += 6
                    packet += b"\xFF" * (length - used)

            packet += pes[offset:offset + take]
            if len(packet) != 188:
                raise RuntimeError(f"internal MPEG-TS packet size error: {len(packet)}")
            packets.append(bytes(packet))
            offset += take
            first = False
        return packets


class Subscriber:
    def __init__(self) -> None:
        self.queue: queue.Queue[bytes] = queue.Queue(maxsize=8)
        self.waiting_for_key = True


class Relay:
    def __init__(self, udp_port: int) -> None:
        self.lock = threading.Lock()
        self.subscribers: set[Subscriber] = set()
        self.udp_target = ("127.0.0.1", udp_port)
        self.udp = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
        self.connected = False
        self.width = self.height = self.fps = 0
        self.measured_fps = self.mbps = 0.0
        self.video_delay_ms: float | None = None
        self.frames = self.bytes = 0
        self.stat_started = time.monotonic()

    def begin_session(self, width: int, height: int, fps: int) -> None:
        self.width, self.height, self.fps = width, height, fps
        self.measured_fps = self.mbps = 0.0
        self.video_delay_ms = None
        self.frames = self.bytes = 0
        self.stat_started = time.monotonic()
        self.connected = True

    def update_mode(self, width: int, height: int, fps: int) -> None:
        """The phone switched lens/settings without reconnecting; refresh reported geometry."""
        self.width, self.height, self.fps = width, height, fps
        print(f"\nCamera switched: {width}x{height} @ {fps} fps")

    def subscribe(self) -> Subscriber:
        sub = Subscriber()
        with self.lock:
            self.subscribers.add(sub)
        return sub

    def unsubscribe(self, sub: Subscriber) -> None:
        with self.lock:
            self.subscribers.discard(sub)

    def publish(self, data: bytes, key_frame: bool) -> None:
        for at in range(0, len(data), 7 * 188):
            try:
                self.udp.sendto(data[at:at + 7 * 188], self.udp_target)
            except OSError:
                pass
        with self.lock:
            subscribers = list(self.subscribers)
        for sub in subscribers:
            if sub.waiting_for_key:
                if not key_frame:
                    continue
                sub.waiting_for_key = False
            try:
                sub.queue.put_nowait(data)
            except queue.Full:
                while True:
                    try:
                        sub.queue.get_nowait()
                    except queue.Empty:
                        break
                sub.waiting_for_key = True

    def frame_stat(self, byte_count: int, video_delay_ms: float | None) -> None:
        self.frames += 1
        self.bytes += byte_count
        if video_delay_ms is not None and 0 <= video_delay_ms <= 2000:
            if self.video_delay_ms is None:
                self.video_delay_ms = video_delay_ms
            else:
                self.video_delay_ms = self.video_delay_ms * 0.9 + video_delay_ms * 0.1
        elapsed = time.monotonic() - self.stat_started
        if elapsed >= 1.0:
            self.measured_fps = self.frames / elapsed
            self.mbps = self.bytes * 8 / elapsed / 1_000_000
            self.frames = self.bytes = 0
            self.stat_started = time.monotonic()
            delay = "  video->PC: n/a" if self.video_delay_ms is None else f"  video->PC: {self.video_delay_ms:4.0f} ms"
            print(f"\rPHONE  {self.width}x{self.height}  {self.measured_fps:5.1f} fps  {self.mbps:4.1f} Mb/s  "
                  f"OBS HTTP clients: {len(self.subscribers)}{delay}   ", end="", flush=True)

    def status(self) -> dict[str, object]:
        return {
            "phone_connected": self.connected,
            "width": self.width,
            "height": self.height,
            "requested_fps": self.fps,
            "measured_fps": round(self.measured_fps, 1),
            "megabits_per_second": round(self.mbps, 1),
            "capture_to_pc_ms": None if self.video_delay_ms is None else round(self.video_delay_ms),
            "udp_obs_url": f"udp://127.0.0.1:{self.udp_target[1]}",
            "http_obs_url": f"http://127.0.0.1:{HTTP_PORT}/stream.ts",
        }


RELAY = Relay(UDP_PORT)


class ObsHttpHandler(BaseHTTPRequestHandler):
    protocol_version = "HTTP/1.1"

    def do_GET(self) -> None:
        self.close_connection = True
        if self.path == "/stream.ts":
            self.stream()
            return
        if self.path == "/status.json":
            body = json.dumps(RELAY.status(), indent=2).encode()
            self.send_response(200)
            self.send_header("Content-Type", "application/json")
        elif self.path == "/":
            body = ("OBS PhoneCam is running. In OBS add Media Source, uncheck Local File, "
                    "Input: udp://127.0.0.1:5600, Input Format: mpegts\n").encode()
            self.send_response(200)
            self.send_header("Content-Type", "text/plain; charset=utf-8")
        else:
            body = b"Not found\n"
            self.send_response(404)
            self.send_header("Content-Type", "text/plain")
        self.send_header("Content-Length", str(len(body)))
        self.send_header("Cache-Control", "no-store")
        self.send_header("Connection", "close")
        self.end_headers()
        self.wfile.write(body)

    def stream(self) -> None:
        sub = RELAY.subscribe()
        try:
            self.close_connection = True
            self.connection.setsockopt(socket.IPPROTO_TCP, socket.TCP_NODELAY, 1)
            self.send_response(200)
            self.send_header("Content-Type", "video/mp2t")
            self.send_header("Cache-Control", "no-store, no-cache, must-revalidate")
            self.send_header("Connection", "close")
            self.end_headers()
            while True:
                chunk = sub.queue.get(timeout=5)
                self.wfile.write(chunk)
                self.wfile.flush()
        except (OSError, TimeoutError, queue.Empty):
            pass
        finally:
            RELAY.unsubscribe(sub)

    def log_message(self, _format: str, *_args: object) -> None:
        return


def find_adb() -> str:
    root = Path(__file__).resolve().parent
    candidates = [
        root / ".tools" / "android-sdk" / "platform-tools" / "adb.exe",
        root / ".tools" / "platform-tools" / "adb.exe",
        root / ".tools" / "android-sdk" / "platform-tools" / "adb",
        root / ".tools" / "platform-tools" / "adb",
    ]
    found = shutil.which("adb")
    if found:
        candidates.append(Path(found))
    for path in candidates:
        if path.is_file():
            return str(path)
    raise FileNotFoundError(
        "adb was not found. Run setup.ps1 on Windows or setup.sh on macOS once to download Android platform-tools.")


def adb_prepare(adb: str) -> str:
    subprocess.run([adb, "start-server"], check=True, capture_output=True)
    output = subprocess.run([adb, "devices"], check=True, text=True, capture_output=True).stdout
    devices = []
    unauthorized = []
    for line in output.splitlines()[1:]:
        fields = line.split()
        if len(fields) >= 2 and fields[1] == "device":
            devices.append(fields[0])
        elif len(fields) >= 2 and fields[1] == "unauthorized":
            unauthorized.append(fields[0])
    if unauthorized:
        raise RuntimeError("Phone is unauthorized. Unlock it and accept the USB debugging prompt.")
    if not devices:
        raise RuntimeError("No USB-debugging phone found. Connect USB and enable Developer options > USB debugging.")
    if len(devices) > 1:
        raise RuntimeError("More than one ADB device is connected; disconnect the extra device.")
    serial = devices[0]
    subprocess.run([adb, "-s", serial, "reverse", f"tcp:{PHONE_PORT}", f"tcp:{PHONE_PORT}"], check=True)
    return serial


def install_apk(adb: str, serial: str) -> None:
    root = Path(__file__).resolve().parent
    apk = root / "OBSPhoneCam.apk"
    if not apk.is_file():
        apk = root / "app" / "build" / "outputs" / "apk" / "release" / "app-release.apk"
    if not apk.is_file():
        raise FileNotFoundError("OBSPhoneCam.apk is missing; run build.ps1 (Windows) or build.sh (macOS) first.")
    subprocess.run([adb, "-s", serial, "install", "-r", str(apk)], check=True)
    print(f"Installed {apk.name}")


def read_exact(stream, size: int) -> bytes:
    data = bytearray()
    while len(data) < size:
        part = stream.read(size - len(data))
        if not part:
            raise EOFError
        data += part
    return bytes(data)


def phone_session(conn: socket.socket, address: tuple[str, int]) -> None:
    muxer = TsMuxer()
    config = b""
    clock_offset_ns: int | None = None
    try:
        conn.setsockopt(socket.IPPROTO_TCP, socket.TCP_NODELAY, 1)
        with conn, conn.makefile("rb") as stream:
            hello = stream.readline(128).decode("ascii", "replace").strip().split()
            hello_received_ns = time.monotonic_ns()
            magic = hello[0]
            # Accept the legacy POCOCAM/* banners too, so phones still running the
            # original PocoCam app (or pre-1.5 builds) keep working with this server.
            if magic in ("PHONECAM/2", "POCOCAM/2") and len(hello) == 5:
                width, height, fps, phone_clock_ns = map(int, hello[1:])
                clock_offset_ns = hello_received_ns - phone_clock_ns
            elif magic in ("PHONECAM/1", "POCOCAM/1") and len(hello) == 4:
                width, height, fps = map(int, hello[1:])
            else:
                raise ValueError("invalid phone handshake")
            RELAY.begin_session(width, height, fps)
            print(f"\nPhone connected: {width}x{height} @ {fps} fps ({address[0]})")
            while True:
                length, pts_us, flags = struct.unpack(">IqI", read_exact(stream, 16))
                if length <= 0 or length > 4 * 1024 * 1024:
                    raise ValueError(f"invalid packet length {length}")
                payload = read_exact(stream, length)
                if flags & FLAG_MODE_UPDATE:
                    # Lens/settings swap; text travels inside an Annex-B filler NAL.
                    if payload[:4] == b"\x00\x00\x00\x01":
                        payload = payload[5:]
                    parts = payload.split()
                    try:
                        if len(parts) >= 3:
                            RELAY.update_mode(int(parts[-3]), int(parts[-2]), int(parts[-1]))
                    except ValueError:
                        pass
                    continue
                payload = annex_b(payload)
                if flags & FLAG_CODEC_CONFIG:
                    config = payload
                    continue
                key_frame = bool(flags & FLAG_KEY_FRAME)
                if key_frame and config:
                    payload = config + payload
                RELAY.publish(muxer.frame(payload, pts_us, key_frame), key_frame)
                video_delay_ms = None
                if clock_offset_ns is not None:
                    video_delay_ms = (time.monotonic_ns() - clock_offset_ns - pts_us * 1000) / 1_000_000
                RELAY.frame_stat(length, video_delay_ms)
    except EOFError:
        pass
    except Exception as error:
        print(f"\nPhone stream error: {error}")
    finally:
        RELAY.connected = False
        print("\nPhone disconnected; waiting for app…")


def open_phone_listener() -> socket.socket:
    listener = socket.socket(socket.AF_INET, socket.SOCK_STREAM)
    try:
        if os.name == "nt" and hasattr(socket, "SO_EXCLUSIVEADDRUSE"):
            listener.setsockopt(socket.SOL_SOCKET, socket.SO_EXCLUSIVEADDRUSE, 1)
        else:
            listener.setsockopt(socket.SOL_SOCKET, socket.SO_REUSEADDR, 1)
        listener.bind(("127.0.0.1", PHONE_PORT))
        listener.listen(1)
        return listener
    except Exception:
        listener.close()
        raise


def phone_server(listener: socket.socket) -> None:
    while True:
        conn, address = listener.accept()
        phone_session(conn, address)


def main() -> int:
    parser = argparse.ArgumentParser(description="OBS PhoneCam USB receiver for OBS Studio")
    parser.add_argument("--install", action="store_true", help="install/reinstall OBSPhoneCam.apk before starting")
    args = parser.parse_args()

    try:
        listener = open_phone_listener()
    except OSError as error:
        print(f"ERROR: OBS PhoneCam server is already running, or localhost port {PHONE_PORT} is unavailable: {error}",
              file=sys.stderr)
        return 1

    try:
        adb = find_adb()
        serial = adb_prepare(adb)
        if args.install:
            install_apk(adb, serial)
        http = ThreadingHTTPServer(("127.0.0.1", HTTP_PORT), ObsHttpHandler)
    except (FileNotFoundError, RuntimeError, OSError, subprocess.CalledProcessError) as error:
        listener.close()
        print(f"ERROR: {error}", file=sys.stderr)
        return 1

    threading.Thread(target=http.serve_forever, name="OBSPhoneCam-http", daemon=True).start()
    print(f"USB device: {serial}")
    print("OBS low-latency input: udp://127.0.0.1:5600   Input Format: mpegts")
    print("OBS reliable fallback: http://127.0.0.1:8766/stream.ts")
    print("Waiting for OBS PhoneCam app…  (Ctrl+C stops the server)")
    try:
        phone_server(listener)
    except KeyboardInterrupt:
        print("\nStopped")
    finally:
        listener.close()
        http.shutdown()
        subprocess.run([adb, "-s", serial, "reverse", "--remove", f"tcp:{PHONE_PORT}"], capture_output=True)
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
