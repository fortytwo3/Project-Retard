import type { ScanParse } from "../types";

/**
 * Turn raw OCR output from a card photo into the fields we can search on.
 *
 * OCR on a phone photo of a foil card is noisy in specific, repeatable ways:
 * glare eats characters, `0`/`O` and `1`/`I`/`l` swap freely, and the attack
 * text below the artwork contributes far more lines than the two we actually
 * want. So rather than trusting any single line, we look for the strong
 * signals — the collector number is a very distinctive shape — and treat the
 * name as a best-effort guess that the user can correct.
 */

/** Words that show up on cards but are never part of the card's name. */
const NOISE_WORDS = new Set([
  "basic",
  "stage",
  "stage1",
  "stage2",
  "restored",
  "evolves",
  "from",
  "hp",
  "weakness",
  "resistance",
  "retreat",
  "cost",
  "illus",
  "illus.",
  "ability",
  "pokemon",
  "pokémon",
  "trainer",
  "energy",
  "supporter",
  "item",
  "stadium",
  "tool",
  "nintendo",
  "creatures",
  "gamefreak",
  "tpci",
  "the",
  "of",
  "and",
]);

/**
 * Name suffixes that are part of the printed card name and must survive
 * cleanup, since "Charizard ex" and "Charizard" are different cards with very
 * different prices.
 */
const NAME_SUFFIXES = [
  "VMAX",
  "VSTAR",
  "V-UNION",
  "EX",
  "GX",
  "V",
  "BREAK",
  "PRISM STAR",
  "LV.X",
  "TAG TEAM",
];

/**
 * Collector number, as printed in the bottom corner.
 *
 * Covers plain numbering ("058/197"), the lettered subsets used by Trainer
 * Gallery and Galarian Gallery ("TG12/TG30", "GG04/GG70"), and the
 * slash-less promo format ("SWSH284", "XY182").
 */
/**
 * Deliberately loose on the character class: OCR turns digits into letters, so
 * insisting on `\d` here would reject exactly the damaged reads we need to
 * repair. Each side is validated after repair instead.
 */
const NUMBER_WITH_TOTAL = /\b([A-Z0-9]{1,6})\s*[/／]\s*([A-Z0-9]{1,6})\b/g;
const PROMO_NUMBER = /\b(SWSH|SM|XY|BW|DP|SVP|HGSS)\s*-?\s*(\d{1,3})\b/i;

/**
 * Letter prefixes that are genuinely printed on cards, as opposed to letters
 * OCR invented. Anything else leading a number token gets repaired into digits.
 */
const SUBSET_PREFIXES = new Set(["TG", "GG", "SV", "RC", "SH", "TR", "GT", "CS", "H"]);

/**
 * OCR routinely reads digits as letters inside an otherwise numeric token.
 * Only applied to strings we already believe are numbers.
 */
function repairDigits(text: string): string {
  return text
    .replace(/[Oo]/g, "0")
    .replace(/[Il|]/g, "1")
    .replace(/[Ss]/g, "5")
    .replace(/[Bb]/g, "8");
}

/**
 * Split a number token into its printed prefix and its digits, repairing OCR
 * damage in the digits. Returns null when the token is not number-shaped at
 * all, which is how a false match on the slash regex gets rejected.
 */
function normaliseNumberToken(token: string): { prefix: string; digits: string } | null {
  const upper = token.toUpperCase();
  const leadingAlpha = upper.match(/^[A-Z]+/)?.[0] ?? "";

  // A recognised prefix is real and must survive; anything else leading the
  // token is a misread digit ("O5B" is "058").
  const prefix = SUBSET_PREFIXES.has(leadingAlpha) ? leadingAlpha : "";
  const digits = repairDigits(upper.slice(prefix.length));

  return /^\d{1,3}$/.test(digits) ? { prefix, digits } : null;
}

/** Extract "058/197" style numbering, tolerating OCR damage. */
export function parseCollectorNumber(text: string): {
  number: string | null;
  printedTotal: number | null;
} {
  const upper = text.toUpperCase();

  // Take the first pair where both sides actually look like numbers, so a
  // stray "AND/OR" in the text does not win over the real collector number.
  for (const match of upper.matchAll(NUMBER_WITH_TOTAL)) {
    const number = normaliseNumberToken(match[1]);
    const total = normaliseNumberToken(match[2]);
    if (!number || !total) continue;

    return {
      number: `${number.prefix}${number.digits}`,
      printedTotal: Number.parseInt(total.digits, 10),
    };
  }

  const promo = upper.match(PROMO_NUMBER);
  if (promo) {
    return { number: `${promo[1].toUpperCase()}${promo[2]}`, printedTotal: null };
  }

  // Last resort: a bare number sitting alone on a line, which is how the
  // slash-less modern promos and some Japanese prints look.
  for (const line of upper.split(/\n+/)) {
    const trimmed = line.trim();
    if (/^\d{1,3}$/.test(trimmed)) {
      return { number: trimmed, printedTotal: null };
    }
  }

  return { number: null, printedTotal: null };
}

