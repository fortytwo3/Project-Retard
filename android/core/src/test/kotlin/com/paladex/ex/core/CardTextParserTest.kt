package com.paladex.ex.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * The OCR strings below are shaped like real recogniser output from card
 * photos: inconsistent casing, letters substituted for digits, and the
 * surrounding card furniture (HP, stage, illustrator credit) mixed in with the
 * two fields we actually want.
 */
class CardTextParserTest {

    // --- collector number ---

    @Test
    fun `reads a plain collector number`() {
        val result = CardTextParser.parseCollectorNumber("058/197")
        assertEquals("058", result.number)
        assertEquals(197, result.printedTotal)
    }

    @Test
    fun `tolerates spaces around the slash`() {
        val result = CardTextParser.parseCollectorNumber("Illus. Mitsuhiro Arita  4 / 102")
        assertEquals("4", result.number)
        assertEquals(102, result.printedTotal)
    }

    @Test
    fun `repairs letters OCR substituted for digits`() {
        // "O5B" is a misread of "058".
        val result = CardTextParser.parseCollectorNumber("O5B/197")
        assertEquals("058", result.number)
        assertEquals(197, result.printedTotal)
    }

    @Test
    fun `keeps meaningful letter prefixes on gallery subsets`() {
        val result = CardTextParser.parseCollectorNumber("TG12/TG30")
        assertEquals("TG12", result.number)
        assertEquals(30, result.printedTotal)
    }

    @Test
    fun `reads slash-less promo numbering`() {
        val result = CardTextParser.parseCollectorNumber("SWSH284")
        assertEquals("SWSH284", result.number)
        assertNull(result.printedTotal)
    }

    @Test
    fun `falls back to a bare number on its own line`() {
        val result = CardTextParser.parseCollectorNumber("Charizard\n\n144\n")
        assertEquals("144", result.number)
        assertNull(result.printedTotal)
    }

    @Test
    fun `returns nulls when there is nothing number-shaped`() {
        val result = CardTextParser.parseCollectorNumber("Weakness Resistance Retreat")
        assertNull(result.number)
        assertNull(result.printedTotal)
    }

    // --- card name ---

    @Test
    fun `picks the name over surrounding card furniture`() {
        val ocr = "Stage 2\nCharizard 170 HP\nEvolves from Charmeleon\nFire Spin 200"
        assertEquals("Charizard", CardTextParser.parseCardName(ocr))
    }

    @Test
    fun `keeps the ex suffix and normalises its casing`() {
        assertEquals("Charizard ex", CardTextParser.parseCardName("CHARIZARD EX\n330 HP"))
    }

    @Test
    fun `keeps VMAX in caps`() {
        assertEquals("Pikachu VMAX", CardTextParser.parseCardName("Pikachu VMAX\n310 HP"))
    }

    @Test
    fun `strips a trailing HP value from the name line`() {
        assertEquals("Mewtwo", CardTextParser.parseCardName("Mewtwo 130 HP"))
    }

    @Test
    fun `handles multi-word names`() {
        assertEquals("Iron Valiant", CardTextParser.parseCardName("Basic\nIron Valiant\n220 HP"))
    }

    @Test
    fun `returns null when every line is noise`() {
        assertNull(CardTextParser.parseCardName("Weakness\nResistance\nRetreat Cost"))
    }

    // --- set code ---

    @Test
    fun `finds a modern three-letter set code`() {
        assertEquals("OBF", CardTextParser.parseSetCode("058/197 OBF"))
    }

    @Test
    fun `returns null when no known set code is present`() {
        assertNull(CardTextParser.parseSetCode("058/197"))
    }

    // --- whole parse ---

    @Test
    fun `combines every field from a realistic two-region read`() {
        val ocr = "Charizard ex\n330 HP\n\nIllus. 5ban Graphics\n054/165  MEW"
        val parsed = CardTextParser.parse(ocr, 82.4f)

        assertEquals("Charizard ex", parsed.name)
        assertEquals("054", parsed.number)
        assertEquals(165, parsed.printedTotal)
        assertEquals("MEW", parsed.setCode)
        assertEquals(82.4f, parsed.confidence)
    }

    @Test
    fun `reports an empty parse when nothing is legible`() {
        assertEquals(true, CardTextParser.parse("Weakness Resistance").isEmpty)
    }
}
