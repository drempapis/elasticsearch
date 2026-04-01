/*
 * Copyright Elasticsearch B.V. and/or licensed to Elasticsearch B.V. under one
 * or more contributor license agreements. Licensed under the "Elastic License
 * 2.0", the "GNU Affero General Public License v3.0 only", and the "Server Side
 * Public License v 1"; you may not use this file except in compliance with, at
 * your election, the "Elastic License 2.0", the "GNU Affero General Public
 * License v3.0 only", or the "Server Side Public License, v 1".
 */

package org.elasticsearch.common.lucene.search;

import org.apache.lucene.util.BytesRef;
import org.apache.lucene.util.automaton.Automaton;
import org.apache.lucene.util.automaton.ByteRunAutomaton;
import org.elasticsearch.test.ESTestCase;

import java.util.Locale;

import static org.hamcrest.Matchers.containsString;

public class AutomatonQueriesTests extends ESTestCase {

    public void testToCaseInsensitiveChar() {
        int codepoint = randomBoolean() ? randomInt(128) : randomUnicodeOfLength(1).codePointAt(0);
        Automaton automaton = AutomatonQueries.toCaseInsensitiveChar(codepoint);
        assertTrue(automaton.isDeterministic());
        ByteRunAutomaton runAutomaton = new ByteRunAutomaton(automaton);
        BytesRef br = new BytesRef(new String(Character.toChars(codepoint)));
        assertTrue(runAutomaton.run(br.bytes, br.offset, br.length));
        // only codepoints below 128 are converted to a case-insensitive automaton, so only test that for those cases
        if (codepoint <= 128) {
            int altCase = Character.isLowerCase(codepoint) ? Character.toUpperCase(codepoint) : Character.toLowerCase(codepoint);
            br = new BytesRef(new String(Character.toChars(altCase)));
            assertTrue(runAutomaton.run(br.bytes, br.offset, br.length));
        }
    }

    public void testToCaseInsensitiveString() {
        String s = randomAlphaOfLengthBetween(10, 100);
        Automaton automaton = AutomatonQueries.toCaseInsensitiveString(s);
        assertTrue(automaton.isDeterministic());
        ByteRunAutomaton runAutomaton = new ByteRunAutomaton(automaton);
        BytesRef br = new BytesRef(s);
        assertTrue(runAutomaton.run(br.bytes, br.offset, br.length));
        br = new BytesRef(randomBoolean() ? s.toLowerCase(Locale.ROOT) : s.toUpperCase(Locale.ROOT));
        assertTrue(runAutomaton.run(br.bytes, br.offset, br.length));

        // we cannot really upper/lowercase any random unicode string, for details
        // see restrictions in AutomatonQueries.toCaseInsensitiveChar, but we can
        // at least check the original string is accepted
        s = randomRealisticUnicodeOfLengthBetween(10, 100);
        automaton = AutomatonQueries.toCaseInsensitiveString(s);
        runAutomaton = new ByteRunAutomaton(automaton);
        br = new BytesRef(s);
        assertTrue(runAutomaton.run(br.bytes, br.offset, br.length));

        s = randomUnicodeOfLengthBetween(10, 100);
        automaton = AutomatonQueries.toCaseInsensitiveString(s);
        runAutomaton = new ByteRunAutomaton(automaton);
        br = new BytesRef(s);
        assertTrue(runAutomaton.run(br.bytes, br.offset, br.length));
    }

    public void testToCaseInsensitivePrefix() {
        String s = randomAlphaOfLengthBetween(10, 100);
        Automaton automaton = AutomatonQueries.caseInsensitivePrefix(s);
        assertTrue(automaton.isDeterministic());
        ByteRunAutomaton runAutomaton = new ByteRunAutomaton(automaton);
        BytesRef br = new BytesRef(s + randomRealisticUnicodeOfLengthBetween(10, 20));
        assertTrue(runAutomaton.run(br.bytes, br.offset, br.length));
        br = new BytesRef(
            (randomBoolean() ? s.toLowerCase(Locale.ROOT) : s.toUpperCase(Locale.ROOT)) + randomRealisticUnicodeOfLengthBetween(10, 20)
        );
        assertTrue(runAutomaton.run(br.bytes, br.offset, br.length));

        // We cannot uppercase or lowercase any random unicode string.
        // For details see restrictions in AutomatonQueries.toCaseInsensitiveChar.
        // However, we can at least check the original string is accepted here.
        s = randomRealisticUnicodeOfLengthBetween(10, 100);
        automaton = AutomatonQueries.caseInsensitivePrefix(s);
        runAutomaton = new ByteRunAutomaton(automaton);
        br = new BytesRef(s + randomRealisticUnicodeOfLengthBetween(10, 20));
        assertTrue(runAutomaton.run(br.bytes, br.offset, br.length));

        s = randomUnicodeOfLengthBetween(10, 100);
        automaton = AutomatonQueries.caseInsensitivePrefix(s);
        runAutomaton = new ByteRunAutomaton(automaton);
        br = new BytesRef(s + randomRealisticUnicodeOfLengthBetween(10, 20));
        assertTrue(runAutomaton.run(br.bytes, br.offset, br.length));
    }

