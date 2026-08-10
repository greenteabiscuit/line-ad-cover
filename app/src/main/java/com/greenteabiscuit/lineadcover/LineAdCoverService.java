package com.greenteabiscuit.lineadcover;

import android.accessibilityservice.AccessibilityService;
import android.content.res.Configuration;
import android.graphics.Color;
import android.graphics.Rect;
import android.os.Handler;
import android.os.Looper;
import android.view.Gravity;
import android.view.View;
import android.view.WindowInsets;
import android.view.WindowManager;
import android.view.accessibility.AccessibilityEvent;
import android.view.accessibility.AccessibilityNodeInfo;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.Locale;

public final class LineAdCoverService extends AccessibilityService {
    private static final String LINE_PACKAGE = "jp.naver.line.android";
    private static final long RESCAN_DELAY_MS = 60;
    private static final long FOREGROUND_CHECK_MS = 250;
    private static final float MIN_CONTROL_WIDTH_FRACTION = 0.65f;
    private static final float MIN_LIST_WIDTH_FRACTION = 0.80f;
    private static final float MAX_CONTROL_HEIGHT_DP = 64f;
    private static final float MIN_CONTROL_HORIZONTAL_INSET_DP = 8f;
    private static final float FIXED_HEADER_HEIGHT_DP = 54f;
    private static final float UPPER_CONTROLS_BOTTOM_FRACTION = 0.25f;
    private static final float NAVIGATION_TOP_FRACTION = 0.70f;

    private final Handler handler = new Handler(Looper.getMainLooper());
    private final Runnable rescan = this::scanAndUpdate;
    private final Runnable foregroundCheck = new Runnable() {
        @Override public void run() {
            AccessibilityNodeInfo root = getRootInActiveWindow();
            if (root == null || !LINE_PACKAGE.contentEquals(root.getPackageName())) {
                removeOverlays();
                return;
            }
            if (bannerMask.isVisible() || bottomTabsMask.isVisible()) {
                handler.postDelayed(this, FOREGROUND_CHECK_MS);
            }
        }
    };

    private WindowManager windowManager;
    private final MaskOverlay bannerMask = new MaskOverlay(
            "LINE VOOM promotional banner mask", false);
    private final MaskOverlay bottomTabsMask = new MaskOverlay(
            "LINE Voom News Apps bottom tab mask", true);
    private int trackedBannerHeight;
    private int trackedClipTop;
    private int trackedListBottomOffset;

    @Override public void onServiceConnected() {
        windowManager = getSystemService(WindowManager.class);
    }

    @Override public void onAccessibilityEvent(AccessibilityEvent event) {
        if (event.getPackageName() == null
                || !LINE_PACKAGE.contentEquals(event.getPackageName())) {
            clearTracking();
            removeOverlays();
            return;
        }
        if (event.getEventType() == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) {
            removeOverlays();
        }
        handler.removeCallbacks(rescan);
        if (event.getEventType() == AccessibilityEvent.TYPE_VIEW_SCROLLED) {
            handler.post(rescan);
        } else {
            handler.postDelayed(rescan, RESCAN_DELAY_MS);
        }
    }

    @Override public void onInterrupt() {
        clearTracking();
        removeOverlays();
    }

    @Override public void onDestroy() {
        handler.removeCallbacksAndMessages(null);
        clearTracking();
        removeOverlays();
        super.onDestroy();
    }

    @Override public void onConfigurationChanged(Configuration newConfig) {
        super.onConfigurationChanged(newConfig);
        clearTracking();
        removeOverlays();
        handler.post(rescan);
    }

