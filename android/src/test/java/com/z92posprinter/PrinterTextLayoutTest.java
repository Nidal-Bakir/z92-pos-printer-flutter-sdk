package com.z92posprinter;

import android.graphics.Bitmap;
import android.graphics.Color;
import android.text.Layout;
import android.text.StaticLayout;
import com.zcs.sdk.Printer;
import com.zcs.sdk.print.PrnStrFormat;
import com.zcs.sdk.print.PrnTextFont;
import com.zcs.sdk.print.PrnTextStyle;
import io.flutter.plugin.common.MethodCall;
import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.GraphicsMode;

import static org.junit.Assert.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@RunWith(RobolectricTestRunner.class)
@Config(manifest = Config.NONE, sdk = 33)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
public class PrinterTextLayoutTest {
    private PrnStrFormat format(Layout.Alignment alignment) {
        PrnStrFormat format = new PrnStrFormat();
        format.setFont(PrnTextFont.MONOSPACE);
        format.setStyle(PrnTextStyle.NORMAL);
        format.setTextSize(24);
        format.setAli(alignment);
        return format;
    }

    @Test public void leftRightAndCenterUsePaperEdgesForEachParagraph() {
        for (String text : new String[]{"English", "موقف السيارات", "المبلغ: 15000 ل.س",
                "Ticket التذكرة 123", "123 التذكرة", "\u200f12345",
                "English\nالعربية\n\n12345\nNext\n"}) {
            for (Layout.Alignment alignment : new Layout.Alignment[]{
                    Layout.Alignment.ALIGN_NORMAL, Layout.Alignment.ALIGN_OPPOSITE,
                    Layout.Alignment.ALIGN_CENTER}) {
                StaticLayout layout = PrinterTextLayout.layout(text, format(alignment), 384);
                for (int line = 0; line < layout.getLineCount(); line++) {
                    float left = layout.getLineLeft(line);
                    float right = layout.getLineRight(line);
                    if (left == right) continue; // Empty paragraphs have no ink to position.
                    if (alignment == Layout.Alignment.ALIGN_NORMAL) {
                        assertEquals(text + " left line " + line, 0, left, 1);
                    } else if (alignment == Layout.Alignment.ALIGN_OPPOSITE) {
                        assertEquals(text + " right line " + line, 384, right, 1);
                    } else {
                        assertEquals(text + " centered line " + line, 384, left + right, 1);
                    }
                }
            }
        }
    }

    @Test public void wrappedArabicRetainsDirectionAndBlankLinesWithoutExtraParagraphs() {
        String text = "هذه تذكرة موقف السيارات رقم 12345 والمبلغ الإجمالي خمسة عشر ألف ليرة\n\nEnglish\n";
        StaticLayout layout = PrinterTextLayout.layout(text, format(Layout.Alignment.ALIGN_OPPOSITE), 160);
        assertTrue(layout.getLineCount() > 4);
        assertEquals(text, layout.getText().toString());
        assertEquals(Layout.DIR_RIGHT_TO_LEFT, layout.getParagraphDirection(0));
        for (int line = 0; line < layout.getLineCount(); line++) {
            if (layout.getLineMax(line) > 0) assertEquals(160, layout.getLineRight(line), 1);
        }
    }

    @Test public void englishAndCenteredTextKeepVendorRenderingPath() {
        assertFalse(PrinterTextLayout.needed("English\n12345", format(Layout.Alignment.ALIGN_NORMAL)));
        assertFalse(PrinterTextLayout.needed("العربية", format(Layout.Alignment.ALIGN_CENTER)));
        assertTrue(PrinterTextLayout.needed("English\nالعربية", format(Layout.Alignment.ALIGN_OPPOSITE)));
    }

    @Test public void columnsRenderInTheirOwnWidthsAtBothPaperSizes() {
        for (int width : new int[]{384, 576}) {
            Bitmap bitmap = PrinterTextLayout.render(new String[]{"العربية\nEnglish", "123", "المبلغ"},
                    new int[]{60, 15, 25}, new PrnStrFormat[]{
                            format(Layout.Alignment.ALIGN_NORMAL), format(Layout.Alignment.ALIGN_CENTER),
                            format(Layout.Alignment.ALIGN_OPPOSITE)}, width);
            try {
                assertEquals(width, bitmap.getWidth());
                boolean hasInk = false;
                for (int y = 0; y < bitmap.getHeight() && !hasInk; y++) {
                    for (int x = 0; x < bitmap.getWidth(); x++) {
                        if (bitmap.getPixel(x, y) != Color.WHITE) { hasInk = true; break; }
                    }
                }
                assertTrue(hasInk);
            } finally {
                bitmap.recycle();
            }
        }
    }

