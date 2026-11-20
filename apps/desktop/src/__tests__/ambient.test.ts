import { describe, expect, it } from "vitest";
import { pickColors } from "../ambient";

/** An RGBA pixel buffer made of solid colour blocks: [r, g, b, count]. */
const pixels = (...blocks: [number, number, number, number][]) =>
  new Uint8ClampedArray(blocks.flatMap(([r, g, b, n]) => Array.from({ length: n }, () => [r, g, b, 255]).flat()));

describe("pickColors", () => {
  it("prefers vivid colours over dull ones", () => {
    const [first] = pickColors(pixels([200, 30, 40, 50], [120, 120, 120, 80]));
    expect(first).toBe("rgb(200 30 40)");
  });
  it("skips near-black and near-white", () => {
    const colors = pickColors(pixels([5, 5, 5, 500], [250, 250, 250, 500], [30, 90, 200, 10]));
    expect(colors[0]).toBe("rgb(30 90 200)");
  });
  it("always returns three colours, padding with the accent palette", () => {
    expect(pickColors(pixels([0, 0, 0, 10]))).toHaveLength(3);
  });
});