    private void scanAndUpdate() {
        AccessibilityNodeInfo root = getRootInActiveWindow();
        if (root == null || !LINE_PACKAGE.contentEquals(root.getPackageName())) {
            clearTracking();
            removeOverlays();
            return;
        }

        Rect displayRect = windowManager.getCurrentWindowMetrics().getBounds();
        AdRowGeometry.Box bottomTabs = findBottomTabsMask(root, displayRect);
        if (bottomTabs.height() > 0) {
            bottomTabsMask.show(bottomTabs);
        } else {
            bottomTabsMask.remove();
        }

        if (!isChatsTab(root, displayRect)) {
            clearTracking();
            bannerMask.remove();
            return;
        }

        float density = getResources().getDisplayMetrics().density;
        LayoutMetrics layout = findLayoutMetrics(root, displayRect, density);
        AccessibilityNodeInfo anchor = findPromoAnchor(root, displayRect);
        AdRowGeometry.Box selected;
        if (anchor != null) {
            Rect anchorRect = new Rect();
            anchor.getBoundsInScreen(anchorRect);
            List<AdRowGeometry.Box> ancestors = nodeAndAncestorBoxes(anchor);
            AdRowGeometry.Box detected = AdRowGeometry.select(
                    box(anchorRect), ancestors, box(displayRect), density);
            rememberTracking(detected, layout, density);
            selected = AdRowGeometry.clipVertically(
                    detected, fixedHeaderBottom(density), layout.listTop(), box(displayRect));
        } else {
            selected = findPromoGap(layout, displayRect, density);
        }
        if (selected.height() <= 0) {
            bannerMask.remove();
            return;
        }
        bannerMask.show(selected);
    }

    private AdRowGeometry.Box findBottomTabsMask(
            AccessibilityNodeInfo root,
            Rect displayRect
    ) {
        Deque<AccessibilityNodeInfo> pending = new ArrayDeque<>();
        pending.push(root);
        int navigationContentTop = displayRect.bottom;
        int navigationBottom = displayRect.top;
        int navigationTop = displayRect.top
                + Math.round(displayRect.height() * NAVIGATION_TOP_FRACTION);

        while (!pending.isEmpty()) {
            AccessibilityNodeInfo node = pending.pop();
            Rect bounds = new Rect();
            node.getBoundsInScreen(bounds);
            if (!bounds.isEmpty() && bounds.centerY() >= navigationTop) {
                String label = searchableText(node);
                if (isVoomLabel(label) || isNewsLabel(label) || isAppsLabel(label)) {
                    navigationContentTop = Math.min(navigationContentTop, bounds.top);
                    navigationBottom = Math.max(navigationBottom, bounds.bottom);
                }
            }
            for (int i = node.getChildCount() - 1; i >= 0; i--) {
                AccessibilityNodeInfo child = node.getChild(i);
                if (child != null) pending.push(child);
            }
        }

        if (navigationBottom <= navigationContentTop) {
            return new AdRowGeometry.Box(
                    displayRect.left, displayRect.top, displayRect.right, displayRect.top);
        }
        int firstCoveredTab = displayRect.left + Math.round(displayRect.width() * 0.4f);
        return new AdRowGeometry.Box(
                firstCoveredTab,
                navigationContentTop,
                displayRect.right,
                Math.min(navigationBottom, displayRect.bottom));
    }

    private boolean isChatsTab(AccessibilityNodeInfo root, Rect displayRect) {
        Deque<AccessibilityNodeInfo> pending = new ArrayDeque<>();
        pending.push(root);
        boolean chatsTabVisible = false;
        boolean chatsTabSelected = false;
        boolean anotherTabSelected = false;
        int navigationTop = displayRect.top
                + Math.round(displayRect.height() * NAVIGATION_TOP_FRACTION);

        while (!pending.isEmpty()) {
            AccessibilityNodeInfo node = pending.pop();
            Rect bounds = new Rect();
            node.getBoundsInScreen(bounds);
            if (!bounds.isEmpty() && bounds.centerY() >= navigationTop) {
                String label = searchableText(node);
                boolean selected = node.isSelected()
                        || label.contains("selected")
                        || label.contains("選択中")
                        || label.contains("選択済み");
                if (isChatsLabel(label)) {
                    chatsTabVisible = true;
                    chatsTabSelected |= selected;
                } else if (isTopLevelTabLabel(label) && selected) {
                    anotherTabSelected = true;
                }
            }
            for (int i = node.getChildCount() - 1; i >= 0; i--) {
                AccessibilityNodeInfo child = node.getChild(i);
                if (child != null) pending.push(child);
            }
        }

        // Some LINE versions expose the selected tab only as a visible label. The
        // exact promo phrase remains a second gate before an overlay is displayed.
        return chatsTabVisible && (chatsTabSelected || !anotherTabSelected);
    }

