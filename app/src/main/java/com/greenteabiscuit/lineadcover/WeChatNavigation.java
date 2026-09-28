package com.greenteabiscuit.lineadcover;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/** Recognizes WeChat's four-tab row without relying on version-specific view IDs. */
final class WeChatNavigation {
    private WeChatNavigation() {}

    record Tab(int index, AdRowGeometry.Box bounds) {}

    /** Read individual node labels, never text aggregated from a whole screen. */
    static int tabIndex(CharSequence value) {
        if (value == null) return -1;
        return switch (value.toString().trim().toLowerCase(Locale.ROOT)) {
            case "chats", "wechat", "微信" -> 0;
            case "contacts", "通讯录", "通訊錄" -> 1;
            case "discover", "发现", "發現" -> 2;
            case "me", "我" -> 3;
            default -> -1;
        };
    }

    /**
     * Require all four labels in order, aligned within one bottom-navigation ancestor.
     * Missing labels do not justify a fixed screen-bottom mask: that could hide a
     * conversation composer, search results, or another screen without tabs.
     */
    static AdRowGeometry.Box selectContainer(
            List<AdRowGeometry.Box> ancestors,
            List<Tab> tabs,
            AdRowGeometry.Box display,
            float density
    ) {
        List<AdRowGeometry.Box> candidates = new ArrayList<>();
        for (AdRowGeometry.Box ancestor : ancestors) {
            AdRowGeometry.Box row = NavigationGeometry.selectContainer(
                    List.of(ancestor), display, density);
            if (row.height() <= 0) continue;
            for (Tab chats : tabs) {
                if (chats.index() != 0 || !inColumn(chats, row)) continue;
                boolean[] seen = new boolean[4];
                int chatsCenterY = (chats.bounds().top() + chats.bounds().bottom()) / 2;
                for (Tab tab : tabs) {
                    int centerY = (tab.bounds().top() + tab.bounds().bottom()) / 2;
                    if (inColumn(tab, row)
                            && Math.abs(centerY - chatsCenterY) <= Math.round(12f * density)) {
                        seen[tab.index()] = true;
                    }
                }
                if (seen[0] && seen[1] && seen[2] && seen[3]) {
                    candidates.add(row);
                    break;
                }
            }
        }
        return NavigationGeometry.selectContainer(candidates, display, density);
    }

    private static boolean inColumn(Tab tab, AdRowGeometry.Box row) {
        AdRowGeometry.Box bounds = tab.bounds();
        if (bounds.width() <= 0 || bounds.height() <= 0 || !row.contains(bounds)
                || bounds.width() > row.width() / 4) return false;
        float center = ((bounds.left() + bounds.right()) / 2f - row.left()) / row.width();
        return Math.abs(center - (tab.index() + 0.5f) / 4f) <= 0.10f;
    }

    /**
     * Finds the navigation background's upper edge in a cropped bottom strip. Both
     * outer edges must change colour; a uniform strip provides no safe row boundary.
     */
    static int backgroundTop(int[] leftEdge, int[] rightEdge) {
        int bottom = leftEdge.length - 1;
        for (int y = bottom - 1; y >= 0; y--) {
            if (differentColor(leftEdge[y], leftEdge[bottom])
                    && differentColor(rightEdge[y], rightEdge[bottom])) return y + 1;
        }
        return -1;
    }

    private static boolean differentColor(int a, int b) {
        return Math.abs(((a >> 16) & 255) - ((b >> 16) & 255)) > 8
                || Math.abs(((a >> 8) & 255) - ((b >> 8) & 255)) > 8
                || Math.abs((a & 255) - (b & 255)) > 8;
    }

    /** Chats is the first of four sections; the remaining three are covered. */
    static AdRowGeometry.Box hiddenTabs(AdRowGeometry.Box navigation) {
        int left = navigation.left() + Math.round(navigation.width() * 0.25f);
        return new AdRowGeometry.Box(
                left, navigation.top(), navigation.right(), navigation.bottom());
    }
}
