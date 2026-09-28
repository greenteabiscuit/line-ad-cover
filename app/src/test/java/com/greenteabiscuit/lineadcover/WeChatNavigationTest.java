package com.greenteabiscuit.lineadcover;

import static org.junit.Assert.assertEquals;

import org.junit.Test;

import java.util.ArrayList;
import java.util.List;

public final class WeChatNavigationTest {
    private static final AdRowGeometry.Box DISPLAY = new AdRowGeometry.Box(0, 0, 1080, 2340);
    private static final AdRowGeometry.Box NAVIGATION =
            new AdRowGeometry.Box(0, 2100, 1080, 2277);
    // Synthetic layout; deliberately different label widths, not a device capture.
    private static final List<WeChatNavigation.Tab> TABS = List.of(
            new WeChatNavigation.Tab(0, new AdRowGeometry.Box(90, 2200, 178, 2240)),
            new WeChatNavigation.Tab(1, new AdRowGeometry.Box(339, 2200, 474, 2240)),
            new WeChatNavigation.Tab(2, new AdRowGeometry.Box(614, 2200, 733, 2240)),
            new WeChatNavigation.Tab(3, new AdRowGeometry.Box(926, 2200, 967, 2240)));

    @Test public void recognizesEnglishAndChineseIndividualLabels() {
        for (String[] labels : List.of(
                new String[]{" Chats ", "CONTACTS", "Discover", "Me"},
                new String[]{"WeChat", "Contacts", "Discover", "Me"},
                new String[]{"微信", "通讯录", "发现", "我"},
                new String[]{"微信", "通訊錄", "發現", "我"})) {
            for (int index = 0; index < labels.length; index++) {
                assertEquals(index, WeChatNavigation.tabIndex(labels[index]));
            }
        }
        assertEquals(-1, WeChatNavigation.tabIndex(null));
        assertEquals(-1, WeChatNavigation.tabIndex("Send me contacts"));
        assertEquals(-1, WeChatNavigation.tabIndex("Chats Contacts Discover Me"));
    }

    @Test public void requiresACompactSharedBottomAncestor() {
        assertEquals(NAVIGATION, WeChatNavigation.selectContainer(
                List.of(DISPLAY, NAVIGATION, new AdRowGeometry.Box(0, 2200, 1080, 2240)),
                TABS, DISPLAY, 2.625f));
        assertEquals(0, WeChatNavigation.selectContainer(
                List.of(DISPLAY), TABS, DISPLAY, 2.625f).height());
    }

    @Test public void everyTabMustBePresentNotJustThreeNamesOrDuplicateLabels() {
        for (int omitted = 0; omitted < 4; omitted++) {
            List<WeChatNavigation.Tab> missing = new ArrayList<>(TABS);
            missing.remove(omitted);
            missing.add(missing.get(0));
            assertEquals(0, WeChatNavigation.selectContainer(
                    List.of(NAVIGATION), missing, DISPLAY, 2.625f).height());
        }
    }

    @Test public void labelsMustBeInTheExpectedColumns() {
        List<WeChatNavigation.Tab> swapped = new ArrayList<>(TABS);
        swapped.set(1, new WeChatNavigation.Tab(1, TABS.get(2).bounds()));
        swapped.set(2, new WeChatNavigation.Tab(2, TABS.get(1).bounds()));

        assertEquals(0, WeChatNavigation.selectContainer(
                List.of(NAVIGATION), swapped, DISPLAY, 2.625f).height());
    }

    @Test public void offScreenLabelsCannotCompleteNavigation() {
        List<WeChatNavigation.Tab> offScreen = new ArrayList<>(TABS);
        offScreen.set(3, new WeChatNavigation.Tab(3,
                new AdRowGeometry.Box(2006, 2200, 2047, 2240)));

        assertEquals(0, WeChatNavigation.selectContainer(
                List.of(NAVIGATION), offScreen, DISPLAY, 2.625f).height());
    }

    @Test public void labelsMustBeAlignedInOneRow() {
        List<WeChatNavigation.Tab> staggered = new ArrayList<>(TABS);
        staggered.set(3, new WeChatNavigation.Tab(3,
                new AdRowGeometry.Box(926, 2110, 967, 2150)));

        assertEquals(0, WeChatNavigation.selectContainer(
                List.of(NAVIGATION), staggered, DISPLAY, 2.625f).height());
    }

    @Test public void keepsChatsAndCoversOnlyTheLastThreeOfFourSections() {
        assertEquals(new AdRowGeometry.Box(270, 2100, 1080, 2277),
                WeChatNavigation.hiddenTabs(NAVIGATION));
        assertEquals(new AdRowGeometry.Box(284, 2100, 1066, 2277),
                WeChatNavigation.hiddenTabs(new AdRowGeometry.Box(23, 2100, 1066, 2277)));
    }

    @Test public void locatesBackgroundEdgeDespiteSmallGradient() {
        // Chat background, divider, then the mildly translucent dark navigation.
        assertEquals(2, WeChatNavigation.backgroundTop(
                new int[]{0xff191919, 0xff2f2f2f, 0xff242424, 0xff232323},
                new int[]{0xff191919, 0xff2e2e2e, 0xff242424, 0xff232323}));
        assertEquals(2, WeChatNavigation.backgroundTop(
                new int[]{0xffffffff, 0xffdddddd, 0xfff6f6f6, 0xfff7f7f7},
                new int[]{0xffffffff, 0xffdddddd, 0xfff8f8f8, 0xfff7f7f7}));
    }

    @Test public void uniformBackgroundOrOneSidedChangeCannotSupplyRowEdge() {
        assertEquals(-1, WeChatNavigation.backgroundTop(
                new int[]{0xff232323, 0xff232323, 0xff232323},
                new int[]{0xff232323, 0xff232323, 0xff232323}));
        assertEquals(-1, WeChatNavigation.backgroundTop(
                new int[]{0xff191919, 0xff232323, 0xff232323},
                new int[]{0xff232323, 0xff232323, 0xff232323}));
    }

    @Test public void conversationWithoutNavigationHasNoMask() {
        AdRowGeometry.Box navigation = WeChatNavigation.selectContainer(
                List.of(), List.of(), DISPLAY, 2.625f);

        assertEquals(0, WeChatNavigation.hiddenTabs(navigation).height());
    }
}
