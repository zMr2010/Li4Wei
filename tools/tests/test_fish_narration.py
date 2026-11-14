import contextlib
import importlib.util
import io
import json
import os
from pathlib import Path
import struct
import tempfile
import unittest
from unittest.mock import patch, MagicMock
import urllib.error
import wave

SCRIPT = Path(__file__).resolve().parents[1] / "generate-fish-narration.py"
spec = importlib.util.spec_from_file_location("fish_narration", SCRIPT)
fish = importlib.util.module_from_spec(spec)
spec.loader.exec_module(fish)


class FishNarrationTests(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        self.root = Path(self.temp.name)
        self.output = self.root / "preview"
        self.config = {"voice": "example_voice", "model": "s2.1-pro-free", "direction": "[calm]", "speed": 1.0}

    def clip(self, step, text):
        self.output.mkdir(exist_ok=True)
        audio = b"test-aac-bytes" * 500
        (self.output / (step + ".m4a")).write_bytes(audio)
        return {"id": step, "textSha256": fish.shared.digest(text.encode()),
                "audioSha256": fish.shared.digest(audio), "durationSeconds": 2,
                "requestSha256": fish.request_hash(text, self.config)}

    def test_canonical_parser_accepts_new_groups_and_multi_digit_lessons(self):
        source = self.root / "lessons.kt"
        identifiers = ["l1_plane", "l4_small", "l5_cube", "l6_same", "l12_future"]
        source.write_text("\n".join(
            'LessonStep(id = "' + step + '", title = "观察", narration = "看看这个截面。")'
            for step in identifiers), encoding="utf-8")
        self.assertEqual([step for step, _ in fish.shared.scripts(source)], identifiers)

    def test_request_uses_explicit_free_model_and_does_not_change_subtitles(self):
        opener = MagicMock()
        opener.open.return_value.__enter__.return_value.read.return_value = b"wav-body"
        with patch.object(fish.urllib.request, "build_opener", return_value=opener):
            self.assertEqual(fish.fetch_audio("看看平面。", self.config, "secret-token", 20), b"wav-body")
        request = opener.open.call_args.args[0]
        self.assertEqual(request.full_url, fish.ENDPOINT)
        self.assertEqual(request.get_header("Model"), "s2.1-pro-free")
        body = json.loads(request.data)
        self.assertEqual(body["text"], "[calm] 看看平面。")
        self.assertEqual(body["reference_id"], "example_voice")
        self.assertEqual(body["format"], "wav")
        self.assertEqual(body["language"], "zh-CN")
        self.assertEqual(body["latency"], "normal")

    def test_cache_rejects_changed_voice_model_direction_speed_text_and_audio(self):
        step, text = "l3_closed", "看看平面。"
        clip = self.clip(step, text)
        self.assertTrue(fish.cached(clip, step, text, self.output, self.config))
        for key, value in [("voice", "another"), ("model", "s2-pro"), ("direction", "[curious]"), ("speed", .9)]:
            with self.subTest(key=key):
                self.assertFalse(fish.cached(clip, step, text, self.output, {**self.config, key: value}))
        self.assertFalse(fish.cached(clip, step, text + "新文案。", self.output, self.config))
        (self.output / (step + ".m4a")).write_bytes(b"changed")
        self.assertFalse(fish.cached(clip, step, text, self.output, self.config))

    def test_subset_cannot_target_bundled_assets(self):
        with self.assertRaisesRegex(ValueError, "separate --output"):
            fish.select_entries([("l3_closed", "text")], ["l3_closed"], fish.shared.OUTPUT)
        with self.assertRaisesRegex(ValueError, "Unknown lesson"):
            fish.select_entries([("l3_closed", "text")], ["wrong"], self.output)

    def test_missing_key_does_not_write_or_call_network(self):
        with patch.dict(os.environ, {}, clear=True), patch.object(fish, "fetch_audio") as fetch:
            with self.assertRaisesRegex(ValueError, "Set FISH_API_KEY"):
                fish.main(["--reference-id", "example_voice", "--step", "l3_closed", "--output", str(self.output)])
            fetch.assert_not_called()
        self.assertFalse(self.output.exists())

    def test_http_error_is_redacted_without_retry_or_paid_fallback(self):
        error = urllib.error.HTTPError(fish.ENDPOINT, 402, "secret-in-message", {}, io.BytesIO(b"secret-in-body"))
        opener = MagicMock()
        opener.open.side_effect = error
        with patch.object(fish.urllib.request, "build_opener", return_value=opener):
            with self.assertRaises(RuntimeError) as caught:
                fish.fetch_audio("test", self.config, "secret-key", 20)
        self.assertIn("HTTP 402", str(caught.exception))
        self.assertNotIn("secret", str(caught.exception))
        self.assertEqual(opener.open.call_count, 1)

    def test_wav_stream_placeholder_is_repaired_but_truncation_and_json_are_rejected(self):
        buffer = io.BytesIO()
        with wave.open(buffer, "wb") as audio:
            audio.setparams((1, 2, 44100, 0, "NONE", "not compressed"))
            audio.writeframes(b"\x10\x00" * 22050)
        wav = buffer.getvalue()
        streaming = bytearray(wav)
        struct.pack_into("<I", streaming, 4, 0xFFFFFF24)
        struct.pack_into("<I", streaming, 40, 0xFFFFFF00)
        repaired, duration = fish.validated_wav(bytes(streaming))
        self.assertEqual(duration, .5)
        with wave.open(io.BytesIO(repaired), "rb") as audio:
            self.assertEqual(audio.getnframes(), 22050)
        for invalid in (wav[:-2], b'{"message":"not audio"}'):
            with self.assertRaisesRegex(RuntimeError, "valid, non-silent"):
                fish.validated_wav(invalid)

    def test_preview_check_uses_manifest_without_credentials(self):
        entries = dict(fish.shared.scripts(fish.shared.SOURCE))
        step = "l3_closed"
        clip = self.clip(step, entries[step])
        manifest = {"engine": "Fish Audio", **self.config, "clips": [clip]}
        (self.output / "manifest.json").write_text(json.dumps(manifest))
        with patch.dict(os.environ, {}, clear=True), patch.object(fish, "fetch_audio") as fetch, contextlib.redirect_stdout(io.StringIO()):
            fish.main(["--check", "--step", step, "--output", str(self.output)])
            fetch.assert_not_called()
        fish.shared.check_assets([(step, entries[step])], self.output, manifest)

    def test_dry_run_is_keyless_and_read_only(self):
        result = io.StringIO()
        with patch.dict(os.environ, {}, clear=True), patch.object(fish, "fetch_audio") as fetch, contextlib.redirect_stdout(result):
            fish.main(["--dry-run", "--reference-id", "example_voice", "--step", "l3_closed", "--output", str(self.output)])
        plan = json.loads(result.getvalue())
        self.assertEqual(plan["model"], "s2.1-pro-free")
        self.assertEqual(len(plan["requests"]), 1)
        self.assertFalse(self.output.exists())
        fetch.assert_not_called()

    def test_invalid_audio_preserves_previous_assets_and_manifest(self):
        self.output.mkdir()
        (self.output / "l3_closed.m4a").write_bytes(b"existing-audio")
        original = b'{"engine":"macOS say","clips":[]}'
        (self.output / "manifest.json").write_bytes(original)
        with patch.dict(os.environ, {"FISH_API_KEY": "test-token"}), patch.object(fish.shutil, "which", return_value="tool"), \
             patch.object(fish.sys, "platform", "darwin"), patch.object(fish, "fetch_audio", return_value=b"invalid-audio"):
            with self.assertRaisesRegex(RuntimeError, "valid, non-silent"):
                fish.main(["--reference-id", "example_voice", "--step", "l3_closed", "--output", str(self.output)])
        self.assertEqual((self.output / "l3_closed.m4a").read_bytes(), b"existing-audio")
        self.assertEqual((self.output / "manifest.json").read_bytes(), original)


if __name__ == "__main__":
    unittest.main()