    public void testValidateRegexRepetitionDepthAllowsSingleQuantifier() {
        assertValidRegex("a+");
        assertValidRegex("a*");
        assertValidRegex("a?");
        assertValidRegex(".*test.*pattern.*");
    }

    public void testValidateRegexRepetitionDepthAllowsTwoConsecutive() {
        assertValidRegex("a++");
        assertValidRegex("a+?");
        assertValidRegex("a*?");
        assertValidRegex("a+*");
        assertValidRegex("a**");
    }

    public void testValidateRegexRepetitionDepthRejectsThreeConsecutive() {
        assertInvalidRegex("a+++");
        assertInvalidRegex("a+*?");
    }

    public void testValidateRegexRepetitionDepthRejectsPathologicalPattern() {
        assertInvalidRegex("(.[^A-Za-z0-9_])?Ben++++++++++++++++++++++++++++++.?");
    }

    public void testValidateRegexRepetitionDepthHandlesEscapes() {
        assertValidRegex("a\\+\\+\\+");
        assertValidRegex("a+\\++");
        assertValidRegex("\\+\\*\\?");
    }

    public void testValidateRegexRepetitionDepthHandlesCharClasses() {
        assertValidRegex("[+*?]+");
        assertValidRegex("[+++]+");
        assertValidRegex("[^+*?]++");
    }

    public void testValidateRegexRepetitionDepthHandlesQuotedStrings() {
        assertValidRegex("\"+++\"a+");
        assertValidRegex("\"***\"b+");
        assertValidRegex("a+\"+++\"b+");
    }

    public void testValidateRegexRepetitionDepthEmptyAndSimplePatterns() {
        assertValidRegex("");
        assertValidRegex("abc");
        assertValidRegex(".");
    }

    public void testValidateRegexRepetitionDepthTrailingBackslash() {
        assertValidRegex("a\\");
    }

    public void testValidateRegexRepetitionDepthResetsOnNonQuantifier() {
        assertValidRegex("a++b++");
        assertValidRegex("a??z**");
    }

    public void testValidateRegexRepetitionDepthUnclosedQuoteOrClass() {
        assertValidRegex("\"+++");
        assertValidRegex("[+++");
    }

    public void testValidateRegexRepetitionDepthNullPattern() {
        expectThrows(NullPointerException.class, () -> AutomatonQueries.validateRegexRepetitionDepth(null, 2));
    }

    public void testValidateRegexRepetitionDepthZeroMaxConsecutiveQuantifiers() {
        assertInvalidRegex("a+", 0);
        assertValidRegex("abc", 0);
    }

    public void testValidateRegexRepetitionDepthNegativeMaxConsecutiveQuantifiers() {
        assertInvalidRegex("a+", -1);
        assertValidRegex("abc", -1);
    }

    public void testValidateRegexRepetitionDepthCustomMaxConsecutiveQuantifiers() {
        assertValidRegex("a+++", 3);
        assertInvalidRegex("a++++", 3);
    }

    private static void assertValidRegex(String pattern) {
        assertValidRegex(pattern, AutomatonQueries.MAX_CONSECUTIVE_REGEX_QUANTIFIERS);
    }

    private static void assertValidRegex(String pattern, int maxConsecutiveQuantifiers) {
        AutomatonQueries.validateRegexRepetitionDepth(pattern, maxConsecutiveQuantifiers);
    }

    private void assertInvalidRegex(String pattern) {
        assertInvalidRegex(pattern, AutomatonQueries.MAX_CONSECUTIVE_REGEX_QUANTIFIERS);
    }

    private void assertInvalidRegex(String pattern, int maxConsecutiveQuantifiers) {
        IllegalArgumentException e = expectThrows(
            IllegalArgumentException.class,
            () -> AutomatonQueries.validateRegexRepetitionDepth(pattern, maxConsecutiveQuantifiers)
        );
        assertThat(e.getMessage(), containsString("consecutive repetition operators"));
    }
}
