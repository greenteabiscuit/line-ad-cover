package com.greenteabiscuit.lineadcover;

import static org.junit.Assert.assertEquals;

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
}
