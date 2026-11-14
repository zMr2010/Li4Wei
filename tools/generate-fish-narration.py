#!/usr/bin/env python3
"""Generate offline M4A lesson narration with Fish Audio, using canonical Kotlin text.

Set FISH_API_KEY in the environment. No key is stored in the APK or manifest.
Preview: --reference-id VOICE_ID --step l3_closed --output work/fish-preview
Add --dry-run to inspect requests without a key or network access. --check checks
the selected output without credentials. Full generation defaults to APK assets.
The default model is explicitly s2.1-pro-free; errors never retry or select a paid model.
API: https://docs.fish.audio/api-reference/endpoint/openapi-v1/text-to-speech
"""
import argparse
import importlib.util
import io
import json
import math
import os
from pathlib import Path
import re
import shutil
import sys
import tempfile
import urllib.error
import urllib.request
import wave

_spec = importlib.util.spec_from_file_location("lesson_narration", Path(__file__).with_name("generate-lesson-narration.py"))
shared = importlib.util.module_from_spec(_spec)
_spec.loader.exec_module(shared)

ENDPOINT = "https://api.fish.audio/v1/tts"
MODELS = ("s2.1-pro-free", "s2.1-pro", "s2-pro", "drama-3")
MAX_AUDIO_BYTES = 32 * 1024 * 1024


class NoRedirect(urllib.request.HTTPRedirectHandler):
    def redirect_request(self, request, response, code, message, headers, newurl):
        raise RuntimeError("Fish Audio returned an unexpected redirect; request stopped")


def request_data(text: str, config: dict) -> dict:
    return {"text": (config["direction"] + " " + text).strip(),
            "reference_id": config["voice"], "format": "wav", "sample_rate": 44100,
            "latency": "normal", "normalize": True, "language": "zh-CN",
            "prosody": {"speed": config["speed"], "volume": 0}}


def request_hash(text: str, config: dict) -> str:
    return shared.digest(json.dumps({"endpoint": ENDPOINT, "model": config["model"],
                                    "body": request_data(text, config)},
                                   ensure_ascii=False, sort_keys=True).encode())


def fetch_audio(text: str, config: dict, key: str, timeout: float) -> bytes:
    request = urllib.request.Request(ENDPOINT, json.dumps(request_data(text, config), ensure_ascii=False).encode(),
        {"Authorization": "Bearer " + key, "Content-Type": "application/json", "model": config["model"]}, method="POST")
    try:
        with urllib.request.build_opener(NoRedirect()).open(request, timeout=timeout) as response:
            data = response.read(MAX_AUDIO_BYTES + 1)
    except urllib.error.HTTPError as error:
        # Do not print response bodies or request headers: either can contain account data.
        raise RuntimeError(f"Fish Audio HTTP {error.code}; no retry or model fallback was attempted") from None
    except (urllib.error.URLError, TimeoutError, OSError):
        raise RuntimeError("Fish Audio request failed or timed out; no retry was attempted") from None
    if len(data) > MAX_AUDIO_BYTES:
        raise RuntimeError("Fish Audio response exceeds the narration size limit")
    return data


def validated_wav(data: bytes) -> tuple[bytes, float]:
    try:
        with wave.open(io.BytesIO(data), "rb") as audio:
            if (audio.getcomptype(), audio.getnchannels(), audio.getsampwidth(), audio.getframerate()) != ("NONE", 1, 2, 44100):
                raise ValueError("Unexpected WAV format")
            frames = audio.getnframes()
            pcm = audio.readframes(frames)
            # Fish's streamed WAV uses a near-uint32-max placeholder data length.
            # Measure received PCM, then write a real header for offline AAC conversion.
            streaming_header = frames >= 0x7FFF0000
            duration = len(pcm) / (2 * 44100)
            if (not .25 < duration <= 300 or len(pcm) % 2 or not any(pcm) or
                    (len(pcm) != frames * 2 and not streaming_header)):
                raise ValueError("Truncated, silent, or invalid-length WAV")
            normalized = io.BytesIO()
            with wave.open(normalized, "wb") as target:
                target.setparams((1, 2, 44100, 0, "NONE", "not compressed"))
                target.writeframes(pcm)
            return normalized.getvalue(), duration
    except (wave.Error, EOFError, ValueError):
        raise RuntimeError("Fish Audio did not return valid, non-silent mono PCM WAV audio") from None


def config_from(args) -> dict:
    if not args.reference_id or not re.fullmatch(r"[A-Za-z0-9_-]{1,128}", args.reference_id):
        raise ValueError("--reference-id must be a Fish voice ID (not its URL)")
    if len(args.direction) > 256 or not re.fullmatch(r"(?:\[[^\[\]\r\n]{1,120}\]\s*)*", args.direction):
        raise ValueError("--direction accepts only short square-bracket delivery cues, e.g. '[calm]'")
    if not math.isfinite(args.speed) or not .5 <= args.speed <= 2:
        raise ValueError("--speed must be between 0.5 and 2")
    return {"model": args.model, "voice": args.reference_id, "direction": args.direction.strip(), "speed": args.speed}


def select_entries(entries, requested, output):
    if not requested:
        return entries
    if output.resolve() == shared.OUTPUT.resolve():
        raise ValueError("--step requires a separate --output preview directory; bundled assets are protected")
    missing = set(requested) - {step for step, _ in entries}
    if missing:
        raise ValueError("Unknown lesson step: " + ", ".join(sorted(missing)))
    return [(step, text) for step, text in entries if step in requested]


def cached(entry, step, text, output, config):
    return (shared.valid_entry(entry, step, shared.digest(text.encode()), output) and
            entry.get("requestSha256") == request_hash(text, config))


