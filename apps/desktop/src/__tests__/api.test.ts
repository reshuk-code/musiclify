import { describe, expect, it } from "vitest";
import { cleanTitle } from "../api";

describe("cleanTitle", () => {
  it("strips featured artists", () => {
    expect(cleanTitle("One Dance (feat. Wizkid & Kyla)")).toBe("One Dance");
    expect(cleanTitle("Song [ft. Someone]")).toBe("Song");
  });
  it("strips remaster and live suffixes", () => {
    expect(cleanTitle("Hey Jude - Remastered 2015")).toBe("Hey Jude");
    expect(cleanTitle("Song (Live)")).toBe("Song");
  });
  it("leaves plain titles alone", () => {
    expect(cleanTitle("Plain Title")).toBe("Plain Title");
  });
});
