package com.limelight.utils;

import com.limelight.nvstream.input.KeyboardPacket;
import com.limelight.utils.TextKeyPlanner.Key;
import com.limelight.utils.TextKeyPlanner.Step;
import com.limelight.utils.TextKeyPlanner.Text;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.nio.charset.StandardCharsets;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Tests for the soft keyboard's character-to-keystroke planning.
 *
 * <p>The failure this guards against is the one that prompted the class: text that reaches Steam
 * Big Picture but is invisible to a game, because it went out as Unicode character input rather
 * than as keys. A wrong entry here is quieter than that but the same shape - one character typing
 * something else on the host, or a shift left held down so everything after it arrives capitalised.
 *
 * <p>Ordering carries as much weight as the table. A single commit can mix characters that have
 * keys with characters that do not, and the two travel by different routes; if the plan does not
 * keep them in source order the host assembles the word wrong.
 */
class TextKeyPlannerTest {

    /** Left shift, which the planner presses as a real key rather than only as a modifier bit. */
    private static final int VK_LSHIFT = 0xA0;

    @Nested
    @DisplayName("planText()")
    class PlanText {

        @ParameterizedTest(name = "\"{0}\" -> VK {1}")
        @CsvSource({
                "a, 0x41",
                "z, 0x5A",
                "q, 0x51",
                "0, 0x30",
                "9, 0x39",
        })
        @DisplayName("maps unshifted letters and digits to their virtual key codes")
        void mapsUnshiftedLettersAndDigits(String character, int expected) {
            assertEquals(expected, keyCodeOf(character));
            assertFalse(usesShift(character), "no shift is needed for " + character);
        }

        @Test
        @DisplayName("maps a space to VK_SPACE rather than to the text fallback")
        void mapsSpace() {
            assertEquals(0x20, keyCodeOf(" "));
            assertEquals(2, TextKeyPlanner.planText(" ").size(), "one down, one up, no shift");
        }

        @Test
        @DisplayName("sends Enter and Tab as keys, since neither survives the text path")
        void mapsEnterAndTab() {
            // Enter in a game's chat box is the case that failed silently: as a text event it
            // reached the host as a newline character nothing acted on.
            assertEquals(0x0D, keyCodeOf("\n"));
            assertEquals(0x09, keyCodeOf("\t"));
        }

        @Test
        @DisplayName("sends a capital as the letter's own key with shift, not a different key")
        void capitalsShareTheLetterKey() {
            assertEquals(keyCodeOf("a"), keyCodeOf("A"));
            assertTrue(usesShift("A"));
            assertFalse(usesShift("a"));
        }

        @Test
        @DisplayName("sends a shifted digit as the digit's own key, not as its character's code")
        void shiftedDigitsShareTheDigitKey() {
            // The failure mode is treating '!' as VK 0x21, which is VK_PRIOR - the host would
            // page up instead of typing anything.
            assertEquals(0x31, keyCodeOf("!"));
            assertEquals(keyCodeOf("1"), keyCodeOf("!"));
            assertTrue(usesShift("!"));

            assertEquals(keyCodeOf("2"), keyCodeOf("@"));
            assertEquals(keyCodeOf("6"), keyCodeOf("^"));
            assertEquals(keyCodeOf("9"), keyCodeOf("("));

            // Shift+0 is ')', not '(' - the row wraps at the end and it is an easy one to slip
            assertEquals(keyCodeOf("0"), keyCodeOf(")"));
            assertNotEquals(keyCodeOf(")"), keyCodeOf("("));
        }

        @Test
        @DisplayName("pairs each punctuation character with its unshifted twin on one OEM key")
        void punctuationPairsShareOneKey() {
            // These virtual key codes bear no relation to the characters they produce, so the
            // check that carries meaning is that both halves of a physical key agree.
            String[][] pairs = {
                    {";", ":"}, {"=", "+"}, {",", "<"}, {"-", "_"}, {".", ">"},
                    {"/", "?"}, {"`", "~"}, {"[", "{"}, {"\\", "|"}, {"]", "}"}, {"'", "\""},
            };

            for (String[] pair : pairs) {
                String unshifted = pair[0];
                String shifted = pair[1];

                assertEquals(keyCodeOf(unshifted), keyCodeOf(shifted),
                        unshifted + " and " + shifted + " are one key");
                assertFalse(usesShift(unshifted), unshifted + " is the unshifted half");
                assertTrue(usesShift(shifted), shifted + " is the shifted half");
            }
        }

