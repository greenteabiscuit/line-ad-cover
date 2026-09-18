package com.greenteabiscuit.lineadcover;

import java.util.Locale;

/** Label matching shared by old and redesigned LINE accessibility trees. */
final class LineUiLabels {
    private LineUiLabels() {}

    static boolean isPromo(String value) {
        String label = normalize(value);
        boolean voom = containsWord(label, "voom");
        return voom && (containsWord(label, "line")
                || containsWord(label, "trending")
                || containsWord(label, "recommended")
                || label.contains("おすすめ")
                || label.contains("話題")
                || label.contains("注目"));
    }

    static boolean isSearch(String value) {
        String label = normalize(value);
        return containsWord(label, "search") || label.contains("検索");
    }

    static boolean isFriendsSubview(String value) {
        String label = normalize(value);
        return containsWord(label, "friends")
                || label.contains("友だち")
                || label.contains("友達");
    }

    /**
     * Returns the tab's position, merging names used by old and redesigned LINE builds.
     * A label naming more than one tab is a shared container rather than a single tab,
     * so it resolves to unknown instead of letting declaration order pick a winner.
     */
    static int topLevelTab(String value) {
        String label = normalize(value);
        int found = -1;
        for (int tab = 0; tab < 5; tab++) {
            if (!namesTab(label, tab)) continue;
            if (found >= 0) return -1;
            found = tab;
        }
        return found;
    }

    private static boolean namesTab(String label, int tab) {
        return switch (tab) {
            case 0 -> containsWord(label, "home") || label.contains("ホーム");
            case 1 -> containsWord(label, "chat")
                    || containsWord(label, "chats")
                    || containsWord(label, "talk")
                    || label.contains("トーク");
            case 2 -> containsWord(label, "voom")
                    || containsWord(label, "shopping")
                    || containsWord(label, "shop")
                    || label.contains("ショッピング");
            case 3 -> containsWord(label, "news") || label.contains("ニュース");
            case 4 -> containsWord(label, "app")
                    || containsWord(label, "apps")
                    || containsWord(label, "wallet")
                    || containsWord(label, "calls")
                    || label.contains("アプリ")
                    || label.contains("ウォレット")
                    || label.contains("通話");
            default -> false;
        };
    }

    static boolean isSelected(String value) {
        String label = normalize(value);
        if (label.contains("not selected")
                || containsWord(label, "unselected")
                || containsWord(label, "deselected")
                || label.contains("未選択")
                || label.contains("非選択")
                || label.contains("選択されていません")) {
            return false;
        }
        return containsWord(label, "selected")
                || label.contains("選択中")
                || label.contains("選択済み");
    }

    private static String normalize(String value) {
        return value == null ? "" : value.toLowerCase(Locale.ROOT);
    }

    private static boolean containsWord(String value, String word) {
        int start = value.indexOf(word);
        while (start >= 0) {
            int end = start + word.length();
            boolean startsAtBoundary = start == 0
                    || !isAsciiLetter(value.charAt(start - 1));
            boolean endsAtBoundary = end == value.length()
                    || !isAsciiLetter(value.charAt(end));
            if (startsAtBoundary && endsAtBoundary) return true;
            start = value.indexOf(word, start + 1);
        }
        return false;
    }

    private static boolean isAsciiLetter(char value) {
        return (value >= 'a' && value <= 'z') || (value >= 'A' && value <= 'Z');
    }
}
