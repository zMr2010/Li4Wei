#!/usr/bin/env python3
"""Generate/check bundled Mandarin speech from the canonical Kotlin lesson scripts.

Generation uses macOS's installed Tingting voice and afconvert; playback needs no
TTS service or network. `--check` is platform-independent and checks source/audio
hashes for either macOS or Fish-generated assets without regenerating them.
No narration text is maintained in this script.
"""
import argparse
import hashlib
import json
import os
from pathlib import Path
import re
import shutil
import subprocess
import sys
import tempfile

ROOT = Path(__file__).resolve().parents[1]
SOURCE = ROOT / "app/src/main/java/com/fourthward/game/core/LessonTutorial.kt"
OUTPUT = ROOT / "app/src/main/assets/narration"
STEP = re.compile(
    r'\bLessonStep\(\s*id\s*=\s*"(?P<id>[a-z][a-z0-9_]*)"\s*,'
    r'(?:(?!\bLessonStep\().)*?\bnarration\s*=\s*"(?P<text>(?:\\.|[^"\\])*)"',
    re.DOTALL,
)


def digest(data: bytes) -> str:
    return hashlib.sha256(data).hexdigest()


def scripts(source: Path) -> list[tuple[str, str]]:
    text = source.read_text(encoding="utf-8")
    found = [(match["id"], json.loads('"' + match["text"] + '"')) for match in STEP.finditer(text)]
    declared = re.findall(r'\bid\s*=\s*"(l[1-9][0-9]*_[a-z0-9_]+)"', text)
    if not found or [step for step, _ in found] != declared:
        raise ValueError("Every lesson id must have one static narration string in LessonTutorial.kt")
    if len({step for step, _ in found}) != len(found):
        raise ValueError("Duplicate narration ids")
    if any(not narration.strip() or "${" in narration for _, narration in found):
        raise ValueError("Narrations must be nonempty static Kotlin strings")
    return found


def run(command: list[str]) -> str:
    result = subprocess.run(command, check=True, capture_output=True, text=True)
    return result.stdout


def valid_entry(entry: dict, step: str, text_hash: str, output: Path) -> bool:
    file = output / (step + ".m4a")
    return (entry.get("id") == step and entry.get("textSha256") == text_hash and file.is_file()
            and file.stat().st_size > 4096 and entry.get("audioSha256") == digest(file.read_bytes())
            and entry.get("durationSeconds", 0) > 0.25)


def check_assets(entries: list[tuple[str, str]], output: Path, manifest: dict) -> None:
    """Check canonical text and packaged audio independently of the synthesis provider."""
    clips = manifest.get("clips", [])
    old = {clip["id"]: clip for clip in clips}
    expected = {step for step, _ in entries}
    actual = {file.stem for file in output.glob("*.m4a")}
    stale = {step for step, text in entries
             if not valid_entry(old.get(step, {}), step, digest(text.encode()), output)}
    stale |= expected ^ actual
    stale |= expected ^ set(old)
    if stale or len(clips) != len(old):
        raise ValueError("Narration assets are stale or incomplete: " + ", ".join(sorted(stale)))


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--source", type=Path, default=SOURCE)
    parser.add_argument("--output", type=Path, default=OUTPUT)
    parser.add_argument("--voice", default="Tingting")
    parser.add_argument("--rate", type=int, default=170)
    parser.add_argument("--check", action="store_true")
    args = parser.parse_args()
    if not 80 <= args.rate <= 300:
        parser.error("--rate must be between 80 and 300")
    source = args.source.resolve()
    output = args.output.resolve()
    entries = scripts(source)
    manifest_file = output / "manifest.json"
    previous = json.loads(manifest_file.read_text()) if manifest_file.exists() else {}
    if args.check:
        try:
            check_assets(entries, output, previous)
        except ValueError as error:
            raise SystemExit(str(error)) from None
        print(f"Verified {len(entries)} narration clips against canonical text and audio hashes")
        return
    compatible = (previous.get("engine") == "macOS say" and
                  previous.get("voice") == args.voice and previous.get("rate") == args.rate)
    old_entries = {entry["id"]: entry for entry in previous.get("clips", [])} if compatible else {}
    if sys.platform != "darwin" or not shutil.which("say") or not shutil.which("afconvert"):
        raise SystemExit("Generation requires macOS with the selected Chinese voice, say, and afconvert")
    output.mkdir(parents=True, exist_ok=True)
    clips = []
    with tempfile.TemporaryDirectory(prefix="li4wei-narration-") as temporary:
        temporary = Path(temporary)
        for step, narration in entries:
            text_hash = digest(narration.encode())
            old = old_entries.get(step, {})
            if valid_entry(old, step, text_hash, output):
                clips.append(old)
                continue
            text_file = temporary / (step + ".txt")
            aiff = temporary / (step + ".aiff")
            encoded = temporary / (step + ".m4a")
            text_file.write_text(narration, encoding="utf-8")
            run(["say", "-v", args.voice, "-r", str(args.rate), "-f", str(text_file), "-o", str(aiff)])
            run(["afconvert", "-f", "m4af", "-d", "aac", "-b", "64000", str(aiff), str(encoded)])
            info = run(["afinfo", str(encoded)])
            match = re.search(r"estimated duration:\s*([\d.]+)\s*sec", info)
            if not match or float(match[1]) <= 0.25 or "aac" not in info:
                raise RuntimeError(f"Invalid AAC narration generated for {step}: {info}")
            data = encoded.read_bytes()
            os.replace(encoded, output / (step + ".m4a"))
            clip = {"id": step, "textSha256": text_hash, "audioSha256": digest(data),
                    "durationSeconds": round(float(match[1]), 6), "bytes": len(data)}
            clips.append(clip)
            print(f"Generated {step}: {clip['durationSeconds']:.2f}s / {clip['bytes']} bytes", flush=True)
    try:
        source_name = str(source.relative_to(ROOT))
    except ValueError:
        source_name = str(source)
    manifest = {"source": source_name, "engine": "macOS say", "voice": args.voice, "rate": args.rate,
                "format": "AAC-LC, mono, 64 kbit/s, M4A", "clips": clips}
    manifest_file.write_text(json.dumps(manifest, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    print(f"Ready: {len(clips)} offline narration clips")


if __name__ == "__main__":
    main()
