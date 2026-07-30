package com.paladex.ex.core

/**
 * Turns raw OCR output from a card photo into the fields we can search on.
 *
 * OCR on a phone photo of a foil card fails in specific, repeatable ways: glare
 * eats characters, `0`/`O` and `1`/`I`/`l` swap freely, and the attack text
 * below the artwork contributes far more lines than the two we want. So rather
 * than trusting any single line, we look for the strong signals — the collector
 * number is a very distinctive shape — and treat the name as a best-effort
 * guess the user can correct.
 */
object CardTextParser {

    /** Words that appear on cards but are never part of a card's name. */
    private val NOISE_WORDS = setOf(
        "basic", "stage", "stage1", "stage2", "restored", "evolves", "from", "hp",
        "weakness", "resistance", "retreat", "cost", "illus", "illus.", "ability",
        "pokemon", "pokémon", "trainer", "energy", "supporter", "item", "stadium",
        "tool", "nintendo", "creatures", "gamefreak", "tpci", "the", "of", "and",
    )

    /**
     * Suffixes that are part of the printed name and must survive cleanup —
     * "Charizard ex" and "Charizard" are different cards with very different
     * prices.
     */
    private val NAME_SUFFIXES = listOf(
        "VMAX", "VSTAR", "V-UNION", "EX", "GX", "V", "BREAK", "PRISM STAR", "LV.X", "TAG TEAM",
    )

    private val UPPERCASE_SUFFIXES = setOf("GX", "V", "VMAX", "VSTAR", "BREAK")

    /**
     * Deliberately loose on the character class: OCR turns digits into letters,
     * so insisting on `\d` here would reject exactly the damaged reads we need
     * to repair. Each side is validated after repair instead.
     */
    private val NUMBER_WITH_TOTAL = Regex("""\b([A-Z0-9]{1,6})\s*[/／]\s*([A-Z0-9]{1,6})\b""")
    private val PROMO_NUMBER = Regex(
        """\b(SWSH|SM|XY|BW|DP|SVP|HGSS)\s*-?\s*(\d{1,3})\b""",
        RegexOption.IGNORE_CASE,
    )
    private val BARE_NUMBER = Regex("""^\d{1,3}$""")

    /**
     * Letter prefixes genuinely printed on cards, as opposed to letters OCR
     * invented. Anything else leading a number token is repaired into digits.
     */
    private val SUBSET_PREFIXES = setOf("TG", "GG", "SV", "RC", "SH", "TR", "GT", "CS", "H")

    private val SET_CODE = Regex(
        """\b(SVI|PAL|OBF|MEW|PAR|PAF|TEF|TWM|SFA|SCR|SSP|PRE|JTG|SVP|BRS|ASR|LOR|SIT|CRZ|SVE)\b""",
    )

    private val HP_WITH_VALUE = Regex("""\b\d{1,3}\s*HP\b""", RegexOption.IGNORE_CASE)
    private val BARE_HP = Regex("""\bHP\b""", RegexOption.IGNORE_CASE)
    private val ENDS_WITH_DAMAGE = Regex("""\d{1,3}\s*[+x×]?$""")
    private val NON_NAME_CHARS = Regex("""[^A-Za-z'\-.\s]""")
    private val WHITESPACE = Regex("""\s+""")
    private val SHORT_ALL_CAPS = Regex("""^[A-Z]{2,4}$""")

    /**
     * OCR routinely reads digits as letters inside an otherwise numeric token.
     * Only applied to strings we already believe are numbers.
     */
    internal fun repairDigits(text: String): String = text
        .replace(Regex("""[Oo]"""), "0")
        .replace(Regex("""[Il|]"""), "1")
        .replace(Regex("""[Ss]"""), "5")
        .replace(Regex("""[Bb]"""), "8")

    internal data class NumberToken(val prefix: String, val digits: String)

    /**
     * Split a number token into its printed prefix and its digits, repairing
     * OCR damage in the digits. Null when the token is not number-shaped at
     * all, which is how a false match on the slash regex gets rejected.
     */
    internal fun normaliseNumberToken(token: String): NumberToken? {
        val upper = token.uppercase()
        val leadingAlpha = Regex("""^[A-Z]+""").find(upper)?.value.orEmpty()

        // A recognised prefix is real and must survive; anything else leading
        // the token is a misread digit ("O5B" is "058").
        val prefix = if (leadingAlpha in SUBSET_PREFIXES) leadingAlpha else ""
        val digits = repairDigits(upper.substring(prefix.length))

        return if (Regex("""^\d{1,3}$""").matches(digits)) NumberToken(prefix, digits) else null
    }

    data class CollectorNumber(val number: String?, val printedTotal: Int?)

