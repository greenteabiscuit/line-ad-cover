package com.greenteabiscuit.lineadcover;

import static org.junit.Assert.assertEquals;

import org.junit.Test;

import java.util.List;

public final class NavigationGeometryTest {
    private static final AdRowGeometry.Box DISPLAY = new AdRowGeometry.Box(0, 0, 1080, 2340);

    @Test public void choosesBottomNavigationInsteadOfFullWindowAncestor() {
        AdRowGeometry.Box result = NavigationGeometry.selectContainer(
                List.of(
                        new AdRowGeometry.Box(0, 1810, 1080, 1980),
                        new AdRowGeometry.Box(0, 2100, 1080, 2268),
                        DISPLAY),
                DISPLAY,
                3f);

        assertEquals(new AdRowGeometry.Box(0, 2100, 1080, 2268), result);
    }

    @Test public void rejectsWideConversationContentAwayFromBottom() {
        AdRowGeometry.Box result = NavigationGeometry.selectContainer(
                List.of(new AdRowGeometry.Box(0, 1200, 1080, 1368)),
                DISPLAY,
                3f);

        assertEquals(new AdRowGeometry.Box(0, 0, 1080, 0), result);
    }

    @Test public void estimatesNavigationWhenOnlyLabelsAreExposed() {
        AdRowGeometry.Box result = NavigationGeometry.estimateFromLabels(
                List.of(
                        new AdRowGeometry.Box(168, 2160, 264, 2210),
                        new AdRowGeometry.Box(600, 2160, 690, 2210)),
                2268,
                DISPLAY,
                3f);

        assertEquals(new AdRowGeometry.Box(0, 2076, 1080, 2268), result);
    }

    @Test public void coversLastThreeOfFiveTabs() {
        AdRowGeometry.Box result = NavigationGeometry.lastThreeTabs(
                new AdRowGeometry.Box(20, 2100, 1060, 2268));

        assertEquals(new AdRowGeometry.Box(436, 2100, 1060, 2268), result);
    }

    @Test public void coversHomeAndLeavesOnlyChatsExposedWithOffsetAndRounding() {
        AdRowGeometry.Box navigation = new AdRowGeometry.Box(23, 2100, 1066, 2268);
        AdRowGeometry.Box home = NavigationGeometry.homeTab(navigation);
        AdRowGeometry.Box lastThree = NavigationGeometry.lastThreeTabs(navigation);

        assertEquals(new AdRowGeometry.Box(23, 2100, 232, 2268), home);
        assertEquals(new AdRowGeometry.Box(440, 2100, 1066, 2268), lastThree);
        assertEquals(208, lastThree.left() - home.right());
    }

    @Test public void missingNavigationProducesNoHomeMaskHeight() {
        AdRowGeometry.Box navigation = NavigationGeometry.selectContainer(
                List.of(), DISPLAY, 3f);

        assertEquals(0, NavigationGeometry.homeTab(navigation).height());
    }
}