    private AccessibilityNodeInfo findPromoAnchor(
            AccessibilityNodeInfo root,
            Rect displayRect
    ) {
        Deque<AccessibilityNodeInfo> pending = new ArrayDeque<>();
        pending.push(root);
        AccessibilityNodeInfo exact = null;
        int navigationTop = displayRect.top
                + Math.round(displayRect.height() * NAVIGATION_TOP_FRACTION);
        while (!pending.isEmpty()) {
            AccessibilityNodeInfo node = pending.pop();
            String searchable = searchableText(node);
            Rect bounds = new Rect();
            node.getBoundsInScreen(bounds);
            if (!bounds.isEmpty()
                    && bounds.top < navigationTop
                    && searchable.contains("trending")
                    && searchable.contains("line voom")) {
                exact = smallerNode(exact, node);
            }
            for (int i = node.getChildCount() - 1; i >= 0; i--) {
                AccessibilityNodeInfo child = node.getChild(i);
                if (child != null) pending.push(child);
            }
        }
        return exact;
    }

    /**
     * Some LINE builds render the promotion without exposing its text to
     * accessibility. In those builds, the banner is still the only vertical gap
     * between the wide Search control and the scrollable chat list.
     */
    private LayoutMetrics findLayoutMetrics(
            AccessibilityNodeInfo root,
            Rect displayRect,
            float density
    ) {
        int minControlWidth = Math.round(
                displayRect.width() * MIN_CONTROL_WIDTH_FRACTION);
        int minListWidth = Math.round(displayRect.width() * MIN_LIST_WIDTH_FRACTION);
        int maxControlHeight = Math.round(MAX_CONTROL_HEIGHT_DP * density);
        int minControlInset = Math.round(MIN_CONTROL_HORIZONTAL_INSET_DP * density);
        int controlsLimit = displayRect.top
                + Math.round(displayRect.height() * UPPER_CONTROLS_BOTTOM_FRACTION);
        int navigationTop = displayRect.top
                + Math.round(displayRect.height() * NAVIGATION_TOP_FRACTION);
        List<Rect> clickableBounds = new ArrayList<>();
        List<Rect> scrollableBounds = new ArrayList<>();

        Deque<AccessibilityNodeInfo> pending = new ArrayDeque<>();
        pending.push(root);
        while (!pending.isEmpty()) {
            AccessibilityNodeInfo node = pending.pop();
            Rect bounds = new Rect();
            node.getBoundsInScreen(bounds);
            if (!bounds.isEmpty()) {
                if (node.isClickable()
                        && !node.isScrollable()
                        && bounds.width() >= minControlWidth
                        && bounds.height() <= maxControlHeight
                        && bounds.left >= displayRect.left + minControlInset
                        && bounds.right <= displayRect.right - minControlInset
                        && bounds.top < controlsLimit
                        && bounds.bottom <= controlsLimit) {
                    clickableBounds.add(bounds);
                }
                if (node.isScrollable()
                        && bounds.width() >= minListWidth
                        && bounds.top > displayRect.top
                        && bounds.top < navigationTop) {
                    scrollableBounds.add(bounds);
                }
            }
            for (int i = node.getChildCount() - 1; i >= 0; i--) {
                AccessibilityNodeInfo child = node.getChild(i);
                if (child != null) pending.push(child);
            }
        }

        int listTop = navigationTop;
        for (Rect bounds : scrollableBounds) {
            listTop = Math.min(listTop, bounds.top);
        }

        int controlTop = displayRect.top;
        int controlBottom = displayRect.top;
        for (Rect bounds : clickableBounds) {
            if (bounds.bottom <= listTop && bounds.bottom > controlBottom) {
                controlTop = bounds.top;
                controlBottom = bounds.bottom;
            }
        }
        return new LayoutMetrics(controlTop, controlBottom, listTop);
    }

