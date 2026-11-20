"""Fetch synced (timestamped) lyrics from LRCLIB, a free lyrics database. No API key needed.

Usage:
    python lyrics.py "Artist" "Song title" [--duration 215] [-o out.lrc]
    python lyrics.py --search "artist song"

https://lrclib.net/docs
"""

import argparse
import json
import re
import sys
import urllib.parse
import urllib.request

API = "https://lrclib.net/api"
HEADERS = {"User-Agent": "Musiclify/0.1"}

# Junk YouTube adds to titles: "(Official Video)", "[Lyrics]", "| 4K", etc.
_NOISE = re.compile(
    r"[\(\[][^\)\]]*(official|video|audio|lyric|lyrics|visuali[sz]er|mv|hd|4k|remaster|live|explicit)[^\)\]]*[\)\]]"
    r"|\|.*$|\bofficial (music )?video\b|\blyrics?\b",
    re.IGNORECASE,
)
_LRC_LINE = re.compile(r"\[(\d+):(\d+(?:\.\d+)?)\](.*)")


def _get(path: str, **params):
    url = f"{API}/{path}?" + urllib.parse.urlencode({k: v for k, v in params.items() if v})
    req = urllib.request.Request(url, headers=HEADERS)
    try:
        with urllib.request.urlopen(req, timeout=15) as r:
            return json.load(r)
    except urllib.error.HTTPError as e:
        if e.code == 404:
            return None
        raise


def clean_title(title: str) -> str:
    title = _NOISE.sub("", title)
    title = re.sub(r"\s*(ft\.?|feat\.?)\s.*$", "", title, flags=re.IGNORECASE)  # drop "feat. X"
    return re.sub(r"\s{2,}", " ", title).strip(" -–—")


def guess_artist_track(title: str, artist: str | None = None, track: str | None = None,
                       uploader: str | None = None) -> tuple[str | None, str]:
    """Work out (artist, song) from YouTube metadata or a title like 'Artist - Song (Official Video)'."""
    if artist and track:
        return artist, clean_title(track)
    parts = re.split(r"\s+[-–—]\s+", title, maxsplit=1)
    if len(parts) == 2:
        return clean_title(parts[0]), clean_title(parts[1])
    uploader = re.sub(r"\s*(- Topic|VEVO|Official)$", "", uploader or "", flags=re.IGNORECASE).strip()
    return (artist or uploader or None), clean_title(track or title)


def find_lyrics(artist: str | None, track: str, duration: float | None = None) -> dict | None:
    """Return the best LRCLIB match (prefers synced lyrics), or None."""
    # 1) exact lookup (best when artist + duration are known)
    if artist:
        hit = _get("get", artist_name=artist, track_name=track,
                   duration=int(duration) if duration else None)
        if hit and (hit.get("syncedLyrics") or hit.get("plainLyrics")):
            return hit

    # 2) search, then pick the closest synced result by duration
    results = _get("search", track_name=track, artist_name=artist) or []
    if not results:
        results = _get("search", q=f"{artist or ''} {track}".strip()) or []
    results = [r for r in results if r.get("syncedLyrics") or r.get("plainLyrics")]
    if not results:
        return None

    def score(r):
        synced = 0 if r.get("syncedLyrics") else 1
        dur_gap = abs((r.get("duration") or 0) - duration) if duration else 0
        return (synced, dur_gap)

    best = min(results, key=score)
    if duration and best.get("duration") and abs(best["duration"] - duration) > 20:
        return None  # probably a different version/song
    return best


def lrc_to_segments(lrc: str, total: float | None = None) -> list[dict]:
    """Turn '[mm:ss.xx] line' text into [{start, end, text}]; end = next line's start."""
    lines = []
    for raw in lrc.splitlines():
        m = _LRC_LINE.match(raw.strip())
        if m:
            start = int(m.group(1)) * 60 + float(m.group(2))
            lines.append((start, m.group(3).strip()))
    segs = []
    for i, (start, text) in enumerate(lines):
        end = lines[i + 1][0] if i + 1 < len(lines) else (total or start + 5)
        words = _parse_word_tags(text, end)
        if words:
            text = " ".join(w["word"] for w in words)
        text = _WORD_TAG.sub("", text).strip()
        if text:  # blank lines in LRC are instrumental gaps
            seg = {"start": round(start, 2), "end": round(end, 2), "text": text}
            if words:
                seg["words"] = words
            segs.append(seg)
    return segs


_WORD_TAG = re.compile(r"<(\d+):(\d+(?:\.\d+)?)>")


def _parse_word_tags(text: str, line_end: float) -> list[dict]:
    """Parse enhanced-LRC word timings: '<00:19.25>no <00:19.53>strangers'."""
    parts = _WORD_TAG.split(text)  # [before, m, s, word, m, s, word, ...]
    words = []
    for j in range(1, len(parts) - 2, 3):
        word = parts[j + 2].strip()
        if word:
            words.append({"start": round(int(parts[j]) * 60 + float(parts[j + 1]), 2), "word": word})
    for k, w in enumerate(words):
        w["end"] = words[k + 1]["start"] if k + 1 < len(words) else round(line_end, 2)
    return words


def main():
    parser = argparse.ArgumentParser(description="Get synced lyrics from LRCLIB.")
    parser.add_argument("artist", nargs="?")
    parser.add_argument("track", nargs="?")
    parser.add_argument("--duration", type=float, help="song length in seconds (improves matching)")
    parser.add_argument("--search", help="free-text search instead of artist/track")
    parser.add_argument("-o", "--output", help="save LRC to this file")
    args = parser.parse_args()

    if args.search:
        artist, track = guess_artist_track(args.search)
    elif args.track:
        artist, track = args.artist, args.track
    else:
        parser.error("give ARTIST TRACK or --search")

    hit = find_lyrics(artist, track, args.duration)
    if not hit:
        sys.exit(f"No lyrics found for {artist} - {track}")
    text = hit.get("syncedLyrics") or hit.get("plainLyrics")
    print(f"# {hit['artistName']} - {hit['trackName']} ({'synced' if hit.get('syncedLyrics') else 'plain'})\n")
    print(text)
    if args.output:
        with open(args.output, "w", encoding="utf-8") as f:
            f.write(text + "\n")


if __name__ == "__main__":
    main()
