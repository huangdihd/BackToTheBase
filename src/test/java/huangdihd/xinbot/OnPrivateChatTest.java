package huangdihd.xinbot;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class OnPrivateChatTest {
    @Test
    void messageAtLimitIsNotSplit() {
        String message = "x".repeat(100);

        assertEquals(List.of(message), OnPrivateChat.splitMessage(message));
    }

    @Test
    void longMessagePrefersWhitespaceAndKeepsPartsWithinLimit() {
        String message = "word ".repeat(30).trim();

        List<String> parts = OnPrivateChat.splitMessage(message);

        assertTrue(parts.size() > 1);
        assertTrue(parts.stream().allMatch(part -> part.length() <= 100));
        assertEquals(message, String.join(" ", parts));
    }

    @Test
    void longWordIsHardSplit() {
        List<String> parts = OnPrivateChat.splitMessage("x".repeat(205));

        assertEquals(List.of("x".repeat(100), "x".repeat(100), "x".repeat(5)), parts);
    }

    @Test
    void managementReplyLinesAreSplitIndependently() {
        List<String> lines = List.of(
                "first " + "a".repeat(110),
                "second " + "b".repeat(110)
        );

        List<List<String>> splitLines = new ArrayList<>();
        for (String line : lines) {
            splitLines.add(OnPrivateChat.splitMessage(line));
        }

        assertEquals(2, splitLines.size());
        assertTrue(splitLines.get(0).size() > 1);
        assertTrue(splitLines.get(1).size() > 1);
        assertTrue(splitLines.stream().flatMap(List::stream).allMatch(part -> part.length() <= 100));
    }
}
