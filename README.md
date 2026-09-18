# LINE Ad Cover

A small, personal sideload Android app that visually masks the LINE VOOM promotional banner in LINE's top-level **Chats** tab and covers **Home** and the final three sections of LINE's five-section bottom navigation, leaving only **Chats** visible. It supports both the original navigation and the redesigned version that replaces **Voom** with **Shopping**. It uses an accessibility service restricted to `jp.naver.line.android`; it does not click the banner, modify LINE, store content, or transmit data.

## Build and install

Requirements: JDK 17 and Android SDK 36.1.

```bash
./gradlew test assembleDebug
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

## Enable and use

1. Open **LINE Ad Cover**.
2. Tap **Open Accessibility Settings** and enable **LINE promotional banner cover**.
3. Return to the app and tap **Open LINE**, then open Chats.

Whenever LINE's five-section bottom navigation is present, the service locates the shared navigation row by its labels or five-column structure and places fixed, theme-matched, non-touchable masks over **Home** and its final three sections. Only **Chats**, the second section, remains visible. This works with both **Voom** and the redesigned **Shopping** tab.

The banner mask requires that the navigation is present and that nothing contradicts **Chats** being the current tab — another selected top-level tab, or the redesigned **Friends** subview selected in a compact control at the top of the screen. Navigation whose sections expose no recognizable name is not treated as a contradiction, because LINE ships builds like that and refusing them hides the mask everywhere. Given that, the service looks for the LINE VOOM promotional accessibility label, including localized recommendation wording split across the banner's own row. It masks the enclosing row, stopping short of any enclosing scrollable so a promotion rendered as a list item does not drag the mask over the chats below it. It clamps the resulting full-row rectangle to the current display and places a non-touchable accessibility overlay over it, clipped beneath LINE's fixed header and Search control and above the bottom navigation. Every mask is painted in LINE's own background colour for the current theme — white in light mode, black in dark mode — so it reads as empty space rather than a bar.

If the label is absent, the app instead measures the banner-sized gap between the live Search control and the scrollable chat list. Search is recognized from the text inside the control, because LINE's search bar carries no label of its own — the word lives in a narrow child view. That gap is re-measured on every scan; tracking only takes over once the gap is too short to be a banner but not yet closed, which is the banner scrolling out behind the header. Because this detector infers the banner from layout alone, it runs only when **Chats** is positively identified and a real chat list was found; it never extrapolates from an assumed list position. Opening an individual conversation removes all masks because the top-level tab navigation is no longer present.

**Only nodes on the visible page count.** LINE's five top-level screens are `ViewPager` pages, so the Home page is laid out at negative x and the Shopping page beyond the right edge — both fully populated in the accessibility tree, and both exactly as wide as the display. Every geometry scan therefore measures a node's *visible* width rather than its own, which reads zero for a page sitting beside the screen. Without that check an off-screen page's scrollable region can be mistaken for the chat list, and since those pages carry remote content that changes without any app update, the failure appears out of nowhere on a build that worked for days.

## Debugging a misplaced mask

LINE's accessibility tree is the only specification this app has, and it changes without warning, so the service can record why it chose the rectangle it chose. The log is off unless you ask for it:

```bash
adb shell setprop log.tag.LineAdCover DEBUG
adb logcat -s LineAdCover
adb shell setprop log.tag.LineAdCover INFO   # off again
```

Each line reports one outcome, and repeats are suppressed so the 500ms poll does not flood the log:

```
banner shown anchor=false searchIdentified=true control=237..350 list=644
             navigationTop=2054 chatsSelected=true tracked=294+0 mask=350..644
```

Compare `control`, `list`, and `mask` against the real layout, which you can read with `adb shell uiautomator dump`. Note that `uiautomator` omits views marked not-important-for-accessibility while this service includes them, so the service sometimes sees nodes the dump does not. The log records geometry and detector outcomes only — never chat content.

## Privacy and limitations

- Accessibility access is used only to locate and mask the promotional banner and selected bottom navigation sections. No data is stored or transmitted.
- The app **masks** the row; it cannot remove or reflow LINE's layout, so the covered space remains.
- The overlay is non-touchable. Search and the first chat row should remain usable because the cover is constrained to a plausible promo-row ancestor (or a density-aware fallback around the phrase).
- LINE UI or accessibility-tree changes may require detector updates.
- This project has unit coverage for geometry selection, but actual LINE versions, devices, display cutouts, split-screen behavior, and OEM accessibility behavior must be validated on a device.
