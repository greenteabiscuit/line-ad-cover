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

    @Test public void recognizesLocalizedPromoWording() {
        assertTrue(LineUiLabels.isPromo("Trending now on LINE VOOM"));
        assertTrue(LineUiLabels.isPromo("LINE VOOMのおすすめ"));
        assertFalse(LineUiLabels.isPromo("VOOM"));
    }

    @Test public void recognizesSemanticSearchAndSelection() {
        assertTrue(LineUiLabels.isSearch("jp.naver.line.android:id/chat_search"));
        assertTrue(LineUiLabels.isSearch("トークを検索"));
        assertTrue(LineUiLabels.isSelected("Tab, Chats, selected"));
        assertTrue(LineUiLabels.isFriendsSubview("Friends"));
        assertTrue(LineUiLabels.isFriendsSubview("友だち"));
    }
}