        @ParameterizedTest(name = "\"{0}\" -> VK {1}")
        @CsvSource({
                "';',  0xBA",
                "'=',  0xBB",
                "'-',  0xBD",
                "'.',  0xBE",
                "'/',  0xBF",
                "'`',  0xC0",
                "'[',  0xDB",
                "']',  0xDD",
        })
        @DisplayName("uses the OEM codes the physical keyboard path already sends")
        void usesTheSameOemCodesAsKeyboardTranslator(String character, int expected) {
            // KeyboardTranslator.translate() maps the equivalent KeyEvent to these. The two have
            // to agree, or the same character types differently depending on which keyboard it
            // came from.
            assertEquals(expected, keyCodeOf(character));
        }

        @Test
        @DisplayName("holds shift across a run of capitals instead of tapping it per letter")
        void coalescesShiftRuns() {
            List<Step> plan = TextKeyPlanner.planText("HELLO");

            assertEquals(1, countShiftTransitions(plan, true), "one shift down for the whole run");
            assertEquals(1, countShiftTransitions(plan, false), "and one shift up at the end");
        }

        @Test
        @DisplayName("releases and re-presses shift around an unshifted character")
        void releasesShiftBetweenRuns() {
            // "Aa A" is shift, letter, no shift, letter, space, shift, letter
            List<Step> plan = TextKeyPlanner.planText("Aa A");

            assertEquals(2, countShiftTransitions(plan, true));
            assertEquals(2, countShiftTransitions(plan, false));
        }

        @Test
        @DisplayName("sets the shift modifier bit on the shifted key as well as pressing shift")
        void setsTheShiftModifierBit() {
            // Belt and braces on purpose: the modifier byte is what the host applies to its own
            // injection, and the real shift key is what a game polling the keyboard can see.
            for (Step step : TextKeyPlanner.planText("A")) {
                if (step instanceof Key key && key.windowsKeyCode() != VK_LSHIFT) {
                    assertEquals(KeyboardPacket.MODIFIER_SHIFT, key.modifiers());
                }
            }
        }

        @Test
        @DisplayName("carries no modifier bits on the shift key itself")
        void shiftKeyCarriesNoModifiers() {
            // Matches GameMenu.sendKeys, where a modifier key is sent before the modifier it
            // establishes is applied to anything.
            for (Step step : TextKeyPlanner.planText("A")) {
                if (step instanceof Key key && key.windowsKeyCode() == VK_LSHIFT) {
                    assertEquals(0, key.modifiers());
                }
            }
        }

        @Test
        @DisplayName("leaves no key held down at the end of a plan")
        void leavesNoKeyHeld() {
            // A stuck shift is the worst outcome available here: everything the user types
            // afterwards, and every gamepad button, reaches the host modified.
            for (String text : new String[]{"HELLO", "Hi!", "a", "!", "café", "😀A"}) {
                assertTrue(pressedKeysBalance(TextKeyPlanner.planText(text)),
                        "every key is released in \"" + text + "\"");
            }
        }

        @Test
        @DisplayName("pairs every key down with an up at the same key")
        void pairsEveryDownWithAnUp() {
            List<Step> plan = TextKeyPlanner.planText("Hi!");

            Integer pending = null;
            for (Step step : plan) {
                if (!(step instanceof Key key)) {
                    continue;
                }
                if (key.pressed()) {
                    pending = key.windowsKeyCode();
                }
                else if (key.windowsKeyCode() != VK_LSHIFT) {
                    // Shift spans the keys it modifies, so only the plain keys close immediately
                    assertEquals(pending, key.windowsKeyCode(), "up follows its own down");
                }
            }
        }

        @Test
        @DisplayName("falls back to a text event for a character with no key")
        void fallsBackForUnmappableCharacters() {
            // An emoji is a surrogate pair, so this also proves the walk is by code point rather
            // than by char - a per-char walk would emit two broken halves.
            List<Step> plan = TextKeyPlanner.planText("😀");

            assertEquals(1, plan.size());
            assertEquals(new Text("😀"), plan.get(0));
        }

        @Test
        @DisplayName("keeps keystrokes and fallback text in the order the characters appeared")
        void preservesSourceOrder() {
            // "café ok": three keys, then the accented character by text, then the rest by key.
            // Emitting the text last would put the host's cursor in the wrong place.
            List<Step> plan = TextKeyPlanner.planText("café ok");

            int textIndex = indexOfFirstText(plan);
            assertNotEquals(-1, textIndex, "the accented character has no key, so it goes as text");

            assertEquals(6, textIndex, "c, a and f are three down/up pairs ahead of it");
            assertEquals(new Text("é"), plan.get(textIndex));

            // And the space after it is a key again, so the text did not swallow the tail
            assertTrue(plan.get(textIndex + 1) instanceof Key key && key.windowsKeyCode() == 0x20);
        }

