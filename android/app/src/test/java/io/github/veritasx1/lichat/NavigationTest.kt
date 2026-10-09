package io.github.veritasx1.lichat

import org.junit.Assert.assertEquals
import org.junit.Test

/** Card a35a239e: every page change goes in the direction it leads, like iOS (template LiNotes). */
class NavigationTest {
    @Test
    fun directions() {
        assertEquals(Move.Forward, move(Page.List, Page.Chat("a")))
        assertEquals(Move.Forward, move(Page.Chat("a"), Page.Info("a")))
        assertEquals(Move.Back, move(Page.Info("a"), Page.Chat("a")))
        assertEquals(Move.Back, move(Page.Chat("a"), Page.List))
        assertEquals(Move.Forward, move(Page.List, Page.Settings))
        assertEquals(Move.SheetUp, move(Page.List, Page.New))
        assertEquals(Move.SheetDown, move(Page.New, Page.List))
        assertEquals("aus „Neue Nachricht“ ins neue Gespräch: das Blatt geht nach unten", Move.SheetDown, move(Page.New, Page.Chat("a")))
        assertEquals("Animationen entfernen: nur Überblenden", Move.Fade, move(Page.List, Page.Chat("a"), reduceMotion = true))
    }

    @Test
    fun backLeadsUp() {
        assertEquals(null, parent(Page.List))
        assertEquals(Page.List, parent(Page.Chat("a")))
        assertEquals(Page.Chat("a"), parent(Page.Info("a")))
        assertEquals(Page.List, parent(Page.New))
    }
}
