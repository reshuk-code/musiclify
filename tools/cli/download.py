"""Download audio (MP3) from YouTube videos or playlists.

Usage:
    python download.py <url> [<url> ...] [-o downloads]

Requires yt-dlp and ffmpeg on PATH.
"""

import argparse
import os
import sys
from pathlib import Path

try:
    import yt_dlp
except ImportError:
    sys.exit("yt-dlp is not installed. Run: pip install -r requirements.txt")


def _progress_hook(d):
    if d["status"] == "downloading":
        pct = d.get("_percent_str", "").strip()
        speed = d.get("_speed_str", "").strip()
        eta = d.get("_eta_str", "").strip()
        print(f"\r  {pct}  {speed}  ETA {eta}   ", end="", flush=True)
    elif d["status"] == "finished":
        print("\r  Download finished, converting to MP3...      ")


_UNWANTED = ("live", "cover", "remix", "karaoke", "instrumental", "reaction", "8d",
             "slowed", "sped up", "nightcore", "lofi", "lo-fi", "tutorial", "1 hour")


def _base_opts() -> dict:
    """Options shared by every yt-dlp call.

    If YouTube starts answering "Sign in to confirm you're not a bot" / HTTP 429,
    either put a Netscape-format cookies.txt next to this file (or point
    MUSICLIFY_COOKIES_FILE at one), or set MUSICLIFY_COOKIES_BROWSER=firefox
    to read that browser's YouTube cookies directly.
    """
    opts = {"quiet": True, "no_warnings": True}
    cookies_file = Path(os.environ.get("MUSICLIFY_COOKIES_FILE", Path(__file__).parent / "cookies.txt"))
    browser = os.environ.get("MUSICLIFY_COOKIES_BROWSER")
    if cookies_file.is_file():
        opts["cookiefile"] = str(cookies_file)
    elif browser:
        opts["cookiesfrombrowser"] = (browser,)
    return opts


def find_youtube_match(artist: str, title: str, duration: float | None = None) -> str | None:
    matches = find_youtube_matches(artist, title, duration)
    return matches[0] if matches else None


def find_youtube_matches(artist: str, title: str, duration: float | None = None) -> list[str]:
    """Search YouTube and return video URLs ranked by how well they match this exact song.

    Ranking: closest duration wins; live/cover/remix/etc. versions are penalised
    unless the requested title itself contains that word; '- Topic' channels
    (YouTube's official audio uploads) get a small bonus.
    """
    query = f"ytsearch8:{artist} - {title} audio"
    with yt_dlp.YoutubeDL({**_base_opts(), "extract_flat": True}) as ydl:
        result = ydl.extract_info(query, download=False) or {}
    entries = [e for e in result.get("entries", []) if e and e.get("id")]
    if not entries:
        return []

    wanted = title.lower()

    def score(e):
        vid_title = (e.get("title") or "").lower()
        vid_dur = e.get("duration")
        s = abs(vid_dur - duration) if (duration and vid_dur) else 30
        s += sum(60 for w in _UNWANTED if w in vid_title and w not in wanted)
        if wanted not in vid_title:
            s += 20
        if (e.get("channel") or e.get("uploader") or "").endswith("- Topic"):
            s -= 10
        return s

    return [f"https://www.youtube.com/watch?v={e['id']}" for e in sorted(entries, key=score)]


def download_audio(urls: list[str], output_dir: Path | str = "downloads",
                   filename: str = "%(title)s", progress=None, raise_errors: bool = False) -> list[dict]:
    """Download each URL as MP3.

    filename: yt-dlp name template without extension (default: the video title).
    progress: optional callback receiving download progress as 0.0-1.0.
    raise_errors: raise yt-dlp's error instead of printing it and moving on.

    Returns one dict per track: path, title, artist, track, duration (seconds).
    artist/track come from YouTube's music metadata and may be None.
    """
    output_dir = Path(output_dir)
    output_dir.mkdir(parents=True, exist_ok=True)

    hooks = [_progress_hook]
    if progress:
        def _report(d):
            total = d.get("total_bytes") or d.get("total_bytes_estimate")
            if d["status"] == "downloading" and total:
                progress(min(d.get("downloaded_bytes", 0) / total, 1.0))
        hooks.append(_report)

    opts = {
        **_base_opts(),
        "format": "bestaudio/best",
        "outtmpl": str(output_dir / f"{filename}.%(ext)s"),
        "noplaylist": len(urls) == 1 and filename != "%(title)s",  # fixed name => single file
        "postprocessors": [
            {"key": "FFmpegExtractAudio", "preferredcodec": "mp3", "preferredquality": "192"},
            {"key": "FFmpegMetadata"},
        ],
        "progress_hooks": hooks,
        "noprogress": True,
        "quiet": True,
        "no_warnings": True,
        "ignoreerrors": True,  # keep going if one playlist item fails
    }

    if raise_errors:
        opts["ignoreerrors"] = False

    files: list[dict] = []
    with yt_dlp.YoutubeDL(opts) as ydl:
        for url in urls:
            info = ydl.extract_info(url, download=True)
            if info is None:
                print(f"  Failed: {url}")
                continue
            entries = info.get("entries") or [info]  # playlist or single video
            for entry in entries:
                if not entry:
                    continue
                for dl in entry.get("requested_downloads", []):
                    path = Path(dl["filepath"])
                    if path.exists():
                        files.append({
                            "path": path,
                            "title": entry.get("title"),
                            "artist": entry.get("artist") or (entry.get("artists") or [None])[0],
                            "track": entry.get("track"),
                            "uploader": entry.get("uploader") or entry.get("channel"),
                            "duration": entry.get("duration"),
                        })
                        print(f"  Saved: {path.name}")
    return files


def main():
    parser = argparse.ArgumentParser(description="Download YouTube audio as MP3.")
    parser.add_argument("urls", nargs="+", help="YouTube video or playlist URL(s)")
    parser.add_argument("-o", "--output", default="downloads", help="output folder (default: downloads)")
    args = parser.parse_args()

    files = download_audio(args.urls, args.output)
    print(f"\nDone. {len(files)} file(s) in {Path(args.output).resolve()}")
    sys.exit(0 if files else 1)


if __name__ == "__main__":
    main()