    private AdRowGeometry.Box findPromoGap(
            LayoutMetrics layout,
            Rect displayRect,
            float density
    ) {
        AdRowGeometry.Box display = box(displayRect);
        if (trackedBannerHeight > 0) {
            return AdRowGeometry.trackGap(
                    layout.listTop() - trackedListBottomOffset,
                    trackedBannerHeight,
                    trackedClipTop,
                    display);
        }
        if (layout.controlBottom() > displayRect.top) {
            AdRowGeometry.Box measured = AdRowGeometry.fromGap(
                    layout.controlBottom(), layout.listTop(), display, density);
            if (measured.height() > 0) {
                trackedBannerHeight = measured.height();
                trackedClipTop = Math.max(layout.controlTop(), fixedHeaderBottom(density));
                trackedListBottomOffset = 0;
                return measured;
            }
            clearTracking();
            return measured;
        }
        return AdRowGeometry.trackGap(
                layout.listTop(), trackedBannerHeight, trackedClipTop, display);
    }

    private void rememberTracking(
            AdRowGeometry.Box selected,
            LayoutMetrics layout,
            float density
    ) {
        if (trackedBannerHeight <= 0
                && selected.height() > 0
                && layout.controlBottom() > 0) {
            trackedBannerHeight = selected.height();
            trackedClipTop = Math.max(layout.controlTop(), fixedHeaderBottom(density));
            trackedListBottomOffset = Math.max(0, layout.listTop() - selected.bottom());
        }
    }

    private int fixedHeaderBottom(float density) {
        int statusBarBottom = windowManager.getCurrentWindowMetrics()
                .getWindowInsets()
                .getInsetsIgnoringVisibility(WindowInsets.Type.statusBars())
                .top;
        return statusBarBottom + Math.round(FIXED_HEADER_HEIGHT_DP * density);
    }

    private AccessibilityNodeInfo smallerNode(
            AccessibilityNodeInfo current,
            AccessibilityNodeInfo candidate
    ) {
        if (current == null) {
            return candidate;
        }
        Rect currentRect = new Rect();
        Rect candidateRect = new Rect();
        current.getBoundsInScreen(currentRect);
        candidate.getBoundsInScreen(candidateRect);
        if (!candidateRect.isEmpty()
                && (currentRect.isEmpty()
                || (long) candidateRect.width() * candidateRect.height()
                < (long) currentRect.width() * currentRect.height())) {
            return candidate;
        }
        return current;
    }

    private List<AdRowGeometry.Box> nodeAndAncestorBoxes(AccessibilityNodeInfo anchor) {
        List<AdRowGeometry.Box> result = new ArrayList<>();
        AccessibilityNodeInfo current = anchor;
        while (current != null) {
            Rect rect = new Rect();
            current.getBoundsInScreen(rect);
            if (!rect.isEmpty()) result.add(box(rect));
            current = current.getParent();
        }
        return result;
    }

    private void removeOverlays() {
        handler.removeCallbacks(foregroundCheck);
        bannerMask.remove();
        bottomTabsMask.remove();
    }

    private void clearTracking() {
        trackedBannerHeight = 0;
        trackedClipTop = 0;
        trackedListBottomOffset = 0;
    }

    private static String safe(CharSequence value) {
        return value == null ? "" : value.toString();
    }

    private static String searchableText(AccessibilityNodeInfo node) {
        return (safe(node.getText()) + " " + safe(node.getContentDescription()))
                .trim()
                .toLowerCase(Locale.ROOT);
    }

