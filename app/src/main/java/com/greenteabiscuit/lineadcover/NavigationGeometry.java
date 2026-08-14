package com.greenteabiscuit.lineadcover;

import java.util.List;

/** Pure geometry used to locate LINE's bottom navigation and cover its last three tabs. */
public final class NavigationGeometry {
    private static final float MIN_WIDTH_FRACTION = 0.80f;
    private static final float MIN_TOP_FRACTION = 0.60f;
    private static final float MIN_BOTTOM_FRACTION = 0.78f;
    private static final float MIN_HEIGHT_DP = 40f;
    private static final float MAX_HEIGHT_DP = 144f;
    private static final float ESTIMATED_HEIGHT_DP = 64f;

    private NavigationGeometry() {}

    /** Selects the lowest wide, shallow candidate in the bottom portion of the display. */
    public static AdRowGeometry.Box selectContainer(
            List<AdRowGeometry.Box> candidates,
            AdRowGeometry.Box display,
            float density
    ) {
        int minWidth = Math.round(display.width() * MIN_WIDTH_FRACTION);
        int minTop = display.top() + Math.round(display.height() * MIN_TOP_FRACTION);
        int minBottom = display.top() + Math.round(display.height() * MIN_BOTTOM_FRACTION);
        int minHeight = Math.round(MIN_HEIGHT_DP * density);
        int maxHeight = Math.round(MAX_HEIGHT_DP * density);

        AdRowGeometry.Box selected = empty(display);
        for (AdRowGeometry.Box candidate : candidates) {
            AdRowGeometry.Box clipped = AdRowGeometry.clamp(candidate, display);
            if (clipped.width() >= minWidth
                    && clipped.height() >= minHeight
                    && clipped.height() <= maxHeight
                    && clipped.top() >= minTop
                    && clipped.bottom() >= minBottom) {
                if (selected.height() <= 0
                        || clipped.top() > selected.top()
                        || (clipped.top() == selected.top()
                        && clipped.height() < selected.height())) {
                    selected = clipped;
                }
            }
        }
        return selected;
    }

    /**
     * Estimates the complete navigation row when LINE exposes tab labels but not
     * their shared container to accessibility.
     */
    public static AdRowGeometry.Box estimateFromLabels(
            List<AdRowGeometry.Box> labels,
            int contentBottom,
            AdRowGeometry.Box display,
            float density
    ) {
        if (labels.isEmpty()) return empty(display);

        int labelTop = display.bottom();
        int labelBottom = display.top();
        for (AdRowGeometry.Box label : labels) {
            AdRowGeometry.Box clipped = AdRowGeometry.clamp(label, display);
            labelTop = Math.min(labelTop, clipped.top());
            labelBottom = Math.max(labelBottom, clipped.bottom());
        }

        int bottom = Math.max(labelBottom,
                Math.max(display.top(), Math.min(contentBottom, display.bottom())));
        int top = Math.min(labelTop, bottom - Math.round(ESTIMATED_HEIGHT_DP * density));
        top = Math.max(display.top(), top);
        if (bottom <= top) return empty(display);
        return new AdRowGeometry.Box(display.left(), top, display.right(), bottom);
    }

    /** Covers exactly the final three of five equal-width navigation sections. */
    public static AdRowGeometry.Box lastThreeTabs(AdRowGeometry.Box navigation) {
        int left = navigation.left() + Math.round(navigation.width() * 0.4f);
        return new AdRowGeometry.Box(
                left, navigation.top(), navigation.right(), navigation.bottom());
    }

    private static AdRowGeometry.Box empty(AdRowGeometry.Box display) {
        return new AdRowGeometry.Box(
                display.left(), display.top(), display.right(), display.top());
    }
}
