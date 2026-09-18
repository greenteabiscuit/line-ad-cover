package com.greenteabiscuit.lineadcover;

import android.accessibilityservice.AccessibilityService;
import android.content.res.Configuration;
import android.graphics.Color;
import android.graphics.Rect;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
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
    /**
     * Enable the decision log with {@code adb shell setprop log.tag.LineAdCover DEBUG},
     * then {@code adb logcat -s LineAdCover}. Off unless that property is set. It records
     * geometry and detector outcomes only — never chat content.
     *
     * <p>This exists because LINE's accessibility tree is the only specification this app
     * has, and it changes without warning. Every detector here is a guess about someone
     * else's UI, so when a mask lands in the wrong place the useful question is which
     * measurement went wrong, not which line of code looks suspicious.
     */
    private static final String TAG = "LineAdCover";

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
    private static final float MAX_PROMO_ROW_HEIGHT_DP = 240f;
    private static final float MAX_SUBVIEW_WIDTH_FRACTION = 0.60f;
    private static final float MAX_SELECTION_OWNER_WIDTH_FRACTION = 0.60f;

    private final Handler handler = new Handler(Looper.getMainLooper());
    private final Runnable rescan = this::scanAndUpdate;
    private final Runnable settledRescan = this::scanAndUpdate;
    private final Runnable foregroundCheck = new Runnable() {
        @Override public void run() {
            AccessibilityNodeInfo root = getRootInActiveWindow();
            if (root == null || !LINE_PACKAGE.contentEquals(root.getPackageName())) {
                clearTracking();
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
            "LINE VOOM promotional banner mask");
    private final MaskOverlay homeTabMask = new MaskOverlay("LINE Home bottom tab mask");
    private final MaskOverlay bottomTabsMask = new MaskOverlay(
            "LINE final three bottom tabs mask");
    private int trackedBannerHeight;
    private int trackedListBottomOffset;
    private String lastDecision;

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
        float density = getResources().getDisplayMetrics().density;
        boolean friendsSubviewSelected =
                isFriendsSubviewSelected(root, displayRect, density);
        NavigationState navigation = findNavigation(root, displayRect, density);
        AdRowGeometry.Box bottomTabs = NavigationGeometry.lastThreeTabs(navigation.bounds());
        if (bottomTabs.height() > 0) {
            homeTabMask.show(NavigationGeometry.homeTab(navigation.bounds()));
            bottomTabsMask.show(bottomTabs);
        } else {
            homeTabMask.remove();
            bottomTabsMask.remove();
        }

        // The top-level navigation must be present, and nothing may contradict Chats
        // being the visible tab. Absent tab labels are not a contradiction: LINE has
        // shipped navigation whose sections expose no recognizable name, and treating
        // that silence as "not Chats" hides the banner mask everywhere.
        boolean navigationPresent = navigation.bounds().height() > 0;
        boolean contradictsChats = friendsSubviewSelected
                || (navigation.anotherTabSelected() && !navigation.chatsTabSelected());
        if (!navigationPresent || contradictsChats) {
            if (debugEnabled()) {
                logDecision("banner hidden"
                        + " navigation=" + navigationPresent
                        + " friendsSubview=" + friendsSubviewSelected
                        + " chatsVisible=" + navigation.chatsTabVisible()
                        + " chatsSelected=" + navigation.chatsTabSelected()
                        + " otherTabSelected=" + navigation.anotherTabSelected());
            }
            clearTracking();
            bannerMask.remove();
            return;
        }

        int navigationTop = navigation.bounds().top();
        LayoutMetrics layout = findLayoutMetrics(root, displayRect, density);
        AccessibilityNodeInfo anchor = findPromoAnchor(root, displayRect, navigationTop, density);
        AdRowGeometry.Box selected;
        if (anchor != null) {
            Rect anchorRect = new Rect();
            anchor.getBoundsInScreen(anchorRect);
            AdRowGeometry.Box detected = AdRowGeometry.select(
                    box(anchorRect), promoRowAncestors(anchor), box(displayRect), density);
            int clipTop = promoClipTop(layout, density);
            selected = AdRowGeometry.clipVertically(
                    detected,
                    clipTop,
                    AdRowGeometry.lowerBoundary(detected, layout.list(), navigationTop),
                    box(displayRect));
            rememberTracking(selected, layout);
        } else if (isChatsTab(navigation)) {
            // The textless detector infers the banner from a structural gap alone, so it
            // runs only when Chats is positively identified.
            selected = findPromoGap(layout, displayRect, density);
        } else {
            clearTracking();
            selected = emptyRow(displayRect);
        }
        if (debugEnabled()) {
            logDecision("banner " + (selected.height() > 0 ? "shown" : "hidden")
                    + " anchor=" + (anchor != null)
                    + " searchIdentified=" + layout.searchIdentified()
                    + " control=" + layout.controlTop() + ".." + layout.controlBottom()
                    + " list=" + (layout.hasList() ? layout.list().top() : "none")
                    + " navigationTop=" + navigationTop
                    + " chatsSelected=" + navigation.chatsTabSelected()
                    + " tracked=" + trackedBannerHeight + "+" + trackedListBottomOffset
                    + " mask=" + selected.top() + ".." + selected.bottom());
        }
        if (selected.height() <= 0) {
            bannerMask.remove();
            return;
        }
        bannerMask.show(selected);
    }

    private static boolean debugEnabled() {
        return Log.isLoggable(TAG, Log.DEBUG);
    }

    /** Logs only when the outcome changes, so a 500ms poll does not flood logcat. */
    private void logDecision(String decision) {
        if (decision.equals(lastDecision)) return;
        lastDecision = decision;
        Log.d(TAG, decision);
    }

    /**
     * Keeps the mask below the fixed header and, when LINE positively identifies its
     * Search control, below Search itself rather than merely below its top edge.
     */
    private int promoClipTop(LayoutMetrics layout, float density) {
        int headerBottom = fixedHeaderBottom(density);
        if (layout.searchIdentified()) {
            return Math.max(headerBottom, layout.controlBottom());
        }
        return Math.max(headerBottom, layout.controlTop());
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
        int maxOwnerWidth = Math.round(
                displayRect.width() * MAX_SELECTION_OWNER_WIDTH_FRACTION);

        while (!pending.isEmpty()) {
            AccessibilityNodeInfo node = pending.pop();
            Rect bounds = new Rect();
            node.getBoundsInScreen(bounds);
            if (!bounds.isEmpty()
                    && bounds.centerY() >= navigationTop
                    && onVisiblePage(bounds, displayRect)) {
                String label = searchableText(node);
                int tab = LineUiLabels.topLevelTab(label);
                if (tab >= 0) {
                    seenTabs[tab] = true;
                    labelBounds.add(box(bounds));
                    if (!scopedToNavigation) {
                        ancestorCandidates.addAll(nodeAndAncestorBoxes(node));
                    }
                    boolean selected = isSelectedControl(node, maxOwnerWidth)
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
        // Too few recognizable names to trust label-derived geometry. The names
        // themselves still count as evidence when they came from a validated
        // navigation container, which is what tells Chats apart from Home.
        if (distinctTabs < 2) {
            labelBounds.clear();
            ancestorCandidates.clear();
            if (!scopedToNavigation && structuralCandidates.isEmpty()) {
                chatsTabVisible = false;
                chatsTabSelected = false;
                anotherTabSelected = false;
            }
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

    /**
     * Detects the redesigned Friends subview only from a compact, selected control in
     * the upper area. A bare "contains Friends somewhere near the top" test matched
     * large containers and inherited selection from unrelated ancestors, which silently
     * suppressed the banner mask on the Chats tab.
     */
    private boolean isFriendsSubviewSelected(
            AccessibilityNodeInfo root,
            Rect displayRect,
            float density
    ) {
        int upperContentBottom = displayRect.top
                + Math.round(displayRect.height() * UPPER_CONTROLS_BOTTOM_FRACTION);
        int maxControlHeight = Math.round(MAX_CONTROL_HEIGHT_DP * density);
        int maxControlWidth = Math.round(displayRect.width() * MAX_SUBVIEW_WIDTH_FRACTION);
        int maxOwnerWidth = Math.round(
                displayRect.width() * MAX_SELECTION_OWNER_WIDTH_FRACTION);
        Deque<AccessibilityNodeInfo> pending = new ArrayDeque<>();
        pending.push(root);
        while (!pending.isEmpty()) {
            AccessibilityNodeInfo node = pending.pop();
            Rect bounds = new Rect();
            node.getBoundsInScreen(bounds);
            if (!bounds.isEmpty()
                    && onVisiblePage(bounds, displayRect)
                    && bounds.bottom <= upperContentBottom
                    && bounds.height() <= maxControlHeight
                    && bounds.width() <= maxControlWidth
                    && LineUiLabels.isFriendsSubview(searchableText(node))
                    && isSelectedControl(node, maxOwnerWidth)) {
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
            Rect displayRect,
            int navigationTop,
            float density
    ) {
        AccessibilityNodeInfo exact = null;
        int maxRowHeight = Math.round(MAX_PROMO_ROW_HEIGHT_DP * density);
        for (AccessibilityNodeInfo node : root.findAccessibilityNodeInfosByText("VOOM")) {
            Rect bounds = new Rect();
            node.getBoundsInScreen(bounds);
            if (bounds.isEmpty()
                    || bounds.top >= navigationTop
                    || !onVisiblePage(bounds, displayRect)) continue;
            if (LineUiLabels.isPromo(searchableText(node))
                    || LineUiLabels.isPromo(promoRowText(node, maxRowHeight))) {
                exact = smallerNode(exact, node);
            }
        }
        return exact;
    }

    /**
     * Promotional wording is often split across siblings: "LINE VOOM" in one node and
     * the recommendation phrase in another. Reads the enclosing compact row so the
     * qualifier can be found there, without widening the search to the whole screen.
     */
    private String promoRowText(AccessibilityNodeInfo anchor, int maxRowHeight) {
        AccessibilityNodeInfo row = anchor;
        AccessibilityNodeInfo current = anchor;
        for (int depth = 0; current != null && depth < 4; depth++) {
            if (depth > 0 && isScrollableNode(current)) break;
            Rect bounds = new Rect();
            current.getBoundsInScreen(bounds);
            if (!bounds.isEmpty() && bounds.height() <= maxRowHeight) row = current;
            current = current.getParent();
        }
        return subtreeText(row, 3);
    }

    /** Searchable text of a node and its descendants, to a bounded depth. */
    private static String subtreeText(AccessibilityNodeInfo root, int maxDepth) {
        StringBuilder text = new StringBuilder();
        Deque<AccessibilityNodeInfo> pending = new ArrayDeque<>();
        Deque<Integer> depths = new ArrayDeque<>();
        pending.push(root);
        depths.push(0);
        while (!pending.isEmpty()) {
            AccessibilityNodeInfo node = pending.pop();
            int depth = depths.pop();
            text.append(searchableText(node)).append(' ');
            if (depth >= maxDepth) continue;
            for (int i = 0; i < node.getChildCount(); i++) {
                AccessibilityNodeInfo child = node.getChild(i);
                if (child != null) {
                    pending.push(child);
                    depths.push(depth + 1);
                }
            }
        }
        return text.toString();
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
            if (!bounds.isEmpty() && onVisiblePage(bounds, displayRect)) {
                boolean controlGeometry = !isScrollableNode(node)
                        && visibleWidth(bounds, displayRect) >= minControlWidth
                        && bounds.height() <= maxControlHeight
                        && bounds.left >= displayRect.left + minControlInset
                        && bounds.right <= displayRect.right - minControlInset
                        && bounds.top < controlsLimit
                        && bounds.bottom <= controlsLimit;
                // LINE's Search bar carries no label of its own; the word lives in a
                // narrow child TextView. Reading the control's subtree is what lets the
                // bar be recognized as Search instead of an anonymous clickable box.
                if (controlGeometry
                        && (LineUiLabels.isSearch(searchableText(node))
                        || LineUiLabels.isSearch(subtreeText(node, 3)))) {
                    searchBounds.add(bounds);
                } else if (controlGeometry && node.isClickable()) {
                    clickableBounds.add(bounds);
                }
                if (isScrollableNode(node)
                        && visibleWidth(bounds, displayRect) >= minListWidth
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
        boolean searchIdentified = !searchBounds.isEmpty();
        List<Rect> controls = searchIdentified ? searchBounds : clickableBounds;
        for (Rect bounds : controls) {
            if (bounds.bottom > controlBottom) {
                controlTop = bounds.top;
                controlBottom = bounds.bottom;
            }
        }

        // The highest list that genuinely starts below the controls, or null. Never
        // substitute a synthetic edge: callers must be able to tell "the list is here"
        // from "no list was found", because only the former can bound the banner.
        Rect list = null;
        for (Rect bounds : scrollableBounds) {
            if ((controlBottom <= displayRect.top || bounds.top >= controlBottom)
                    && (list == null || bounds.top < list.top)) {
                list = bounds;
            }
        }
        return new LayoutMetrics(
                controlTop, controlBottom, searchIdentified, list == null ? null : box(list));
    }

    private AdRowGeometry.Box findPromoGap(
            LayoutMetrics layout,
            Rect displayRect,
            float density
    ) {
        AdRowGeometry.Box display = box(displayRect);
        // Both measuring and following the gap need a real list edge. Without one there
        // is nothing to measure against and nothing to follow, so abstain rather than
        // extrapolate from an invented boundary.
        if (!layout.hasList() || layout.controlBottom() <= displayRect.top) {
            clearTracking();
            return emptyRow(displayRect);
        }
        int listTop = layout.list().top();

        // Measure the live gap on every scan. Latching the first measurement and only
        // ever re-following it is how a full banner ends up under a sliver of a mask:
        // whatever height happened to be visible while LINE was still settling becomes
        // permanent, because nothing ever measures again.
        AdRowGeometry.Box measured = AdRowGeometry.fromGap(
                layout.controlBottom(), listTop, display, density);
        if (measured.height() > 0) {
            trackedBannerHeight = measured.height();
            trackedListBottomOffset = 0;
            return measured;
        }

        // Too short to be a banner but not yet closed: the banner is scrolling out
        // behind the header. Follow the last measured height down to nothing.
        if (trackedBannerHeight > 0
                && AdRowGeometry.isClosingGap(layout.controlBottom(), listTop, density)) {
            return AdRowGeometry.trackGap(
                    listTop - trackedListBottomOffset,
                    trackedBannerHeight,
                    promoClipTop(layout, density),
                    display);
        }
        clearTracking();
        return emptyRow(displayRect);
    }

    /**
     * Seeds the textless tracker from a located banner, but only for the layout the
     * tracker models: a banner sitting above the chat list, whose lower edge the list
     * follows as it scrolls. A promotion rendered inside the list has no such edge, so
     * tracking it would later drag the mask up over Search.
     */
    private void rememberTracking(AdRowGeometry.Box selected, LayoutMetrics layout) {
        if (selected.height() <= 0
                || layout.controlBottom() <= 0
                || !layout.hasList()
                || layout.list().top() < selected.bottom()) {
            clearTracking();
            return;
        }
        if (trackedBannerHeight <= 0) {
            trackedBannerHeight = selected.height();
            trackedListBottomOffset = Math.max(0, layout.list().top() - selected.bottom());
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

    /**
     * Row candidates for the promo anchor, stopping below any enclosing scrollable. When
     * LINE renders the promotion as a list item, the list itself is not its row, and
     * offering it as a candidate would mask chats below the banner.
     */
    private List<AdRowGeometry.Box> promoRowAncestors(AccessibilityNodeInfo anchor) {
        List<AdRowGeometry.Box> result = new ArrayList<>();
        AccessibilityNodeInfo current = anchor;
        while (current != null) {
            if (!result.isEmpty() && isScrollableNode(current)) break;
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

    /**
     * Whether the control owning this node reports itself selected. Selection is read
     * from the node and the ancestors that can still plausibly be that one control;
     * anything wider than {@code maxOwnerWidth} is a shared row or screen container,
     * whose selection says nothing about this node.
     */
    private static boolean isSelectedControl(AccessibilityNodeInfo node, int maxOwnerWidth) {
        AccessibilityNodeInfo current = node;
        for (int depth = 0; current != null && depth < 4; depth++) {
            Rect bounds = new Rect();
            current.getBoundsInScreen(bounds);
            if (depth > 0 && !bounds.isEmpty() && bounds.width() > maxOwnerWidth) {
                return false;
            }
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
                + " " + viewIdName(node))
                .trim()
                .toLowerCase(Locale.ROOT);
    }

    /**
     * The local part of a view id. The package qualifier is dropped because every LINE
     * id begins with {@code jp.naver.line.android}, which otherwise supplies the word
     * "line" to every node and satisfies the promotional-wording test on its own.
     */
    private static String viewIdName(AccessibilityNodeInfo node) {
        String id = safe(node.getViewIdResourceName());
        return id.substring(id.lastIndexOf('/') + 1);
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

    /** See {@link AdRowGeometry#onVisiblePage}: filters out adjacent ViewPager pages. */
    private static boolean onVisiblePage(Rect bounds, Rect displayRect) {
        return AdRowGeometry.onVisiblePage(box(bounds), box(displayRect));
    }

    /** Width of the part of a node the user can actually see. */
    private static int visibleWidth(Rect bounds, Rect displayRect) {
        return AdRowGeometry.visibleWidth(box(bounds), box(displayRect));
    }

    private static AdRowGeometry.Box emptyRow(Rect displayRect) {
        return new AdRowGeometry.Box(
                displayRect.left, displayRect.top, displayRect.right, displayRect.top);
    }

    /** LINE's own background behind every masked area, so the mask reads as empty space. */
    private int maskColor() {
        int nightMode = getResources().getConfiguration().uiMode
                & Configuration.UI_MODE_NIGHT_MASK;
        return nightMode == Configuration.UI_MODE_NIGHT_YES ? Color.BLACK : Color.WHITE;
    }

    private final class MaskOverlay {
        private final String title;
        private View view;
        private WindowManager.LayoutParams params;
        private AdRowGeometry.Box bounds;

        private MaskOverlay(String title) {
            this.title = title;
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
            view.setBackgroundColor(maskColor());
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

    /** {@code list} is null when no chat list below the controls was found. */
    private record LayoutMetrics(
            int controlTop,
            int controlBottom,
            boolean searchIdentified,
            AdRowGeometry.Box list
    ) {
        boolean hasList() { return list != null; }
    }

    private record NavigationState(
            AdRowGeometry.Box bounds,
            boolean chatsTabVisible,
            boolean chatsTabSelected,
            boolean anotherTabSelected
    ) {}
}