    /** Extract "058/197" style numbering, tolerating OCR damage. */
    fun parseCollectorNumber(text: String): CollectorNumber {
        val upper = text.uppercase()

        // Take the first pair where both sides actually look like numbers, so a
        // stray "AND/OR" cannot beat the real collector number.
        for (match in NUMBER_WITH_TOTAL.findAll(upper)) {
            val number = normaliseNumberToken(match.groupValues[1]) ?: continue
            val total = normaliseNumberToken(match.groupValues[2]) ?: continue
            return CollectorNumber(
                number = "${number.prefix}${number.digits}",
                printedTotal = total.digits.toIntOrNull(),
            )
        }

        PROMO_NUMBER.find(upper)?.let {
            return CollectorNumber("${it.groupValues[1].uppercase()}${it.groupValues[2]}", null)
        }

        // Last resort: a bare number alone on a line, which is how the
        // slash-less modern promos and some Japanese prints look.
        upper.split(Regex("""\n+""")).forEach { line ->
            val trimmed = line.trim()
            if (BARE_NUMBER.matches(trimmed)) return CollectorNumber(trimmed, null)
        }

        return CollectorNumber(null, null)
    }

    /** Three-letter set code printed next to the collector number on modern cards. */
    fun parseSetCode(text: String): String? = SET_CODE.find(text.uppercase())?.groupValues?.get(1)

    private fun cleanNameCandidate(line: String): String = line
        // Card names are Latin letters, spaces, hyphens and apostrophes.
        // Anything else on the name line is HP, energy symbols or OCR garbage.
        .replace(NON_NAME_CHARS, " ")
        .replace(WHITESPACE, " ")
        .trim()

    internal fun scoreNameCandidate(candidate: String): Double {
        if (candidate.isEmpty()) return Double.NEGATIVE_INFINITY

        val words = candidate.split(" ").filter { it.isNotEmpty() }
        if (words.isEmpty() || words.size > 5) return Double.NEGATIVE_INFINITY
        if (candidate.count { it.isLetter() } < 3) return Double.NEGATIVE_INFINITY

        // A line made entirely of card furniture ("Retreat Cost", "Weakness") is
        // never the name, however well it scores on shape.
        val noiseCount = words.count { it.lowercase() in NOISE_WORDS }
        if (noiseCount == words.size) return Double.NEGATIVE_INFINITY

        // A short all-caps token is a set code (MEW, OBF), not a name.
        if (SHORT_ALL_CAPS.matches(candidate)) return Double.NEGATIVE_INFINITY

        var score = 0.0

        // Real names are short. Attack text and flavour text are not.
        score += maxOf(0, 20 - candidate.length).toDouble()
        score -= noiseCount * 15.0

        // Title Case is how names are printed; ALL CAPS attack names are not.
        if (candidate.length >= 2 && candidate[0].isUpperCase() && candidate[1].isLowerCase()) {
            score += 8
        }

        // A recognised suffix is a very strong signal we found the name line.
        if (NAME_SUFFIXES.any { candidate.uppercase().endsWith(" $it") }) score += 12

        return score
    }

    /**
     * Pick the most name-shaped line. OCR hands us the whole card, so this is a
     * ranking problem rather than a lookup — every line is scored and the best
     * one wins.
     */
    fun parseCardName(text: String): String? {
        var best: String? = null
        var bestScore = Double.NEGATIVE_INFINITY

        for (rawLine in text.split(Regex("""\n+"""))) {
            val line = rawLine.trim()
            if (line.isEmpty()) continue

            // An attack line ends in its damage value; a name line never does.
            // Checked before cleanup, which strips the digits that show this.
            val endsWithDamage = ENDS_WITH_DAMAGE.containsMatchIn(line)

            // HP has to go before the non-letter strip, since removing the
            // digits first would leave a bare "HP" glued to the name.
            val withoutHp = line.replace(HP_WITH_VALUE, " ").replace(BARE_HP, " ")

            val candidate = cleanNameCandidate(withoutHp)
            val score = scoreNameCandidate(candidate) - if (endsWithDamage) 10 else 0

            if (score > bestScore) {
                bestScore = score
                best = candidate
            }
        }

        if (best == null || bestScore <= 0) return null

        // Normalise suffix casing so "CHARIZARD EX" becomes "Charizard ex",
        // matching how pokemontcg.io stores names.
        return best.split(" ").joinToString(" ") { word ->
            val upper = word.uppercase()
            when {
                upper == "EX" -> "ex"
                upper in UPPERCASE_SUFFIXES -> upper
                else -> word.replaceFirstChar { it.uppercase() }.let {
                    it[0] + it.substring(1).lowercase()
                }
            }
        }
    }

    /** Full parse of an OCR pass over a card image. */
    fun parse(rawText: String, confidence: Float? = null): ScanParse {
        val collector = parseCollectorNumber(rawText)
        return ScanParse(
            name = parseCardName(rawText),
            number = collector.number,
            printedTotal = collector.printedTotal,
            setCode = parseSetCode(rawText),
            rawText = rawText,
            confidence = confidence,
        )
    }
}
