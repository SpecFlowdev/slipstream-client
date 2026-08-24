package dev.specflow.slipstream

import dev.specflow.slipstream.core.Action
import dev.specflow.slipstream.core.Rule
import dev.specflow.slipstream.core.RuleSet
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * The same cases the desktop client's rule engine is held to, so a rule list
 * that behaves one way there cannot quietly behave another way here.
 */
class RulesTest {

    private fun rule(pattern: String, action: Action) = Rule(pattern, action)

    @Test
    fun `an exact pattern matches only that host`() {
        val set = RuleSet(listOf(rule("example.com", Action.BLOCK)))
        assertEquals(Action.BLOCK, set.decide("example.com"))
        assertEquals(Action.ALLOW, set.decide("a.example.com"))
        assertEquals(Action.ALLOW, set.decide("notexample.com"))
    }

    @Test
    fun `a wildcard matches subdomains but not the domain itself`() {
        val set = RuleSet(listOf(rule("*.example.com", Action.BLOCK)))
        assertEquals(Action.BLOCK, set.decide("a.example.com"))
        assertEquals(Action.BLOCK, set.decide("deep.nested.example.com"))
        assertEquals(Action.ALLOW, set.decide("example.com"))
        // The classic off-by-one: a suffix match alone would block these.
        assertEquals(Action.ALLOW, set.decide("notexample.com"))
        assertEquals(Action.ALLOW, set.decide("evilexample.com"))
    }

    @Test
    fun `the first matching rule wins`() {
        val set = RuleSet(
            listOf(
                rule("good.ads.example", Action.ALLOW),
                rule("*.ads.example", Action.BLOCK),
            )
        )
        assertEquals(Action.ALLOW, set.decide("good.ads.example"))
        assertEquals(Action.BLOCK, set.decide("other.ads.example"))
    }

    @Test
    fun `a bare star blocks everything it reaches`() {
        val set = RuleSet(listOf(rule("keep.example", Action.ALLOW), rule("*", Action.BLOCK)))
        assertEquals(Action.ALLOW, set.decide("keep.example"))
        assertEquals(Action.BLOCK, set.decide("anything.at.all"))
    }

    @Test
    fun `matching ignores case and a trailing dot`() {
        val set = RuleSet(listOf(rule("example.com", Action.BLOCK)))
        assertEquals(Action.BLOCK, set.decide("EXAMPLE.COM"))
        assertEquals(Action.BLOCK, set.decide("example.com."))
        assertEquals(Action.BLOCK, set.decide("ExAmPlE.CoM."))
    }

    @Test
    fun `a disabled rule is skipped without being deleted`() {
        val set = RuleSet(listOf(Rule("example.com", Action.BLOCK, enabled = false)))
        assertEquals(Action.ALLOW, set.decide("example.com"))
    }

    @Test
    fun `nothing matching means allowed`() {
        val set = RuleSet(listOf(rule("blocked.example", Action.BLOCK)))
        assertEquals(Action.ALLOW, set.decide("something.else"))
        assertEquals(Action.ALLOW, RuleSet().decide("anything"))
    }

    @Test
    fun `ip literals match exactly like any other host`() {
        val set = RuleSet(listOf(rule("10.0.0.1", Action.BLOCK)))
        assertEquals(Action.BLOCK, set.decide("10.0.0.1"))
        assertEquals(Action.ALLOW, set.decide("10.0.0.10"))
    }

    @Test
    fun `refusals are counted`() {
        val set = RuleSet(listOf(rule("*", Action.BLOCK)))
        assertEquals(0L, set.blockedCount())
        assertEquals(1L, set.refuse())
        assertEquals(2L, set.refuse())
        assertEquals(2L, set.blockedCount())
    }

    @Test
    fun `replacing the list takes effect immediately`() {
        val set = RuleSet(listOf(rule("example.com", Action.BLOCK)))
        assertEquals(Action.BLOCK, set.decide("example.com"))
        set.replace(emptyList())
        assertEquals(Action.ALLOW, set.decide("example.com"))
    }

    @Test
    fun `validation rejects patterns that would not do what they look like`() {
        assertNull(rule("example.com", Action.BLOCK).validate())
        assertNull(rule("*.example.com", Action.BLOCK).validate())
        assertNull(rule("*", Action.BLOCK).validate())

        for (bad in listOf("", "   ", "ex*ample.com", "*.a*.com", "example.*")) {
            assertNotNull("should reject '$bad'", rule(bad, Action.BLOCK).validate())
        }
    }
}
