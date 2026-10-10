"""Voice messages on Ubuntu (f0-05/f0-12, like Messages on the Mac): the microphone through GStreamer into an M4A, its
level every 100 ms for the wave – the same ~50 bars 0…31 as on Android (1a682a79). Nothing leaves the computer here;
the recording lies in a private temporary file until it is sent or thrown away."""

import math
import os
import tempfile
import time
from pathlib import Path

import gi

gi.require_version("Gst", "1.0")
from gi.repository import GLib, Gst  # noqa: E402

WAVE_BARS = 50
SOURCE = os.environ.get("LICHAT_MIKROFON", "autoaudiosrc")      # tests: "audiotestsrc is-live=true wave=sine"


def wave(raw):
    """Raw levels (0…32767) → WAVE_BARS bars 0…31: the loudest of each slice, on a square-root scale like the ear."""
    if not raw:
        return []
    slices = []
    for k in range(WAVE_BARS):
        start = k * len(raw) // WAVE_BARS
        end = max(start + 1, (k + 1) * len(raw) // WAVE_BARS)
        part = raw[min(start, len(raw) - 1):min(end, len(raw))]
        slices.append(max(part) if part else 0)
    top = max(max(slices), 1)
    return [min(31, max(1, int(31 * math.sqrt(s / top)))) for s in slices]


class Recorder:
    """start() → the microphone runs; stop() → (bytes, seconds, bars) once the file is closed, or None."""

    def __init__(self):
        Gst.init(None)
        self.pipeline = None
        self.levels = []
        self.started = 0.0
        self.file = None

    @property
    def running(self):
        return self.pipeline is not None

    def start(self):
        if self.pipeline is not None:
            return False
        fd, name = tempfile.mkstemp(prefix="lichat-aufnahme-", suffix=".m4a")      # 0600, only for me
        os.close(fd)
        self.file = Path(name)
        self.levels = []
        try:
            self.pipeline = Gst.parse_launch(
                f"{SOURCE} ! audioconvert ! audioresample ! audio/x-raw,channels=1,rate=44100 "
                "! level interval=100000000 post-messages=true ! voaacenc bitrate=64000 ! mp4mux "
                f"! filesink location=\"{self.file}\"")
        except GLib.Error:
            self.pipeline = None
            self.file.unlink(missing_ok=True)
            return False
        bus = self.pipeline.get_bus()
        bus.add_signal_watch()
        bus.connect("message::element", self.measured)
        if self.pipeline.set_state(Gst.State.PLAYING) == Gst.StateChangeReturn.FAILURE:
            self.pipeline.set_state(Gst.State.NULL)
            self.pipeline = None
            self.file.unlink(missing_ok=True)
            return False
        self.started = time.monotonic()
        return True

    @property
    def seconds(self):
        return time.monotonic() - self.started if self.pipeline is not None else 0.0

    def measured(self, _bus, message):
        structure = message.get_structure()
        if structure is None or structure.get_name() != "level":
            return
        peaks = structure.get_value("peak")          # dB, 0 = loudest
        if peaks:
            self.levels.append(int(32767 * min(1.0, 10 ** (max(peaks) / 20))))

    def stop(self, keep=True):
        """Ends the recording. keep: (bytes, seconds, bars) – None when thrown away, broken or shorter than half a second."""
        pipeline, self.pipeline = self.pipeline, None
        if pipeline is None:
            return None
        seconds = time.monotonic() - self.started
        if keep:
            pipeline.send_event(Gst.Event.new_eos())            # mp4mux writes its index only at the end
            pipeline.get_bus().timed_pop_filtered(5 * Gst.SECOND, Gst.MessageType.EOS | Gst.MessageType.ERROR)
        pipeline.set_state(Gst.State.NULL)
        pipeline.get_bus().remove_signal_watch()
        try:
            data = self.file.read_bytes() if keep and seconds >= 0.5 else b""
        finally:
            self.file.unlink(missing_ok=True)
        bars = wave(self.levels)
        self.levels = []
        return (data, round(seconds, 1), bars) if data else None
