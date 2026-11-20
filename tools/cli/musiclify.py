"""Musiclify: YouTube link -> MP3 + timestamped lyrics.

Lyrics come from LRCLIB (free synced-lyrics database). If a song isn't there,
it falls back to transcribing the audio with Whisper on the CPU.

Usage:
    python musiclify.py <url> [<url> ...]          # download audio + lyrics
    python musiclify.py <url> --no-lyrics          # audio only
    python musiclify.py <url> --no-transcribe      # LRCLIB only, never run Whisper
    python musiclify.py <url> --transcribe         # skip LRCLIB, always use Whisper

Output for each song (in the downloads folder):
    <title>.mp3, <title>.lrc (synced lyrics), .srt, .json, .txt
"""

import argparse
import sys

from download import download_audio
from lyrics import find_lyrics, guess_artist_track, lrc_to_segments


def save_lrclib_lyrics(track: dict, hit: dict):
    from transcribe import _write_outputs  # shared writer for .lrc/.srt/.json/.txt

    duration = track["duration"] or hit.get("duration") or 0
    if hit.get("syncedLyrics"):
        segments = lrc_to_segments(hit["syncedLyrics"], duration)
    else:  # plain lyrics only: no timestamps available
        segments = [{"start": 0, "end": 0, "text": line.strip()}
                    for line in hit["plainLyrics"].splitlines() if line.strip()]
    result = {
        "file": track["path"].name,
        "artist": hit.get("artistName"),
        "track": hit.get("trackName"),
        "duration": duration,
        "source": "lrclib",
        "synced": bool(hit.get("syncedLyrics")),
        "segments": segments,
    }
    _write_outputs(track["path"], result)


def main():
    parser = argparse.ArgumentParser(description="Download YouTube audio and get timestamped lyrics.")
    parser.add_argument("urls", nargs="+", help="YouTube video or playlist URL(s)")
    parser.add_argument("-o", "--output", default="downloads", help="output folder (default: downloads)")
    parser.add_argument("--no-lyrics", action="store_true", help="only download the MP3")
    parser.add_argument("--no-transcribe", action="store_true", help="never fall back to Whisper")
    parser.add_argument("--transcribe", action="store_true", help="skip LRCLIB and always use Whisper")
    parser.add_argument("-m", "--model", help="Whisper model for fallback (default: small)")
    parser.add_argument("-l", "--language", help="language code for Whisper, e.g. en, hi, ne")
    args = parser.parse_args()

    print("== Downloading audio ==")
    tracks = download_audio(args.urls, args.output)
    if not tracks:
        sys.exit("Nothing was downloaded.")

    if not args.no_lyrics:
        print("\n== Getting lyrics ==")
        for t in tracks:
            artist, song = guess_artist_track(t["title"], t["artist"], t["track"], t["uploader"])
            print(f"- {t['path'].name}")

            hit = None
            if not args.transcribe:
                print(f"  Looking up LRCLIB: {artist or '?'} - {song}")
                try:
                    hit = find_lyrics(artist, song, t["duration"])
                except OSError as e:
                    print(f"  LRCLIB request failed: {e}")

            if hit:
                kind = "synced" if hit.get("syncedLyrics") else "plain (no timestamps)"
                print(f"  Found {kind} lyrics: {hit['artistName']} - {hit['trackName']}")
                save_lrclib_lyrics(t, hit)
            elif args.no_transcribe:
                print("  No lyrics found (Whisper fallback disabled).")
            else:
                if not args.transcribe:
                    print("  Not on LRCLIB, transcribing with Whisper (CPU)...")
                from transcribe import transcribe
                transcribe(t["path"], args.model, args.language, device="cpu")
            print()

    print(f"Done. {len(tracks)} track(s) in '{args.output}'.")


if __name__ == "__main__":
    main()
