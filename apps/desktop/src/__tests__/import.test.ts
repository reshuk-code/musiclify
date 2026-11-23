import { describe, expect, it } from "vitest";
import { baseTitle, fromVideo, looksLikeCompilation, parseImportLink } from "../api";

describe("looksLikeCompilation", () => {
  it("spots greatest-hits albums", () => {
    expect(looksLikeCompilation("Curtain Call: The Hits")).toBe(true);
    expect(looksLikeCompilation("The Very Best Of Fleetwood Mac")).toBe(true);
    expect(looksLikeCompilation("Wedding Songs 2020")).toBe(true);
  });
  it("leaves studio albums alone", () => {
    expect(looksLikeCompilation("÷ (Deluxe)")).toBe(false);
    expect(looksLikeCompilation("The Eminem Show")).toBe(false);
    expect(looksLikeCompilation("Whitney")).toBe(false);
  });
});

describe("baseTitle", () => {
  it("matches the same song across releases", () => {
    expect(baseTitle('Lose Yourself (From "8 Mile")')).toBe("loseyourself");
    expect(baseTitle("Stan - Remastered 2009")).toBe("stan");
    expect(baseTitle("Shape Of You [Radio Edit]")).toBe(baseTitle("Shape of You"));
  });
});

describe("parseImportLink", () => {
  it("reads Spotify playlist and album links", () => {
    expect(parseImportLink("https://open.spotify.com/playlist/37i9dQZF1DXcBWIGoYBM5M?si=x"))
      .toEqual({ kind: "spotify", type: "playlist", id: "37i9dQZF1DXcBWIGoYBM5M" });
    expect(parseImportLink("https://open.spotify.com/intl-de/album/4aawyAB9vmqN3uQ7FjRGTy"))
      .toEqual({ kind: "spotify", type: "album", id: "4aawyAB9vmqN3uQ7FjRGTy" });
  });
  it("reads YouTube playlist links", () => {
    expect(parseImportLink("https://music.youtube.com/watch?v=abc&list=RDCLAK5uy_x")).toEqual({ kind: "youtube", listId: "RDCLAK5uy_x" });
  });
  it("rejects other links", () => {
    expect(parseImportLink("https://open.spotify.com/track/123")).toBeNull();
    expect(parseImportLink("https://www.youtube.com/watch?v=abc")).toBeNull();
  });
});

describe("fromVideo", () => {
  it("splits 'Artist - Song (Official Video)'", () => {
    expect(fromVideo("Ed Sheeran - Perfect (Official Music Video)", "Ed Sheeran", 280)).toEqual({ title: "Perfect", artist: "Ed Sheeran", durationSec: 280 });
  });
  it("uses the artist from a '- Topic' channel", () => {
    expect(fromVideo("Perfect", "Ed Sheeran - Topic", 263)).toEqual({ title: "Perfect", artist: "Ed Sheeran", durationSec: 263 });
  });
});