        @Test
        @DisplayName("releases shift before handing anything to the text path")
        void releasesShiftBeforeFallingBack() {
            // The host applies the modifier byte to its own injection, so text sent while we
            // still held shift would arrive capitalised.
            List<Step> plan = TextKeyPlanner.planText("Aé");

            int textIndex = indexOfFirstText(plan);
            assertEquals(0, countShiftTransitions(plan.subList(textIndex, plan.size()), false),
                    "shift is already up by the time the text goes");
        }

        @Test
        @DisplayName("splits fallback text without ever cutting a UTF-8 sequence")
        void neverSplitsAMultiByteSequence() {
            // Three two-byte characters against a three-byte limit: the naive split lands in the
            // middle of the second one and both halves reach the host as mojibake.
            List<Step> plan = TextKeyPlanner.planText("ééé", 3);

            assertEquals(3, plan.size(), "one chunk per character, not one per three bytes");
            for (Step step : plan) {
                Text text = (Text) step;
                assertEquals("é", text.text());
                assertEquals(2, text.text().getBytes(StandardCharsets.UTF_8).length);
            }
        }

        @Test
        @DisplayName("emits one chunk when the text fits the limit")
        void emitsOneChunkWhenItFits() {
            List<Step> plan = TextKeyPlanner.planText("éé", 512);

            assertEquals(1, plan.size());
            assertEquals(new Text("éé"), plan.get(0));
        }

        @Test
        @DisplayName("plans nothing for empty text")
        void plansNothingForEmptyText() {
            assertEquals(List.of(), TextKeyPlanner.planText(""));
        }
    }

    @Nested
    @DisplayName("planDeletion()")
    class PlanDeletion {

        @Test
        @DisplayName("sends one backspace down and up per character")
        void sendsOneBackspacePerCharacter() {
            List<Step> plan = TextKeyPlanner.planDeletion(3, 0);

            assertEquals(6, plan.size());
            for (Step step : plan) {
                assertEquals(0x08, ((Key) step).windowsKeyCode(), "VK_BACK");
            }
        }

        @Test
        @DisplayName("sends forward deletes after the backspaces")
        void sendsForwardDeletesAfterBackspaces() {
            // afterLength is normally zero - our cursor sits at the end of the buffer - but it
            // used to be dropped silently, which under-deleted whenever an IME did use it.
            List<Step> plan = TextKeyPlanner.planDeletion(1, 1);

            assertEquals(4, plan.size());
            assertEquals(0x08, ((Key) plan.get(0)).windowsKeyCode());
            assertEquals(0x2E, ((Key) plan.get(2)).windowsKeyCode(), "VK_DELETE");
        }

        @Test
        @DisplayName("plans nothing when there is nothing to delete")
        void plansNothingForZero() {
            assertEquals(List.of(), TextKeyPlanner.planDeletion(0, 0));
        }

        @Test
        @DisplayName("plans nothing for negative counts rather than looping forever")
        void plansNothingForNegativeCounts() {
            assertEquals(List.of(), TextKeyPlanner.planDeletion(-1, -1));
        }

        @Test
        @DisplayName("leaves no key held down")
        void leavesNoKeyHeld() {
            assertTrue(pressedKeysBalance(TextKeyPlanner.planDeletion(2, 2)));
        }
    }

    /** @return the virtual key code of the first key in the plan that is not shift */
    private static int keyCodeOf(String text) {
        for (Step step : TextKeyPlanner.planText(text)) {
            if (step instanceof Key key && key.windowsKeyCode() != VK_LSHIFT) {
                return key.windowsKeyCode();
            }
        }
        return -1;
    }

    /** @return true if the plan presses shift at any point */
    private static boolean usesShift(String text) {
        return countShiftTransitions(TextKeyPlanner.planText(text), true) > 0;
    }

    /** @return how many times shift is pressed, or released, across the plan */
    private static int countShiftTransitions(List<Step> plan, boolean pressed) {
        int count = 0;
        for (Step step : plan) {
            if (step instanceof Key key && key.windowsKeyCode() == VK_LSHIFT && key.pressed() == pressed) {
                count++;
            }
        }
        return count;
    }

    /** @return true if every key the plan presses is released by the end of it */
    private static boolean pressedKeysBalance(List<Step> plan) {
        int held = 0;
        for (Step step : plan) {
            if (step instanceof Key key) {
                held += key.pressed() ? 1 : -1;
            }
        }
        return held == 0;
    }

    /** @return the index of the first text event in the plan, or -1 if it is all keystrokes */
    private static int indexOfFirstText(List<Step> plan) {
        for (int i = 0; i < plan.size(); i++) {
            if (plan.get(i) instanceof Text) {
                return i;
            }
        }
        return -1;
    }
}
