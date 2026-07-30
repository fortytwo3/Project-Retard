"use client";

import { createWorker, type Worker } from "tesseract.js";

import { parseScan } from "./parse";
import type { ScanParse } from "../types";

/**
 * Browser-side OCR. Runs entirely on the device — the photo never leaves the
 * phone, only the handful of characters we extract from it get sent to the
 * server.
 */

let workerPromise: Promise<Worker> | null = null;

/** One worker for the session; spinning up Tesseract costs a few seconds. */
async function getWorker(): Promise<Worker> {
  if (!workerPromise) {
    workerPromise = createWorker("eng");
  }
  return workerPromise;
}

export async function warmUpOcr(): Promise<void> {
  try {
    await getWorker();
  } catch {
    // Warm-up is an optimisation; failures surface on the real call.
  }
}

export async function disposeOcr(): Promise<void> {
  if (!workerPromise) return;
  const worker = await workerPromise;
  workerPromise = null;
  await worker.terminate();
}

/**
 * Grayscale plus a contrast stretch.
 *
 * Holofoil cards are the hard case: the foil throws coloured highlights across
 * the name and number, and Tesseract does much better on a high-contrast
 * two-tone image than on the raw photo. Pixels are pushed away from mid-grey,
 * which darkens text and blows out the foil sparkle behind it.
 */
function enhanceForOcr(canvas: HTMLCanvasElement): void {
  const context = canvas.getContext("2d", { willReadFrequently: true });
  if (!context) return;

  const image = context.getImageData(0, 0, canvas.width, canvas.height);
  const { data } = image;

  const CONTRAST = 1.6;

  for (let i = 0; i < data.length; i += 4) {
    // Rec. 601 luma — matches how the eye weights the channels.
    const luma = 0.299 * data[i] + 0.587 * data[i + 1] + 0.114 * data[i + 2];
    const stretched = Math.min(255, Math.max(0, (luma - 128) * CONTRAST + 128));

    data[i] = stretched;
    data[i + 1] = stretched;
    data[i + 2] = stretched;
  }

  context.putImageData(image, 0, 0);
}

/** Fractions of the card frame, as (x, y, width, height). */
interface Region {
  name: string;
  rect: [number, number, number, number];
}

/**
 * The two places worth reading.
 *
 * Reading the whole card wastes time on attack text and flavour text, and gives
 * the name-ranking heuristic dozens of extra lines to be wrong about. The name
 * band and the bottom strip are where the fields we need are printed on every
 * modern layout.
 */
const REGIONS: Region[] = [
  { name: "title", rect: [0.02, 0.02, 0.96, 0.16] },
  { name: "footer", rect: [0.02, 0.83, 0.96, 0.17] },
];

function cropToCanvas(
  source: HTMLCanvasElement,
  [x, y, w, h]: [number, number, number, number],
  scale = 2,
): HTMLCanvasElement {
  const sx = Math.floor(source.width * x);
  const sy = Math.floor(source.height * y);
  const sw = Math.floor(source.width * w);
  const sh = Math.floor(source.height * h);

  const out = document.createElement("canvas");
  // Upscaling before OCR measurably helps on small text.
  out.width = sw * scale;
  out.height = sh * scale;

  const context = out.getContext("2d");
  if (context) {
    context.imageSmoothingQuality = "high";
    context.drawImage(source, sx, sy, sw, sh, 0, 0, out.width, out.height);
    enhanceForOcr(out);
  }

  return out;
}

/**
 * OCR a captured card image and parse it into searchable fields.
 *
 * Both regions are recognised and their text concatenated, then handed to the
 * shared parser, which knows how to pick the name out of one and the collector
 * number out of the other.
 */
export async function scanCardImage(
  canvas: HTMLCanvasElement,
  onProgress?: (message: string) => void,
): Promise<ScanParse> {
  const worker = await getWorker();

  const texts: string[] = [];
  const confidences: number[] = [];

  for (const region of REGIONS) {
    onProgress?.(`Reading ${region.name}…`);
    const crop = cropToCanvas(canvas, region.rect);

    const { data } = await worker.recognize(crop);
    if (data.text.trim()) {
      texts.push(data.text.trim());
      confidences.push(data.confidence);
    }
  }

  const meanConfidence =
    confidences.length > 0 ? confidences.reduce((a, b) => a + b, 0) / confidences.length : 0;

  return parseScan(texts.join("\n"), meanConfidence);
}
