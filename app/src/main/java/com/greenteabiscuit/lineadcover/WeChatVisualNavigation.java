package com.greenteabiscuit.lineadcover;

import android.accessibilityservice.AccessibilityService.ScreenshotResult;
import android.graphics.Bitmap;
import android.graphics.Rect;
import android.hardware.HardwareBuffer;

import com.google.mlkit.vision.common.InputImage;
import com.google.mlkit.vision.text.Text;
import com.google.mlkit.vision.text.TextRecognition;
import com.google.mlkit.vision.text.TextRecognizer;
import com.google.mlkit.vision.text.chinese.ChineseTextRecognizerOptions;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

/** On-device fallback for WeChat builds that expose an empty accessibility tree. */
final class WeChatVisualNavigation {
    record Detection(AdRowGeometry.Box navigation, int color) {}

    private final TextRecognizer recognizer = TextRecognition.getClient(
            new ChineseTextRecognizerOptions.Builder().build());

    /** Search for the row within the bottom 144dp, then pass only that row to OCR. */
    void detect(ScreenshotResult screenshot, Rect window, int bottomInset, float density,
            Consumer<Detection> callback) {
        Bitmap full;
        try (HardwareBuffer buffer = screenshot.getHardwareBuffer()) {
            Bitmap hardware = Bitmap.wrapHardwareBuffer(buffer, screenshot.getColorSpace());
            if (hardware == null) {
                callback.accept(null);
                return;
            }
            full = hardware.copy(Bitmap.Config.ARGB_8888, false);
            hardware.recycle();
        }
        // Window screenshots use window-local coordinates. Reject unexpected scaling
        // instead of positioning a screen-space overlay from the wrong coordinate frame.
        if (full.getWidth() != window.width() || full.getHeight() != window.height()) {
            full.recycle();
            callback.accept(null);
            return;
        }
        int bottom = full.getHeight() - bottomInset;
        int top = Math.max(0, bottom - Math.round(144f * density));
        if (bottom <= top || full.getWidth() < 6) {
            full.recycle();
            callback.accept(null);
            return;
        }
        Bitmap strip = Bitmap.createBitmap(full, 0, top, full.getWidth(), bottom - top);
        if (strip != full) full.recycle();
        int[] leftEdge = new int[strip.getHeight()];
        int[] rightEdge = new int[strip.getHeight()];
        strip.getPixels(leftEdge, 0, 1, 2, 0, 1, strip.getHeight());
        strip.getPixels(rightEdge, 0, 1, strip.getWidth() - 3, 0, 1, strip.getHeight());
        int rowTop = WeChatNavigation.backgroundTop(leftEdge, rightEdge);
        if (rowTop < 0) {
            strip.recycle();
            callback.accept(null);
            return;
        }
        int color = rightEdge[rightEdge.length - 1];
        AdRowGeometry.Box display = new AdRowGeometry.Box(
                window.left, window.top, window.right, window.bottom);
        AdRowGeometry.Box row = new AdRowGeometry.Box(
                window.left, window.top + top + rowTop, window.right, window.top + bottom);
        if (NavigationGeometry.selectContainer(List.of(row), display, density).height() <= 0) {
            strip.recycle();
            callback.accept(null);
            return;
        }
        Bitmap navigationImage = Bitmap.createBitmap(
                strip, 0, rowTop, strip.getWidth(), strip.getHeight() - rowTop);
        if (navigationImage != strip) strip.recycle();
        recognizer.process(InputImage.fromBitmap(navigationImage, 0)).addOnCompleteListener(task -> {
            try {
                if (!task.isSuccessful()) {
                    callback.accept(null);
                    return;
                }
                List<WeChatNavigation.Tab> tabs = new ArrayList<>();
                for (Text.TextBlock block : task.getResult().getTextBlocks()) {
                    for (Text.Line line : block.getLines()) {
                        addTab(tabs, line.getText(), line.getBoundingBox(), window.left,
                                row.top());
                        for (Text.Element element : line.getElements()) {
                            addTab(tabs, element.getText(), element.getBoundingBox(), window.left,
                                    row.top());
                        }
                    }
                }
                AdRowGeometry.Box navigation = WeChatNavigation.selectContainer(
                        List.of(row), tabs, display, density);
                callback.accept(navigation.height() > 0 ? new Detection(navigation, color) : null);
            } finally {
                navigationImage.recycle();
            }
        });
    }

    private static void addTab(List<WeChatNavigation.Tab> tabs, String text, Rect bounds,
            int offsetX, int offsetY) {
        int index = WeChatNavigation.tabIndex(text);
        if (index >= 0 && bounds != null) {
            tabs.add(new WeChatNavigation.Tab(index, new AdRowGeometry.Box(
                    bounds.left + offsetX, bounds.top + offsetY,
                    bounds.right + offsetX, bounds.bottom + offsetY)));
        }
    }

    void close() {
        recognizer.close();
    }
}
