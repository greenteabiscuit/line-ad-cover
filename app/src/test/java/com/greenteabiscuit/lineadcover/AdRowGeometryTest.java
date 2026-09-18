package com.greenteabiscuit.lineadcover;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.util.List;

public final class AdRowGeometryTest {
    private static final AdRowGeometry.Box DISPLAY = new AdRowGeometry.Box(0, 0, 1080, 2340);

    @Test public void choosesPlausibleFullRowAncestor() {
        AdRowGeometry.Box result = AdRowGeometry.select(
                new AdRowGeometry.Box(120, 410, 520, 455),
                List.of(
                        new AdRowGeometry.Box(90, 380, 600, 500),
                        new AdRowGeometry.Box(0, 340, 1080, 610),
                        DISPLAY),
                DISPLAY, 3f);

        assertEquals(new AdRowGeometry.Box(0, 340, 1080, 610), result);
    }

    @Test public void rejectsOverlyLargeRootAncestor() {
        AdRowGeometry.Box result = AdRowGeometry.select(
                new AdRowGeometry.Box(100, 400, 500, 450),
                List.of(DISPLAY), DISPLAY, 2f);

        assertEquals(new AdRowGeometry.Box(0, 272, 1080, 498), result);
    }

    @Test public void fallbackExpandsAndClampsAroundTextAnchor() {
        AdRowGeometry.Box result = AdRowGeometry.select(
                new AdRowGeometry.Box(20, 20, 500, 60),
                List.of(), DISPLAY, 2f);

        assertEquals(new AdRowGeometry.Box(0, 0, 1080, 108), result);
    }

    @Test public void acceptsBannerSizedStructuralGap() {
        AdRowGeometry.Box result = AdRowGeometry.fromGap(350, 644, DISPLAY, 2.625f);

        assertEquals(new AdRowGeometry.Box(0, 350, 1080, 644), result);
    }

    @Test public void rejectsTinyStructuralGap() {
        AdRowGeometry.Box result = AdRowGeometry.fromGap(350, 360, DISPLAY, 2.625f);

        assertEquals(new AdRowGeometry.Box(0, 0, 1080, 0), result);
    }

    @Test public void rejectsOversizedStructuralGap() {
        AdRowGeometry.Box result = AdRowGeometry.fromGap(350, 1100, DISPLAY, 2.625f);

        assertEquals(new AdRowGeometry.Box(0, 0, 1080, 0), result);
    }

    @Test public void tracksGapWithMovingListEdge() {
        AdRowGeometry.Box result = AdRowGeometry.trackGap(478, 294, 250, DISPLAY);

        assertEquals(new AdRowGeometry.Box(0, 250, 1080, 478), result);
    }

    @Test public void shrinksGapAsItMovesBehindHeader() {
        AdRowGeometry.Box result = AdRowGeometry.trackGap(314, 294, 250, DISPLAY);

        assertEquals(new AdRowGeometry.Box(0, 250, 1080, 314), result);
    }

    @Test public void hidesGapOnceItMovesBehindHeader() {
        AdRowGeometry.Box result = AdRowGeometry.trackGap(250, 294, 250, DISPLAY);

        assertEquals(new AdRowGeometry.Box(0, 0, 1080, 0), result);
    }

    @Test public void clipsDetectedRowAtLiveListEdge() {
        AdRowGeometry.Box result = AdRowGeometry.clipVertically(
                new AdRowGeometry.Box(0, 250, 1080, 544), 250, 320, DISPLAY);

        assertEquals(new AdRowGeometry.Box(0, 250, 1080, 320), result);
    }

    @Test public void hidesDetectedRowBehindFixedHeader() {
        AdRowGeometry.Box result = AdRowGeometry.clipVertically(
                new AdRowGeometry.Box(0, 100, 1080, 240), 250, 320, DISPLAY);

        assertEquals(new AdRowGeometry.Box(0, 0, 1080, 0), result);
    }

    @Test public void listEdgeBelowTheRowBoundsIt() {
        int boundary = AdRowGeometry.lowerBoundary(
                new AdRowGeometry.Box(0, 250, 1080, 544),
                new AdRowGeometry.Box(0, 544, 1080, 2000),
                1900);

        assertEquals(544, boundary);
    }

