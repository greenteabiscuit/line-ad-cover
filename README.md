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

Whenever LINE's five-section bottom navigation is present, the service locates the shared navigation row by its labels or five-column structure and places fixed, theme-matched, non-touchable masks over **Home** and its final three sections. Only **Chats**, the second section, remains visible. This works with both **Voom** and the redesigned **Shopping** tab. For the promotional banner, the service additionally verifies that **Chats** is the active tab (when LINE exposes selection state). It looks for the LINE VOOM promotional accessibility label, including localized recommendation wording. If LINE renders the promotion without exposing its text, the app detects the banner-sized gap between the live Search control and the scrollable chat list instead. It clamps the resulting full-row rectangle to the current display and places a non-touchable white accessibility overlay over it. As the list scrolls, the overlay follows the banner's lower edge and is clipped beneath LINE's live header/Search position until the banner is fully offscreen. Selecting the redesigned **Friends** subview removes the banner mask but keeps all non-Chats tabs covered. Opening an individual conversation removes all masks because the top-level tab navigation is no longer present.

## Privacy and limitations

- Accessibility access is used only to locate and mask the promotional banner and selected bottom navigation sections. No data is stored or transmitted.
- The app **masks** the row; it cannot remove or reflow LINE's layout, so the covered space remains.
- The overlay is non-touchable. Search and the first chat row should remain usable because the cover is constrained to a plausible promo-row ancestor (or a density-aware fallback around the phrase).
- LINE UI or accessibility-tree changes may require detector updates.
- This project has unit coverage for geometry selection, but actual LINE versions, devices, display cutouts, split-screen behavior, and OEM accessibility behavior must be validated on a device.
