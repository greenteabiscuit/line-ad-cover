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
    private static final String NAVIGATION_VIEW_ID =
            "jp.naver.line.android:id/main_tab_container";
    private static final long RESCAN_DELAY_MS = 60;
    private static final long SETTLED_RESCAN_DELAY_MS = 600;
    private static final long FOREGROUND_CHECK_MS = 500;
    private static final float MIN_CONTROL_WIDTH_FRACTION = 0.65f;
    private static final float MIN_LIST_WIDTH_FRACTION = 0.80f;
    private static final float MAX_CONTROL_HEIGHT_DP = 64f;
    private static final float MIN_CONTROL_HORIZONTAL_INSET_DP = 8f;
    private static final float FIXED_HEADER_HEIGHT_DP = 54f;
    private static final float UPPER_CONTROLS_BOTTOM_FRACTION = 0.38f;
    private static final float NAVIGATION_TOP_FRACTION = 0.70f;

    private final Handler handler = new Handler(Looper.getMainLooper());
    private final Runnable rescan = this::scanAndUpdate;
    private final Runnable settledRescan = this::scanAndUpdate;
    private final Runnable foregroundCheck = new Runnable() {
        @Override public void run() {
            AccessibilityNodeInfo root = getRootInActiveWindow();
            if (root == null || !LINE_PACKAGE.contentEquals(root.getPackageName())) {
                removeOverlays();
                return;
            }
            scanAndUpdate();
            handler.removeCallbacks(this);
            handler.postDelayed(this, FOREGROUND_CHECK_MS);
        }
    };

    private WindowManager windowManager;
    private final MaskOverlay bannerMask = new MaskOverlay(
            "LINE VOOM promotional banner mask", false);
    private final MaskOverlay homeTabMask = new MaskOverlay(
            "LINE Home bottom tab mask", true);
    private final MaskOverlay bottomTabsMask = new MaskOverlay(
            "LINE final three bottom tabs mask", true);
    private int trackedBannerHeight;
    private int trackedClipTop;
    private int trackedListBottomOffset;

    @Override public void onServiceConnected() {
        windowManager = getSystemService(WindowManager.class);
        handler.post(rescan);
        handler.postDelayed(foregroundCheck, FOREGROUND_CHECK_MS);
    }

    @Override public void onAccessibilityEvent(AccessibilityEvent event) {
        if (event.getPackageName() == null
                || !LINE_PACKAGE.contentEquals(event.getPackageName())) {
            handler.removeCallbacks(rescan);
            handler.removeCallbacks(settledRescan);
            clearTracking();
            removeOverlays();
            return;
        }
        handler.removeCallbacks(rescan);
        if (event.getEventType() == AccessibilityEvent.TYPE_VIEW_SCROLLED) {
            handler.post(rescan);
        } else {
            handler.postDelayed(rescan, RESCAN_DELAY_MS);
        }
        handler.removeCallbacks(settledRescan);
        handler.postDelayed(settledRescan, SETTLED_RESCAN_DELAY_MS);
        handler.removeCallbacks(foregroundCheck);
        handler.postDelayed(foregroundCheck, FOREGROUND_CHECK_MS);
    }

    @Override public void onInterrupt() {
        handler.removeCallbacks(rescan);
        handler.removeCallbacks(settledRescan);
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
        handler.postDelayed(settledRescan, SETTLED_RESCAN_DELAY_MS);
    }

    private void scanAndUpdate() {
        AccessibilityNodeInfo root = getRootInActiveWindow();
        if (root == null || !LINE_PACKAGE.contentEquals(root.getPackageName())) {
            clearTracking();
            removeOverlays();
            return;
        }

        Rect displayRect = windowManager.getCurrentWindowMetrics().getBounds();
        Rect rootRect = new Rect();
        root.getBoundsInScreen(rootRect);
        if (!rootRect.isEmpty()
                && rootRect.width() < Math.round(displayRect.width() * 0.80f)
                && (bannerMask.isVisible() || homeTabMask.isVisible()
                || bottomTabsMask.isVisible())) {
            return;
        }
        boolean friendsSubviewSelected = isFriendsSubviewSelected(root, displayRect);
        if (friendsSubviewSelected) {
            clearTracking();
            bannerMask.remove();
        }
        float density = getResources().getDisplayMetrics().density;
        NavigationState navigation = findNavigation(root, displayRect, density);
        AdRowGeometry.Box bottomTabs = NavigationGeometry.lastThreeTabs(navigation.bounds());
        if (bottomTabs.height() > 0) {
            homeTabMask.show(NavigationGeometry.homeTab(navigation.bounds()));
            bottomTabsMask.show(bottomTabs);
        } else {
            homeTabMask.remove();
            bottomTabsMask.remove();
        }

        if (!isChatsTab(navigation) || friendsSubviewSelected) {
            clearTracking();
            bannerMask.remove();
            return;
        }

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
                    detected,
                    Math.max(fixedHeaderBottom(density), layout.controlTop()),
                    layout.listTop(),
                    box(displayRect));
        } else {
            selected = findPromoGap(layout, displayRect, density);
        }
        if (selected.height() <= 0) {
            bannerMask.remove();
            return;
        }
        bannerMask.show(selected);
    }

    private NavigationState findNavigation(
            AccessibilityNodeInfo root,
            Rect displayRect,
            float density
    ) {
        List<AdRowGeometry.Box> labelBounds = new ArrayList<>();
        List<AdRowGeometry.Box> ancestorCandidates = new ArrayList<>();
        List<AdRowGeometry.Box> structuralCandidates = new ArrayList<>();
        boolean[] seenTabs = new boolean[5];
        boolean chatsTabVisible = false;
        boolean chatsTabSelected = false;
        boolean anotherTabSelected = false;
        Deque<AccessibilityNodeInfo> pending = new ArrayDeque<>();
        List<AccessibilityNodeInfo> navigationContainers =
                root.findAccessibilityNodeInfosByViewId(NAVIGATION_VIEW_ID);
        boolean scopedToNavigation = !navigationContainers.isEmpty();
        if (scopedToNavigation) {
            AccessibilityNodeInfo container = navigationContainers.get(0);
            pending.push(container);
            Rect containerBounds = new Rect();
            container.getBoundsInScreen(containerBounds);
            if (!containerBounds.isEmpty()) {
                structuralCandidates.add(box(containerBounds));
            }
        } else {
            pending.push(root);
        }
        int navigationTop = displayRect.top
                + Math.round(displayRect.height() * NAVIGATION_TOP_FRACTION);

        while (!pending.isEmpty()) {
            AccessibilityNodeInfo node = pending.pop();
            Rect bounds = new Rect();
            node.getBoundsInScreen(bounds);
            if (!bounds.isEmpty() && bounds.centerY() >= navigationTop) {
                String label = searchableText(node);
                int tab = LineUiLabels.topLevelTab(label);
                if (tab >= 0) {
                    seenTabs[tab] = true;
                    labelBounds.add(box(bounds));
                    if (!scopedToNavigation) {
                        ancestorCandidates.addAll(nodeAndAncestorBoxes(node));
                    }
                    boolean selected = isNodeOrAncestorSelected(node)
                            || LineUiLabels.isSelected(label);
                    if (tab == 1) {
                        chatsTabVisible = true;
                        chatsTabSelected |= selected;
                    } else {
                        anotherTabSelected |= selected;
                    }
                }
                if (!scopedToNavigation
                        && looksLikeFiveTabNavigation(node, bounds, displayRect, density)) {
                    structuralCandidates.add(box(bounds));
                }
            }
            for (int i = node.getChildCount() - 1; i >= 0; i--) {
                AccessibilityNodeInfo child = node.getChild(i);
                if (child != null) pending.push(child);
            }
        }

        int distinctTabs = 0;
        for (boolean seen : seenTabs) {
            if (seen) distinctTabs++;
        }
        if (distinctTabs < 2) {
            labelBounds.clear();
            ancestorCandidates.clear();
            chatsTabVisible = false;
            chatsTabSelected = false;
            anotherTabSelected = false;
        }

        AdRowGeometry.Box display = box(displayRect);
        AdRowGeometry.Box navigation = NavigationGeometry.selectContainer(
                structuralCandidates, display, density);
        if (navigation.height() <= 0) {
            navigation = NavigationGeometry.selectContainer(
                    ancestorCandidates, display, density);
        }
        if (navigation.height() <= 0 && !labelBounds.isEmpty()) {
            int navigationBarBottom = windowManager.getCurrentWindowMetrics()
                    .getWindowInsets()
                    .getInsetsIgnoringVisibility(WindowInsets.Type.navigationBars())
                    .bottom;
            navigation = NavigationGeometry.estimateFromLabels(
                    labelBounds,
                    displayRect.bottom - navigationBarBottom,
                    display,
                    density);
        }
        return new NavigationState(
                navigation,
                chatsTabVisible,
                chatsTabSelected,
                anotherTabSelected);
    }

    private boolean isChatsTab(NavigationState navigation) {
        // Some LINE versions expose the selected tab only as a visible label. The
        // exact promo phrase remains a second gate before an overlay is displayed.
        return navigation.chatsTabVisible()
                && (navigation.chatsTabSelected() || !navigation.anotherTabSelected());
    }

    private boolean isFriendsSubviewSelected(
            AccessibilityNodeInfo root,
            Rect displayRect
    ) {
        int upperContentBottom = displayRect.top
                + Math.round(displayRect.height() * UPPER_CONTROLS_BOTTOM_FRACTION);
        Deque<AccessibilityNodeInfo> pending = new ArrayDeque<>();
        pending.push(root);
        while (!pending.isEmpty()) {
            AccessibilityNodeInfo node = pending.pop();
            Rect bounds = new Rect();
            node.getBoundsInScreen(bounds);
            if (!bounds.isEmpty()
                    && bounds.top < upperContentBottom
                    && LineUiLabels.isFriendsSubview(searchableText(node))
                    && isNodeOrAncestorSelected(node)) {
                return true;
            }
            for (int i = node.getChildCount() - 1; i >= 0; i--) {
                AccessibilityNodeInfo child = node.getChild(i);
                if (child != null) pending.push(child);
            }
        }
        return false;
    }

    private AccessibilityNodeInfo findPromoAnchor(
            AccessibilityNodeInfo root,
            Rect displayRect
    ) {
        AccessibilityNodeInfo exact = null;
        int navigationTop = displayRect.top
                + Math.round(displayRect.height() * NAVIGATION_TOP_FRACTION);
        for (AccessibilityNodeInfo node : root.findAccessibilityNodeInfosByText("VOOM")) {
            String searchable = searchableText(node);
            Rect bounds = new Rect();
            node.getBoundsInScreen(bounds);
            if (!bounds.isEmpty()
                    && bounds.top < navigationTop
                    && LineUiLabels.isPromo(searchable)) {
                exact = smallerNode(exact, node);
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
        List<Rect> searchBounds = new ArrayList<>();
        List<Rect> clickableBounds = new ArrayList<>();
        List<Rect> scrollableBounds = new ArrayList<>();

        Deque<AccessibilityNodeInfo> pending = new ArrayDeque<>();
        pending.push(root);
        while (!pending.isEmpty()) {
            AccessibilityNodeInfo node = pending.pop();
            Rect bounds = new Rect();
            node.getBoundsInScreen(bounds);
            if (!bounds.isEmpty()) {
                boolean controlGeometry = !isScrollableNode(node)
                        && bounds.width() >= minControlWidth
                        && bounds.height() <= maxControlHeight
                        && bounds.left >= displayRect.left + minControlInset
                        && bounds.right <= displayRect.right - minControlInset
                        && bounds.top < controlsLimit
                        && bounds.bottom <= controlsLimit;
                if (controlGeometry && LineUiLabels.isSearch(searchableText(node))) {
                    searchBounds.add(bounds);
                } else if (controlGeometry && node.isClickable()) {
                    clickableBounds.add(bounds);
                }
                if (isScrollableNode(node)
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

        int controlTop = displayRect.top;
        int controlBottom = displayRect.top;
        List<Rect> controls = searchBounds.isEmpty() ? clickableBounds : searchBounds;
        for (Rect bounds : controls) {
            if (bounds.bottom > controlBottom) {
                controlTop = bounds.top;
                controlBottom = bounds.bottom;
            }
        }

        int listTop = navigationTop;
        for (Rect bounds : scrollableBounds) {
            if ((controlBottom <= displayRect.top || bounds.top >= controlBottom)
                    && bounds.top < listTop) {
                listTop = bounds.top;
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

    private boolean looksLikeFiveTabNavigation(
            AccessibilityNodeInfo node,
            Rect bounds,
            Rect displayRect,
            float density
    ) {
        int minTop = displayRect.top + Math.round(displayRect.height() * 0.60f);
        int minHeight = Math.round(40f * density);
        int maxHeight = Math.round(144f * density);
        if (node.getChildCount() < 5
                || bounds.width() < Math.round(displayRect.width() * 0.80f)
                || bounds.height() < minHeight
                || bounds.height() > maxHeight
                || bounds.top < minTop) {
            return false;
        }

        boolean[] columns = new boolean[5];
        Deque<AccessibilityNodeInfo> pending = new ArrayDeque<>();
        Deque<Integer> depths = new ArrayDeque<>();
        for (int i = 0; i < node.getChildCount(); i++) {
            AccessibilityNodeInfo child = node.getChild(i);
            if (child != null) {
                pending.push(child);
                depths.push(1);
            }
        }
        while (!pending.isEmpty()) {
            AccessibilityNodeInfo descendant = pending.pop();
            int depth = depths.pop();
            Rect childBounds = new Rect();
            descendant.getBoundsInScreen(childBounds);
            if (!childBounds.isEmpty()
                    && bounds.contains(childBounds)
                    && childBounds.width() < bounds.width() / 2
                    && (descendant.isClickable()
                    || descendant.getChildCount() == 0
                    || LineUiLabels.topLevelTab(searchableText(descendant)) >= 0)) {
                float position = (childBounds.exactCenterX() - bounds.left) / bounds.width();
                int column = Math.min(4, Math.max(0, (int) (position * 5)));
                float expectedCenter = (column + 0.5f) / 5f;
                if (Math.abs(position - expectedCenter) <= 0.11f) {
                    columns[column] = true;
                }
            }
            if (depth < 3) {
                for (int i = 0; i < descendant.getChildCount(); i++) {
                    AccessibilityNodeInfo child = descendant.getChild(i);
                    if (child != null) {
                        pending.push(child);
                        depths.push(depth + 1);
                    }
                }
            }
        }
        for (boolean present : columns) {
            if (!present) return false;
        }
        return true;
    }

    private static boolean isNodeOrAncestorSelected(AccessibilityNodeInfo node) {
        AccessibilityNodeInfo current = node;
        for (int depth = 0; current != null && depth < 4; depth++) {
            if (current.isSelected()
                    || current.isChecked()
                    || (depth == 0 && LineUiLabels.isSelected(searchableText(current)))) {
                return true;
            }
            current = current.getParent();
        }
        return false;
    }

    private void removeOverlays() {
        handler.removeCallbacks(foregroundCheck);
        bannerMask.remove();
        homeTabMask.remove();
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
        return (safe(node.getText())
                + " " + safe(node.getContentDescription())
                + " " + safe(node.getHintText())
                + " " + safe(node.getStateDescription())
                + " " + safe(node.getTooltipText())
                + " " + safe(node.getViewIdResourceName()))
                .trim()
                .toLowerCase(Locale.ROOT);
    }

    private static boolean isScrollableNode(AccessibilityNodeInfo node) {
        return node.isScrollable()
                || node.getActionList().contains(
                AccessibilityNodeInfo.AccessibilityAction.ACTION_SCROLL_FORWARD)
                || node.getActionList().contains(
                AccessibilityNodeInfo.AccessibilityAction.ACTION_SCROLL_BACKWARD);
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

    private record NavigationState(
            AdRowGeometry.Box bounds,
            boolean chatsTabVisible,
            boolean chatsTabSelected,
            boolean anotherTabSelected
    ) {}
}
