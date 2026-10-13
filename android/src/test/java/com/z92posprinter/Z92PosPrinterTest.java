package com.z92posprinter;

import android.text.Layout;
import com.google.zxing.BarcodeFormat;
import com.zcs.sdk.Printer;
import com.zcs.sdk.SdkResult;
import com.zcs.sdk.print.PrnStrFormat;
import com.zcs.sdk.print.PrnTextStyle;
import io.flutter.plugin.common.MethodCall;
import org.junit.Before;
import org.junit.Test;
import org.mockito.ArgumentCaptor;
import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.Assert.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

public class Z92PosPrinterTest {
    private final Printer printer = mock(Printer.class);
    private final List<String> pending = new ArrayList<>();

    @Before public void installSdkBuffer() throws Exception {
        Field buffer = Printer.class.getDeclaredField("h");
        buffer.setAccessible(true);
        buffer.set(printer, pending);
    }

    @Test public void failedAppendOrStartCannotContaminateNextReceipt() {
        for (Throwable failure : new Throwable[]{new IllegalStateException(),
                new UnsatisfiedLinkError(), new OutOfMemoryError()}) {
            for (boolean failDuringAppend : new boolean[]{true, false}) {
                doAnswer(invocation -> {
                    String content = invocation.getArgument(0);
                    pending.add(content);
                    if (failDuringAppend && content.equals("Receipt")) throw failure;
                    return null;
                }).when(printer).setPrintAppendString(anyString(), any(PrnStrFormat.class));
                doAnswer(invocation -> {
                    if (pending.contains("Receipt")) throw failure;
                    assertEquals(List.of("Next receipt"), pending);
                    return SdkResult.SDK_OK;
                }).when(printer).setPrintStart();

                assertThrows(failure.getClass(), () -> Z92PosPrinter.executePrint(printer, null, text()));
                assertTrue(pending.isEmpty());
                assertTrue(Z92PosPrinter.executePrint(printer, null,
                        call("printText", "text", "Next receipt", "fontSize", 20,
                                "isBold", false, "Alignment", 1)));
                assertTrue(pending.isEmpty());
            }
        }
    }

    @Test public void inaccessibleBufferPreventsAppending() throws Exception {
        Field buffer = Printer.class.getDeclaredField("h");
        buffer.setAccessible(true);
        buffer.set(printer, null);
        assertFalse(Z92PosPrinter.executePrint(printer, null, text()));
        verifyNoInteractions(printer);
    }

    @Test public void staleBufferIsClearedBeforeAppending() {
        pending.add("Previous receipt");
        doAnswer(invocation -> {
            assertTrue(pending.isEmpty());
            pending.add(invocation.getArgument(0));
            return null;
        }).when(printer).setPrintAppendString(anyString(), any(PrnStrFormat.class));
        assertTrue(Z92PosPrinter.executePrint(printer, null, text()));
        assertTrue(pending.isEmpty());
    }

    private MethodCall call(String method, Object... arguments) {
        Map<String, Object> data = new HashMap<>();
        for (int i = 0; i < arguments.length; i += 2) {
            data.put((String) arguments[i], arguments[i + 1]);
        }
        return new MethodCall(method, data);
    }

    private MethodCall text() {
        return call("printText", "text", "Receipt", "fontSize", 20, "isBold", true, "Alignment", 1);
    }

    @Test public void printStartFailureIsNotReportedAsSuccess() {
        when(printer.setPrintStart()).thenReturn(SdkResult.SDK_PRN_STATUS_PAPEROUT);
        assertFalse(Z92PosPrinter.executePrint(printer, null, text()));
        verify(printer).setPrintAppendString(eq("Receipt"), any(PrnStrFormat.class));
    }

    @Test public void allNonReadyStatusesPreventAppending() {
        for (int status : new int[]{SdkResult.SDK_PRN_STATUS_PAPEROUT,
                SdkResult.SDK_PRN_STATUS_TOOHEAT, SdkResult.SDK_PRN_STATUS_FAULT,
                SdkResult.SDK_PRN_STATUS_PRINTING, SdkResult.SDK_ERROR}) {
            when(printer.getPrinterStatus()).thenReturn(status);
            assertFalse(Z92PosPrinter.executePrint(printer, null, text()));
        }
        verify(printer, never()).setPrintAppendString(anyString(), any(PrnStrFormat.class));
        verify(printer, never()).setPrintStart();
    }

    @Test public void barcodeAndQrDimensionsReachSdkInWidthHeightOrder() {
        assertTrue(Z92PosPrinter.executePrint(printer, null,
                call("printBarCode128", "text", "1234", "width", 300, "height", 80)));
        verify(printer).setPrintAppendBarCode(isNull(), eq("1234"), eq(300), eq(80),
                eq(false), eq(Layout.Alignment.ALIGN_CENTER), eq(BarcodeFormat.CODE_128));
        assertTrue(Z92PosPrinter.executePrint(printer, null,
                call("printQrCode", "text", "QR", "width", 200, "height", 120)));
        verify(printer).setPrintAppendQRCode("QR", 200, 120, Layout.Alignment.ALIGN_CENTER);
    }

    @Test public void everyColumnReceivesSizeBoldAndAlignment() {
        assertTrue(Z92PosPrinter.executePrint(printer, null,
                call("print3Column", "leftText", "L", "centerText", "C", "rightText", "R",
                        "leftTextSize", 60, "centerTextSize", 15, "rightTextSize", 25,
                        "fontSize", 18, "isBold", true,
                        "leftAlign", 1, "centerAlign", 0, "rightAlign", 2)));
        ArgumentCaptor<PrnStrFormat[]> formats = ArgumentCaptor.forClass(PrnStrFormat[].class);
        verify(printer).setPrintAppendStrings(eq(new String[]{"L", "C", "R"}),
                eq(new int[]{60, 15, 25}), formats.capture());
        for (PrnStrFormat format : formats.getValue()) {
            assertEquals(18, format.getTextSize());
            assertEquals(PrnTextStyle.BOLD, format.getStyle());
        }
        assertEquals(Layout.Alignment.ALIGN_NORMAL, formats.getValue()[0].getAli());
        assertEquals(Layout.Alignment.ALIGN_CENTER, formats.getValue()[1].getAli());
        assertEquals(Layout.Alignment.ALIGN_OPPOSITE, formats.getValue()[2].getAli());
    }

    @Test public void invalidInputsDoNotStartPrinting() {
        assertThrows(IllegalArgumentException.class, () -> Z92PosPrinter.executePrint(printer, null,
                call("printSpace", "space", -1)));
        assertThrows(IllegalArgumentException.class, () -> Z92PosPrinter.executePrint(printer, null,
                call("printQrCode", "text", "", "width", 200, "height", 200)));
        assertThrows(IllegalArgumentException.class, () -> Z92PosPrinter.executePrint(printer, null,
                call("printImage", "pathImage", new byte[0])));
        verify(printer, never()).setPrintStart();
    }

    @Test public void bothImageMethodsAreRecognizedAndZeroFeedIsANoOp() {
        assertTrue(Z92PosPrinter.isPrintMethod("printImage"));
        assertTrue(Z92PosPrinter.isPrintMethod("printImageBitmap"));
        assertFalse(Z92PosPrinter.isPrintMethod("unknown"));
        assertTrue(Z92PosPrinter.executePrint(printer, null, call("printSpace", "space", 0)));
        verify(printer, never()).setPrintStart();
    }
}
