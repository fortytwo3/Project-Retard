package com.paladex.ex.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class MatchingTest {

    private fun card(name: String = "Charizard ex", number: String = "054") = Card(
        id = "sv3pt5-54",
        name = name,
        number = number,
        printedTotal = 165,
        setId = "sv3pt5",
        setName = "151",
        setSeries = "Scarlet & Violet",
        rarity = "Double Rare",
    )

    @Test
    fun `number comparison ignores leading zeros`() {
        assertEquals(
            PokemonTcgClient.normaliseNumber("54"),
            PokemonTcgClient.normaliseNumber("054"),
        )
    }

    @Test
    fun `number comparison keeps a subset prefix`() {
        assertEquals("TG12", PokemonTcgClient.normaliseNumber("TG012"))
    }

    @Test
    fun `identical strings score 1`() {
        assertEquals(1.0, PokemonTcgClient.similarity("charizard", "charizard"))
    }

    @Test
    fun `similarity stays high through a single OCR error`() {
        assertTrue(PokemonTcgClient.similarity("charizard", "charizerd") > 0.85)
    }

    @Test
    fun `unrelated names score low`() {
        assertTrue(PokemonTcgClient.similarity("charizard", "blastoise") < 0.4)
    }

    @Test
    fun `scores a full match near 1`() {
        val scan = ScanParse(name = "Charizard ex", number = "054", printedTotal = 165)
        assertTrue(PokemonTcgClient.scoreCard(card(), scan).score > 0.95)
    }

    @Test
    fun `still clears the candidate threshold on the number alone`() {
        val scan = ScanParse(number = "054", printedTotal = 165)
        assertTrue(PokemonTcgClient.scoreCard(card(), scan).score > 0.35)
    }

    @Test
    fun `scores a wrong card below the threshold`() {
        val scan = ScanParse(name = "Charizard ex", number = "054")
        val other = card(name = "Blastoise ex", number = "009")
        assertTrue(PokemonTcgClient.scoreCard(other, scan).score < 0.35)
    }

    @Test
    fun `explains why it matched`() {
        val scan = ScanParse(name = "Charizard ex", number = "054", printedTotal = 165)
        val reasons = PokemonTcgClient.scoreCard(card(), scan).reasons

        assertTrue("Exact name match" in reasons)
        assertTrue("Card number 054" in reasons)
    }
}