    private Printer printer(int width) throws Exception {
        Printer printer = mock(Printer.class);
        Field buffer = Printer.class.getDeclaredField("h");
        buffer.setAccessible(true);
        buffer.set(printer, new ArrayList<Bitmap>());
        Field paperWidth = Printer.class.getDeclaredField("d");
        paperWidth.setAccessible(true);
        paperWidth.setInt(printer, width);
        return printer;
    }

    private MethodCall call(String method, Object... args) {
        Map<String, Object> arguments = new HashMap<>();
        for (int i = 0; i < args.length; i += 2) arguments.put((String) args[i], args[i + 1]);
        return new MethodCall(method, arguments);
    }

    @Test public void standaloneAndBatchArabicAppendBitmapAndSubmitOnlyOnce() throws Exception {
        for (String method : new String[]{"printText", "printBitMap", "printBatch"}) {
            Printer printer = printer(384);
            AtomicInteger bitmapCount = new AtomicInteger();
            doAnswer(invocation -> {
                Bitmap bitmap = invocation.getArgument(0);
                assertEquals(384, bitmap.getWidth());
                assertFalse(bitmap.isRecycled());
                bitmapCount.incrementAndGet();
                return null;
            }).when(printer).setPrintAppendBitmap(any(Bitmap.class), eq(Layout.Alignment.ALIGN_CENTER));
            MethodCall text = call(method.equals("printBatch") ? "printText" : method,
                    "text", "العربية\nEnglish", "Alignment", 2, "fontSize", 24, "isBold", true);
            MethodCall request = method.equals("printBatch") ? call("printBatch", "commands",
                    java.util.List.of(Map.of("method", text.method, "arguments", text.arguments))) : text;
            assertTrue(Z92PosPrinter.executePrint(printer, null, request));
            assertEquals(1, bitmapCount.get());
            verify(printer, times(1)).setPrintStart();
            verify(printer, never()).setPrintAppendString(anyString(), any());
        }
    }

    @Test public void arabicColumnsAndSeparatorsUseCorrectedRendering() throws Exception {
        Printer printer = printer(576);
        assertTrue(Z92PosPrinter.executePrint(printer, null, call("print3Column",
                "leftText", "العربية\nEnglish", "centerText", "123", "rightText", "المبلغ",
                "leftTextSize", 60, "centerTextSize", 15, "rightTextSize", 25,
                "fontSize", 24, "isBold", false, "leftAlign", 1, "centerAlign", 0, "rightAlign", 2)));
        assertTrue(Z92PosPrinter.executePrint(printer, null, call("printLine",
                "lineStyle", "ع", "lineSize", 3, "fontSize", 24, "isBold", false, "Alignment", 2)));
        assertTrue(Z92PosPrinter.executePrint(printer, null, call("print2Column",
                "leftText", "العربية", "rightText", "English\nالمبلغ",
                "leftTextSize", 60, "rightTextSize", 40,
                "fontSize", 24, "isBold", false, "leftAlign", 1, "rightAlign", 2)));
        verify(printer, times(3)).setPrintAppendBitmap(any(Bitmap.class), eq(Layout.Alignment.ALIGN_CENTER));
        verify(printer, times(3)).setPrintStart();
        verify(printer, never()).setPrintAppendStrings(any(), any(), any());
    }

    @Test public void appendFailureRecyclesRenderedBitmapAndPreventsSubmission() throws Exception {
        Printer printer = printer(384);
        AtomicReference<Bitmap> appended = new AtomicReference<>();
        doAnswer(invocation -> {
            appended.set(invocation.getArgument(0));
            throw new IllegalStateException("Append failed");
        }).when(printer).setPrintAppendBitmap(any(Bitmap.class), any());
        assertThrows(IllegalStateException.class, () -> Z92PosPrinter.executePrint(printer, null,
                call("printText", "text", "العربية", "Alignment", 2, "fontSize", 24, "isBold", false)));
        assertTrue(appended.get().isRecycled());
        verify(printer, never()).setPrintStart();
    }

    @Test public void invalidPrinterWidthPreventsSubmission() throws Exception {
        Printer printer = printer(0);
        assertThrows(IllegalStateException.class, () -> Z92PosPrinter.executePrint(printer, null,
                call("printText", "text", "العربية", "Alignment", 2, "fontSize", 24, "isBold", false)));
        verify(printer, never()).setPrintStart();
        verify(printer, never()).setPrintAppendBitmap(any(), any());
    }
}
