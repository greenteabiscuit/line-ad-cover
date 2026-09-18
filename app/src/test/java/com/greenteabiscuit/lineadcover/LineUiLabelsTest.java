package com.greenteabiscuit.lineadcover;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public final class LineUiLabelsTest {
    @Test public void recognizesRedesignedShoppingTab() {
        assertEquals(2, LineUiLabels.topLevelTab("Tab, Shopping"));
        assertEquals(2, LineUiLabels.topLevelTab("ショッピング、タブ"));
        assertEquals(2, LineUiLabels.topLevelTab(
                "jp.naver.line.android:id/bottom_navigation_shopping"));
    }

    @Test public void recognizesChatsRegardlessOfLabelOrder() {
        assertEquals(1, LineUiLabels.topLevelTab("Chats, selected, tab 2 of 5"));
        assertEquals(1, LineUiLabels.topLevelTab("Tab 2 of 5: Chats"));
        assertEquals(1, LineUiLabels.topLevelTab("トーク、選択中"));
    }

    @Test public void recognizesOldAndAlternateFinalTabs() {
        assertEquals(2, LineUiLabels.topLevelTab("VOOM"));
        assertEquals(3, LineUiLabels.topLevelTab("News"));
        assertEquals(4, LineUiLabels.topLevelTab("Wallet"));
        assertEquals(4, LineUiLabels.topLevelTab("Apps"));
    }

    @Test public void treatsLabelNamingSeveralTabsAsUnknown() {
        assertEquals(-1, LineUiLabels.topLevelTab("Home, Chats, VOOM, News, Wallet"));
        assertEquals(-1, LineUiLabels.topLevelTab("home_tab_chats"));
    }

    @Test public void recognizesLocalizedPromoWording() {
        assertTrue(LineUiLabels.isPromo("Trending now on LINE VOOM"));
        assertTrue(LineUiLabels.isPromo("LINE VOOMのおすすめ"));
        assertFalse(LineUiLabels.isPromo("VOOM"));
    }

    /**
     * The service strips the {@code jp.naver.line.android:id/} qualifier before matching,
     * so a bare VOOM node no longer borrows the "line" the promo wording requires.
     */
    @Test public void rejectsPromoQualifiedOnlyByTheViewIdPackage() {
        assertFalse(LineUiLabels.isPromo("voom voom_banner_title"));
        assertTrue(LineUiLabels.isPromo("voom jp.naver.line.android:id/voom_banner_title"));
    }

    @Test public void rejectsNegatedSelectionWording() {
        assertFalse(LineUiLabels.isSelected("Tab, Home, not selected"));
        assertFalse(LineUiLabels.isSelected("ホーム、未選択"));
        assertFalse(LineUiLabels.isSelected("Tab, VOOM, unselected"));
    }

    @Test public void recognizesSemanticSearchAndSelection() {
        assertTrue(LineUiLabels.isSearch("jp.naver.line.android:id/chat_search"));
        assertTrue(LineUiLabels.isSearch("トークを検索"));
        assertTrue(LineUiLabels.isSelected("Tab, Chats, selected"));
        assertTrue(LineUiLabels.isFriendsSubview("Friends"));
        assertTrue(LineUiLabels.isFriendsSubview("友だち"));
    }
}