    @Test public void missingListFallsBackToNavigationEdge() {
        int boundary = AdRowGeometry.lowerBoundary(
                new AdRowGeometry.Box(0, 250, 1080, 544), null, 1900);

        assertEquals(1900, boundary);
    }

    @Test public void listContainingThePromoCannotBoundIt() {
        int boundary = AdRowGeometry.lowerBoundary(
                new AdRowGeometry.Box(0, 400, 1080, 600),
                new AdRowGeometry.Box(0, 400, 1080, 1900),
                1900);

        assertEquals(1900, boundary);
    }

    /**
     * LINE lays its top-level screens out as ViewPager pages, so the Home and Shopping
     * pages sit beside the display, fully populated and exactly display-width. These are
     * the real bounds observed on a Pixel 7 Pro: the off-screen page's scrollable at
     * top 428 was beating the real chat list at top 644, collapsing the measured banner
     * gap to a 78px sliver.
     */
    @Test public void adjacentViewPagerPagesAreNotOnTheVisiblePage() {
        AdRowGeometry.Box display = new AdRowGeometry.Box(0, 0, 1080, 2340);
        AdRowGeometry.Box offScreenRight = new AdRowGeometry.Box(1080, 428, 2160, 655);
        AdRowGeometry.Box offScreenLeft = new AdRowGeometry.Box(-1080, 276, 0, 630);
        AdRowGeometry.Box realChatList = new AdRowGeometry.Box(0, 644, 1080, 2148);
        AdRowGeometry.Box chatsTab = new AdRowGeometry.Box(240, 2054, 408, 2277);

        assertFalse(AdRowGeometry.onVisiblePage(offScreenRight, display));
        assertFalse(AdRowGeometry.onVisiblePage(offScreenLeft, display));
        assertTrue(AdRowGeometry.onVisiblePage(realChatList, display));
        assertTrue(AdRowGeometry.onVisiblePage(chatsTab, display));
        assertEquals(0, AdRowGeometry.visibleWidth(offScreenRight, display));
        assertEquals(1080, AdRowGeometry.visibleWidth(realChatList, display));
    }

    /** A half-swiped page is too narrow on screen to be mistaken for the chat list. */
    @Test public void aPartlySwipedPageIsMeasuredByItsVisiblePortion() {
        AdRowGeometry.Box display = new AdRowGeometry.Box(0, 0, 1080, 2340);

        assertEquals(280, AdRowGeometry.visibleWidth(
                new AdRowGeometry.Box(800, 644, 1880, 2148), display));
    }

    /**
     * Pixel 7 Pro, LINE 26.14: Search bottom 350, chat list top 644, density 2.625.
     * This is the fallback used whenever the promotional label is absent.
     */
    @Test public void measuresTheRealLineChatsGap() {
        AdRowGeometry.Box display = new AdRowGeometry.Box(0, 0, 1080, 2340);

        AdRowGeometry.Box result = AdRowGeometry.fromGap(350, 644, display, 2.625f);

        assertEquals(new AdRowGeometry.Box(0, 350, 1080, 644), result);
    }

    @Test public void treatsAShrinkingGapAsClosingButNotAClosedOne() {
        assertTrue(AdRowGeometry.isClosingGap(350, 420, 2.625f));
        assertFalse(AdRowGeometry.isClosingGap(350, 350, 2.625f));
        assertFalse(AdRowGeometry.isClosingGap(350, 644, 2.625f));
    }

    @Test public void promoRenderedInsideTheListSurvivesClipping() {
        AdRowGeometry.Box row = new AdRowGeometry.Box(0, 400, 1080, 600);
        AdRowGeometry.Box list = new AdRowGeometry.Box(0, 400, 1080, 1900);

        AdRowGeometry.Box result = AdRowGeometry.clipVertically(
                row, 400, AdRowGeometry.lowerBoundary(row, list, 1900), DISPLAY);

        assertEquals(row, result);
    }
}