/** Three-letter set code printed next to the collector number on modern cards. */
export function parseSetCode(text: string): string | null {
  const match = text.toUpperCase().match(/\b(SVI|PAL|OBF|MEW|PAR|PAF|TEF|TWM|SFA|SCR|SSP|PRE|JTG|SVP|BRS|ASR|LOR|SIT|CRZ|SVE)\b/);
  return match ? match[1] : null;
}

function cleanNameCandidate(line: string): string {
  return line
    // Card names are Latin letters, spaces, hyphens and apostrophes. Anything
    // else on the name line is HP, energy symbols or OCR garbage.
    .replace(/[^A-Za-z'\-.\s]/g, " ")
    .replace(/\s+/g, " ")
    .trim();
}

function scoreNameCandidate(candidate: string): number {
  if (!candidate) return -Infinity;

  const words = candidate.split(" ").filter(Boolean);
  if (words.length === 0 || words.length > 5) return -Infinity;

  const letters = (candidate.match(/[A-Za-z]/g) ?? []).length;
  if (letters < 3) return -Infinity;

  // A line made entirely of card furniture ("Retreat Cost", "Weakness") is
  // never the name, however well it scores on shape.
  const noiseCount = words.filter((w) => NOISE_WORDS.has(w.toLowerCase())).length;
  if (noiseCount === words.length) return -Infinity;

  // A short all-caps token is a set code (MEW, OBF) or a stray symbol, not a name.
  if (/^[A-Z]{2,4}$/.test(candidate)) return -Infinity;

  let score = 0;

  // Real names are short. Attack text and flavour text are not.
  score += Math.max(0, 20 - candidate.length);

  score -= noiseCount * 15;

  // Title Case is how names are printed; ALL CAPS attack names are not.
  if (/^[A-Z][a-z]/.test(candidate)) score += 8;

  // A recognised suffix is a very strong signal we found the name line.
  if (NAME_SUFFIXES.some((s) => candidate.toUpperCase().endsWith(` ${s}`))) score += 12;

  return score;
}

/**
 * Pick the most name-shaped line. OCR gives us the whole card, so this is a
 * ranking problem rather than a lookup — we score every line and take the best.
 */
export function parseCardName(text: string): string | null {
  let best: string | null = null;
  let bestScore = -Infinity;

  for (const rawLine of text.split(/\n+/)) {
    const line = rawLine.trim();
    if (!line) continue;

    // An attack line ends in its damage value; a name line never does. Checked
    // before cleanup, which strips the digits that make this visible.
    const endsWithDamage = /\d{1,3}\s*[+x×]?$/.test(line);

    // HP has to go before the non-letter strip, since removing the digits first
    // would leave a bare "HP" glued to the name.
    const withoutHp = line.replace(/\b\d{1,3}\s*HP\b/i, " ").replace(/\bHP\b/i, " ");

    const candidate = cleanNameCandidate(withoutHp);
    const score = scoreNameCandidate(candidate) - (endsWithDamage ? 10 : 0);

    if (score > bestScore) {
      bestScore = score;
      best = candidate;
    }
  }

  if (!best || bestScore <= 0) return null;

  // Normalise the suffix casing so "CHARIZARD EX" becomes "Charizard ex",
  // matching how pokemontcg.io stores names.
  return best
    .split(" ")
    .map((word) => {
      const upper = word.toUpperCase();
      if (upper === "EX") return "ex";
      if (["GX", "V", "VMAX", "VSTAR", "BREAK"].includes(upper)) return upper;
      return word.charAt(0).toUpperCase() + word.slice(1).toLowerCase();
    })
    .join(" ");
}

/** Full parse of an OCR pass over a card image. */
export function parseScan(rawText: string, confidence = 0): ScanParse {
  const { number, printedTotal } = parseCollectorNumber(rawText);

  return {
    name: parseCardName(rawText),
    number,
    printedTotal,
    setCode: parseSetCode(rawText),
    rawText,
    confidence,
  };
}

/** Nothing to search on — the UI drops the user into manual entry. */
export function isEmptyParse(parse: ScanParse): boolean {
  return !parse.name && !parse.number;
}

export const __testing = { repairDigits, scoreNameCandidate, normaliseNumberToken };