def main(argv=None):
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--source", type=Path, default=shared.SOURCE)
    parser.add_argument("--output", type=Path, default=shared.OUTPUT)
    parser.add_argument("--reference-id")
    parser.add_argument("--model", choices=MODELS, default="s2.1-pro-free")
    parser.add_argument("--direction", default="[calm]")
    parser.add_argument("--speed", type=float, default=1.0)
    parser.add_argument("--timeout", type=float, default=120)
    parser.add_argument("--step", action="append", help="Repeat for several steps; requires a separate --output")
    mode = parser.add_mutually_exclusive_group()
    mode.add_argument("--dry-run", action="store_true")
    mode.add_argument("--check", action="store_true")
    args = parser.parse_args(argv)
    source, output = args.source.resolve(), args.output.resolve()
    entries = select_entries(shared.scripts(source), args.step, output)
    extra = {path.stem for path in output.glob("*.m4a")} - {step for step, _ in entries}
    if extra:
        raise ValueError("Output contains other narration clips; use a separate preview directory")
    manifest_path = output / "manifest.json"
    previous = json.loads(manifest_path.read_text()) if manifest_path.exists() else {}
    if args.check:
        shared.check_assets(entries, output, previous)
        if previous.get("engine") != "Fish Audio":
            raise ValueError("This output was not generated with Fish Audio")
        config = {k: previous[k] for k in ("model", "voice", "direction", "speed")}
        old = {clip["id"]: clip for clip in previous["clips"]}
        if any(not cached(old[step], step, text, output, config) for step, text in entries):
            raise ValueError("Fish Audio request provenance does not match the manifest")
        print(f"Verified {len(entries)} Fish Audio clips, canonical text, audio hashes, and request provenance")
        return
    config = config_from(args)
    if not math.isfinite(args.timeout) or not 1 <= args.timeout <= 180:
        raise ValueError("--timeout must be between 1 and 180 seconds")
    old = {clip["id"]: clip for clip in previous.get("clips", [])} if previous.get("engine") == "Fish Audio" else {}
    pending = [(step, text) for step, text in entries if not cached(old.get(step, {}), step, text, output, config)]
    if args.dry_run:
        print(json.dumps({"endpoint": ENDPOINT, "model": config["model"], "output": str(output),
            "requests": [{"id": step, "cached": (step, text) not in pending,
                          "body": request_data(text, config)} for step, text in entries]}, ensure_ascii=False, indent=2))
        return
    if not pending:
        print(f"Ready: {len(entries)} cached Fish Audio narration clips")
        return
    key = os.environ.get("FISH_API_KEY", "").strip()
    if not key:
        raise ValueError("Set FISH_API_KEY in your local environment before generation; --dry-run needs no key")
    if not key.isascii() or any(character.isspace() for character in key):
        raise ValueError("FISH_API_KEY must be a single ASCII token without whitespace")
    if sys.platform != "darwin" or not all(shutil.which(tool) for tool in ("afconvert", "afinfo")):
        raise ValueError("M4A generation currently requires macOS afconvert and afinfo")
    clips = []
    # Stage all responses before changing the selected output. Failed requests preserve old assets.
    with tempfile.TemporaryDirectory(prefix="li4wei-fish-") as temporary:
        temporary = Path(temporary)
        for step, text in entries:
            if cached(old.get(step, {}), step, text, output, config):
                clips.append(old[step]); continue
            data = fetch_audio(text, config, key, args.timeout)
            data, _ = validated_wav(data)
            wav, encoded = temporary / (step + ".wav"), temporary / (step + ".m4a")
            wav.write_bytes(data)
            shared.run(["afconvert", "-f", "m4af", "-d", "aac", "-b", "128000", str(wav), str(encoded)])
            info = shared.run(["afinfo", str(encoded)])
            duration = re.search(r"estimated duration:\s*([\d.]+)\s*sec", info)
            if not duration or float(duration[1]) <= .25 or "aac" not in info or encoded.stat().st_size <= 4096:
                raise RuntimeError(f"Invalid encoded narration for {step}")
            data = encoded.read_bytes()
            clips.append({"id": step, "textSha256": shared.digest(text.encode()), "audioSha256": shared.digest(data),
                          "requestSha256": request_hash(text, config), "durationSeconds": round(float(duration[1]), 6), "bytes": len(data)})
            print(f"Prepared {step}: {float(duration[1]):.2f}s", flush=True)
        try:
            source_name = str(source.relative_to(shared.ROOT))
        except ValueError:
            source_name = str(source)
        manifest = {"source": source_name, "engine": "Fish Audio", "endpoint": ENDPOINT, **config,
                    "format": "AAC-LC, mono, 128 kbit/s, M4A", "clips": clips}
        output.mkdir(parents=True, exist_ok=True)
        for step, _ in pending:
            # Sibling temporary files keep replacement atomic even when /tmp is on another volume.
            with tempfile.NamedTemporaryFile(dir=output, delete=False) as staged:
                staged.write((temporary / (step + ".m4a")).read_bytes())
                staged_name = staged.name
            os.replace(staged_name, output / (step + ".m4a"))
        with tempfile.NamedTemporaryFile(mode="w", encoding="utf-8", dir=output, delete=False) as staged:
            json.dump(manifest, staged, ensure_ascii=False, indent=2)
            staged.write("\n")
            staged_name = staged.name
        os.replace(staged_name, manifest_path)
    print(f"Ready: {len(clips)} offline Fish Audio narration clips")


if __name__ == "__main__":
    try:
        main()
    except (ValueError, RuntimeError, OSError, KeyError) as error:
        raise SystemExit(str(error)) from None
