import { describe, expect, it } from "vitest";
import { compareVersions } from "../updates";

describe("compareVersions", () => {
  it("compares numerically, not as text", () => {
    expect(compareVersions("0.10.0", "0.9.3")).toBeGreaterThan(0);
    expect(compareVersions("0.1.1", "0.1.2")).toBeLessThan(0);
  });
  it("treats missing parts as zero", () => {
    expect(compareVersions("0.2", "0.2.0")).toBe(0);
    expect(compareVersions("1", "0.9.9")).toBeGreaterThan(0);
  });
  it("ignores pre-release suffixes", () => {
    expect(compareVersions("0.1.1-debug", "0.1.1")).toBe(0);
  });
});