    private static boolean isChatsLabel(String label) {
        return label.equals("chats")
                || label.startsWith("chats,")
                || label.startsWith("chats ")
                || label.equals("トーク")
                || label.startsWith("トーク,")
                || label.startsWith("トーク ");
    }

    private static boolean isTopLevelTabLabel(String label) {
        return label.startsWith("home")
                || label.startsWith("voom")
                || label.startsWith("news")
                || label.startsWith("apps")
                || label.startsWith("wallet")
                || label.startsWith("calls")
                || label.startsWith("ホーム")
                || label.startsWith("ニュース")
                || label.startsWith("アプリ")
                || label.startsWith("ウォレット")
                || label.startsWith("通話");
    }

    private static boolean isVoomLabel(String label) {
        return label.equals("voom")
                || label.startsWith("voom,")
                || label.startsWith("voom ");
    }

    private static boolean isNewsLabel(String label) {
        return label.equals("news")
                || label.startsWith("news,")
                || label.startsWith("news ")
                || label.equals("ニュース")
                || label.startsWith("ニュース,")
                || label.startsWith("ニュース ");
    }

    private static boolean isAppsLabel(String label) {
        return label.equals("apps")
                || label.startsWith("apps,")
                || label.startsWith("apps ")
                || label.equals("アプリ")
                || label.startsWith("アプリ,")
                || label.startsWith("アプリ ");
    }

    private static AdRowGeometry.Box box(Rect rect) {
        return new AdRowGeometry.Box(rect.left, rect.top, rect.right, rect.bottom);
    }

    private int navigationMaskColor() {
        int nightMode = getResources().getConfiguration().uiMode
                & Configuration.UI_MODE_NIGHT_MASK;
        return nightMode == Configuration.UI_MODE_NIGHT_YES ? Color.BLACK : Color.WHITE;
    }

    private final class MaskOverlay {
        private final String title;
        private final boolean matchNavigationTheme;
        private View view;
        private WindowManager.LayoutParams params;
        private AdRowGeometry.Box bounds;

        private MaskOverlay(String title, boolean matchNavigationTheme) {
            this.title = title;
            this.matchNavigationTheme = matchNavigationTheme;
        }

        private boolean isVisible() {
            return view != null;
        }

        private void show(AdRowGeometry.Box nextBounds) {
            if (view != null && nextBounds.equals(bounds)) return;
            int contentTop = windowManager.getCurrentWindowMetrics()
                    .getWindowInsets()
                    .getInsetsIgnoringVisibility(WindowInsets.Type.statusBars())
                    .top;
            int y = Math.max(0, nextBounds.top() - contentTop);

            if (view != null) {
                params.width = nextBounds.width();
                params.height = nextBounds.height();
                params.x = nextBounds.left();
                params.y = y;
                windowManager.updateViewLayout(view, params);
                bounds = nextBounds;
                return;
            }

            view = new View(LineAdCoverService.this);
            view.setBackgroundColor(
                    matchNavigationTheme ? navigationMaskColor() : Color.WHITE);
            view.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
            params = new WindowManager.LayoutParams(
                    nextBounds.width(),
                    nextBounds.height(),
                    WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
                    WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                            | WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
                            | WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
                    android.graphics.PixelFormat.OPAQUE);
            params.gravity = Gravity.TOP | Gravity.START;
            params.x = nextBounds.left();
            params.y = y;
            params.setTitle(title);
            windowManager.addView(view, params);
            bounds = nextBounds;
            handler.removeCallbacks(foregroundCheck);
            handler.postDelayed(foregroundCheck, FOREGROUND_CHECK_MS);
        }

        private void remove() {
            if (view != null && windowManager != null) {
                try {
                    windowManager.removeViewImmediate(view);
                } catch (IllegalArgumentException ignored) {
                    // Already detached by the window manager.
                }
            }
            view = null;
            params = null;
            bounds = null;
        }
    }

    private record LayoutMetrics(int controlTop, int controlBottom, int listTop) {}
}
