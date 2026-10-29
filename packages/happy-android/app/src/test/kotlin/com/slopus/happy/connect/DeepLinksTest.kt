package com.slopus.happy.connect

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** The links the app opens, as happy-ios `PairingLink` reads them. */
class DeepLinksTest {
    @Test
    fun terminalAndAccountLinksArePairings() {
        assertEquals(DeepLink.Pairing("happy://terminal?abc", isTerminal = true), parseDeepLink("happy://terminal?abc"))
        assertEquals(DeepLink.Pairing("happy:///account?abc", isTerminal = false), parseDeepLink("  happy:///account?abc\n"))
    }

    @Test
    fun directCodesIncludingMalformedOnesNeverFallThroughToRelay() {
        for (text in listOf("happy://direct?invalid", "HAPPY://DIRECT?bad", "happy://direct")) {
            assertEquals(DeepLink.Direct(text), parseDeepLink(text))
        }
    }

    @Test
    fun anyOtherLinkIsNotTheApps() {
        assertNull(parseDeepLink("happy://session/s1"))
        assertNull(parseDeepLink("happy:///new"))
        assertNull(parseDeepLink("https://app.happy.engineering/terminal?abc"))
        assertNull(parseDeepLink("happy://terminal/connect"))
        assertNull(parseDeepLink("not a link"))
    }
}
