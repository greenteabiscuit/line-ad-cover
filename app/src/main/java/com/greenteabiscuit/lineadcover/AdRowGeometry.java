package com.greenteabiscuit.lineadcover;

import java.util.List;

/** Pure geometry used to select a safe, screen-wide promo row. */
public final class AdRowGeometry {
    private static final float MIN_WIDTH_FRACTION = 0.72f;
    private static final float MAX_HEIGHT_FRACTION = 0.38f;
    private static final float MIN_GAP_HEIGHT_DP = 48f;
    private static final float MAX_GAP_HEIGHT_DP = 240f;
    private static final float FALLBACK_TOP_PADDING_DP = 64f;
    private static final float FALLBACK_BOTTOM_PADDING_DP = 24f;

    private AdRowGeometry() {}

    public record Box(int left, int top, int right, int bottom) {
        public int width() { return Math.max(0, right - left); }
        public int height() { return Math.max(0, bottom - top); }
        public boolean contains(Box other) {
            return left <= other.left && top <= other.top
                    && right >= other.right && bottom >= other.bottom;
        }
    }

    /**
     * Ancestors must be ordered nearest-first. Picks the nearest plausible row while
     * refusing root/window-sized containers. Falls back to density-aware padding.
     */
    public static Box select(Box anchor, List<Box> ancestors, Box display, float density) {
        int minHeight = Math.max(Math.round(48f * density), anchor.height());
        int maxHeight = Math.min(
                Math.round(display.height() * MAX_HEIGHT_FRACTION),
                Math.round(240f * density));
        int minWidth = Math.round(display.width() * MIN_WIDTH_FRACTION);

        for (Box candidate : ancestors) {
            Box clipped = clamp(candidate, display);
            if (!candidate.contains(anchor)
                    || clipped.width() < minWidth
                    || clipped.height() < minHeight
                    || clipped.height() > maxHeight) {
                continue;
            }
            return new Box(display.left, clipped.top, display.right, clipped.bottom);
        }

        int topPadding = Math.round(FALLBACK_TOP_PADDING_DP * density);
        int bottomPadding = Math.round(FALLBACK_BOTTOM_PADDING_DP * density);
        Box expanded = new Box(display.left, anchor.top - topPadding,
                display.right, anchor.bottom + bottomPadding);
        return clamp(expanded, display);
    }

    /** Returns a full-width row only when the structural gap is banner-sized. */
    public static Box fromGap(int top, int bottom, Box display, float density) {
        Box candidate = clamp(new Box(display.left, top, display.right, bottom), display);
        int minHeight = Math.round(MIN_GAP_HEIGHT_DP * density);
        int maxHeight = Math.round(MAX_GAP_HEIGHT_DP * density);
        if (candidate.height() < minHeight || candidate.height() > maxHeight) {
            return new Box(display.left, display.top, display.right, display.top);
        }
        return candidate;
    }

    /** Moves a previously measured row with its lower edge and clips it below a header. */
    public static Box trackGap(
            int lowerEdge,
            int measuredHeight,
            int clipTop,
            Box display
    ) {
        int bottom = Math.max(display.top, Math.min(lowerEdge, display.bottom));
        int top = Math.max(clipTop, bottom - measuredHeight);
        top = Math.max(display.top, Math.min(top, display.bottom));
        if (measuredHeight <= 0 || bottom <= top) {
            return new Box(display.left, display.top, display.right, display.top);
        }
        return new Box(display.left, top, display.right, bottom);
    }

    /** Clips a detected row between the fixed header and the live list edge. */
    public static Box clipVertically(Box row, int clipTop, int clipBottom, Box display) {
        Box clamped = clamp(row, display);
        int top = Math.max(clamped.top, clipTop);
        int bottom = Math.min(clamped.bottom, clipBottom);
        if (bottom <= top) {
            return new Box(display.left, display.top, display.right, display.top);
        }
        return new Box(display.left, top, display.right, bottom);
    }

    public static Box clamp(Box value, Box display) {
        return new Box(
                Math.max(display.left, Math.min(value.left, display.right)),
                Math.max(display.top, Math.min(value.top, display.bottom)),
                Math.max(display.left, Math.min(value.right, display.right)),
                Math.max(display.top, Math.min(value.bottom, display.bottom)));
    }
}
