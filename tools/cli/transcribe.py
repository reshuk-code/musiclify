"""Transcribe an audio file into timestamped lyrics/text with faster-whisper.

Usage:
    python transcribe.py song.mp3 [more.mp3 ...] [--model turbo] [--language en]

For each input it writes, next to the audio file:
    <name>.lrc   synced lyrics (music players load this automatically)
    <name>.srt   subtitles
    <name>.json  every segment and every word with start/end times
    <name>.txt   plain text
"""

import argparse
import json
import os
import sys
from pathlib import Path


os.environ.setdefault("HF_HUB_DISABLE_SYMLINKS_WARNING", "1")

try:
    from faster_whisper import WhisperModel
except ImportError:
    sys.exit("faster-whisper is not installed. Run: pip install -r requirements.txt")


# ---------- time formatting ----------

def _ts_srt(t: float) -> str:
    ms = int(round(t * 1000))
    h, ms = divmod(ms, 3_600_000)
    m, ms = divmod(ms, 60_000)
    s, ms = divmod(ms, 1000)
    return f"{h:02}:{m:02}:{s:02},{ms:03}"


def _ts_lrc(t: float) -> str:
    cs = int(round(t * 100))
    m, cs = divmod(cs, 6000)
    s, cs = divmod(cs, 100)
    return f"{m:02}:{s:02}.{cs:02}"


def _ts_short(t: float) -> str:
    m, s = divmod(int(t), 60)
    return f"{m:02}:{s:02}"


# ---------- model loading ----------

_model_cache: dict = {}


def load_model(model_name: str | None, device: str = "auto"):
    """Load Whisper on the GPU if it works, otherwise on the CPU.

    Returns (model, device_used, model_name_used).
    """
    attempts = []
    if device in ("auto", "cuda"):
        attempts.append(("cuda", "float16", model_name or "turbo"))
    if device in ("auto", "cpu"):
        attempts.append(("cpu", "int8", model_name or "small"))

    last_err = None
    for dev, compute, name in attempts:
        key = (dev, name)
        if key in _model_cache:
            return _model_cache[key], dev, name
        try:
            model = WhisperModel(name, device=dev, compute_type=compute)
            if dev == "cuda":
                # CUDA errors (missing cuBLAS/cuDNN) only show up on first use, so test it now.
                import numpy as np
                list(model.transcribe(np.zeros(16000, dtype=np.float32))[0])
            _model_cache[key] = model
            return model, dev, name
        except Exception as e:  # noqa: BLE001 - fall back to the next option
            last_err = e
            if dev == "cuda" and device == "auto":
                print(f"  GPU not usable ({str(e).splitlines()[0][:100]}), falling back to CPU.")
    raise RuntimeError(f"Could not load Whisper model: {last_err}")


# ---------- transcription ----------

def transcribe(audio_path: Path | str, model_name: str | None = None, language: str | None = None,
               device: str = "cpu", vad: bool = True) -> dict:
    """Transcribe one file, write .lrc/.srt/.json/.txt next to it, and return the result."""
    audio_path = Path(audio_path)
    model, dev, name = load_model(model_name, device)
    print(f"  Transcribing {audio_path.name} with '{name}' on {dev.upper()}...")

    segments_iter, info = model.transcribe(
        str(audio_path),
        language=language,
        word_timestamps=True,
        vad_filter=vad,
        condition_on_previous_text=False,  # stops repeated-line loops on songs
        beam_size=5,
    )
    print(f"  Language: {info.language} ({info.language_probability:.0%}), length {_ts_short(info.duration)}")

    segments = []
    for seg in segments_iter:
        text = seg.text.strip()
        if not text:
            continue
        segments.append({
            "start": round(seg.start, 2),
            "end": round(seg.end, 2),
            "text": text,
            "words": [
                {"start": round(w.start, 2), "end": round(w.end, 2), "word": w.word.strip(),
                 "probability": round(w.probability, 3)}
                for w in (seg.words or [])
            ],
        })
        print(f"    [{_ts_short(seg.start)} -> {_ts_short(seg.end)}] {text}")

    result = {
        "file": audio_path.name,
        "language": info.language,
        "duration": round(info.duration, 2),
        "model": name,
        "segments": segments,
    }
    _write_outputs(audio_path, result)
    return result


def _write_outputs(audio_path: Path, result: dict):
    base = audio_path.with_suffix("")
    segs = result["segments"]

    lrc = [f"[ti:{audio_path.stem}]", f"[length:{_ts_short(result['duration'])}]", ""]
    lrc += [f"[{_ts_lrc(s['start'])}]{s['text']}" for s in segs]
    base.with_suffix(".lrc").write_text("\n".join(lrc) + "\n", encoding="utf-8")

    srt = []
    for i, s in enumerate(segs, 1):
        srt.append(f"{i}\n{_ts_srt(s['start'])} --> {_ts_srt(s['end'])}\n{s['text']}\n")
    base.with_suffix(".srt").write_text("\n".join(srt), encoding="utf-8")

    base.with_suffix(".json").write_text(json.dumps(result, ensure_ascii=False, indent=2), encoding="utf-8")
    base.with_suffix(".txt").write_text("\n".join(s["text"] for s in segs) + "\n", encoding="utf-8")

    print(f"  Wrote {base.name}.lrc / .srt / .json / .txt")


def main():
    parser = argparse.ArgumentParser(description="Transcribe audio to timestamped lyrics/text.")
    parser.add_argument("files", nargs="+", help="audio file(s) to transcribe")
    parser.add_argument("-m", "--model", help="Whisper model: tiny, base, small, medium, large-v3, turbo "
                                              "(default: turbo on GPU, small on CPU)")
    parser.add_argument("-l", "--language", help="language code, e.g. en, hi, ne (default: auto-detect)")
    parser.add_argument("--device", choices=["auto", "cuda", "cpu"], default="cpu")
    parser.add_argument("--no-vad", action="store_true", help="don't skip silent/instrumental parts")
    args = parser.parse_args()

    for f in args.files:
        transcribe(f, args.model, args.language, args.device, vad=not args.no_vad)


if __name__ == "__main__":
    main()
