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

    /** Returns the tab's position, merging names used by old and redesigned LINE builds. */
    static int topLevelTab(String value) {
        String label = normalize(value);
        if (containsWord(label, "home") || label.contains("ホーム")) return 0;
        if (containsWord(label, "chat")
                || containsWord(label, "chats")
                || containsWord(label, "talk")
                || label.contains("トーク")) return 1;
        if (containsWord(label, "voom")
                || containsWord(label, "shopping")
                || containsWord(label, "shop")
                || label.contains("ショッピング")) return 2;
        if (containsWord(label, "news") || label.contains("ニュース")) return 3;
        if (containsWord(label, "app")
                || containsWord(label, "apps")
                || containsWord(label, "wallet")
                || containsWord(label, "calls")
                || label.contains("アプリ")
                || label.contains("ウォレット")
                || label.contains("通話")) return 4;
        return -1;
    }

    static boolean isSelected(String value) {
        String label = normalize(value);
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
