package com.z92posprinter;

import android.text.Layout;
import com.google.zxing.BarcodeFormat;
import com.zcs.sdk.Printer;
import com.zcs.sdk.SdkResult;
import com.zcs.sdk.print.PrnStrFormat;
import com.zcs.sdk.print.PrnTextStyle;
import io.flutter.plugin.common.MethodCall;
import io.flutter.plugin.common.MethodChannel;
import org.junit.Before;
import org.junit.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

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

    private MethodCall batch(MethodCall... commands) {
        List<Map<String, Object>> entries = new ArrayList<>();
        for (MethodCall command : commands) {
            entries.add(Map.of("method", command.method, "arguments", command.arguments));
        }
        return call("printBatch", "commands", entries);
    }

    @Test public void batchAppendsMixedCommandsInOrderAndStartsOnce() {
        assertTrue(Z92PosPrinter.isPrintMethod("printBatch"));
        assertTrue(Z92PosPrinter.executePrint(printer, null, batch(text(),
                call("print2Column", "leftText", "Total", "rightText", "6.00",
                        "leftTextSize", 60, "rightTextSize", 40, "fontSize", 16,
                        "isBold", true, "leftAlign", 1, "rightAlign", 2),
                call("printBarCode128", "text", "1234", "width", 300, "height", 80),
                call("printQrCode", "text", "QR", "width", 200, "height", 120),
                call("printLine", "lineStyle", "-", "lineSize", 3,
                        "fontSize", 16, "isBold", false, "Alignment", 0),
                call("printSpace", "space", 2))));

        InOrder order = inOrder(printer);
        order.verify(printer).getPrinterStatus();
        order.verify(printer).setPrintAppendString(eq("Receipt"), any(PrnStrFormat.class));
        order.verify(printer).setPrintAppendStrings(eq(new String[]{"Total", "6.00"}),
                eq(new int[]{60, 40}), any(PrnStrFormat[].class));
        order.verify(printer).setPrintAppendBarCode(isNull(), eq("1234"), eq(300), eq(80),
                eq(false), eq(Layout.Alignment.ALIGN_CENTER), eq(BarcodeFormat.CODE_128));
        order.verify(printer).setPrintAppendQRCode("QR", 200, 120, Layout.Alignment.ALIGN_CENTER);
        order.verify(printer).setPrintAppendString(eq("---"), any(PrnStrFormat.class));
        order.verify(printer, times(2)).setPrintAppendString(eq(""), any(PrnStrFormat.class));
        order.verify(printer).setPrintStart();
        order.verifyNoMoreInteractions();
        assertTrue(pending.isEmpty());
    }

    @Test public void batchKeepsBufferUntilSubmissionAndClearsBeforeNextJob() {
        pending.add("Stale receipt");
        doAnswer(invocation -> {
            pending.add(invocation.getArgument(0));
            return null;
        }).when(printer).setPrintAppendString(anyString(), any(PrnStrFormat.class));
        when(printer.setPrintStart()).thenAnswer(invocation -> {
            assertEquals(List.of("Receipt", "Second"), pending);
            return SdkResult.SDK_OK;
        }).thenAnswer(invocation -> {
            assertEquals(List.of("Receipt"), pending);
            return SdkResult.SDK_OK;
        });
        assertTrue(Z92PosPrinter.executePrint(printer, null, batch(text(),
                call("printText", "text", "Second", "fontSize", 18,
                        "isBold", false, "Alignment", 1))));
        assertTrue(pending.isEmpty());
        assertTrue(Z92PosPrinter.executePrint(printer, null, batch(text())));
        verify(printer, times(2)).setPrintStart();
        assertTrue(pending.isEmpty());
    }

    @Test public void batchWaitsBeforeAnyAppendAndPollsOnlyOnceForTheWholeJob() {
        when(printer.getPrinterStatus()).thenReturn(SdkResult.SDK_PRN_STATUS_PRINTING,
                SdkResult.SDK_PRN_STATUS_PRINTING, SdkResult.SDK_OK);
        assertTrue(Z92PosPrinter.executePrint(printer, null, batch(text(),
                call("printQrCode", "text", "QR", "width", 200, "height", 120))));
        InOrder order = inOrder(printer);
        order.verify(printer, times(3)).getPrinterStatus();
        order.verify(printer).setPrintAppendString(eq("Receipt"), any(PrnStrFormat.class));
        order.verify(printer).setPrintAppendQRCode("QR", 200, 120, Layout.Alignment.ALIGN_CENTER);
        order.verify(printer).setPrintStart();
        order.verifyNoMoreInteractions();
    }

    @Test public void batchReadinessErrorsAndTimeoutPreventAppending() {
        for (int status : new int[]{SdkResult.SDK_PRN_STATUS_PAPEROUT,
                SdkResult.SDK_PRN_STATUS_TOOHEAT, SdkResult.SDK_PRN_STATUS_FAULT,
                SdkResult.SDK_ERROR, SdkResult.SDK_PRN_STATUS_PRINTING}) {
            when(printer.getPrinterStatus()).thenReturn(status);
            assertFalse(Z92PosPrinter.executePrint(printer, null, batch(text()), null, 0, 10));
        }
        verify(printer, never()).setPrintAppendString(anyString(), any(PrnStrFormat.class));
        verify(printer, never()).setPrintStart();
        assertTrue(pending.isEmpty());
    }

    @Test public void emptyAndZeroLengthBatchesDoNotStartPrinting() {
        assertTrue(Z92PosPrinter.executePrint(printer, null, batch()));
        assertTrue(Z92PosPrinter.executePrint(printer, null, batch(
                call("printSpace", "space", 0),
                call("printLine", "lineStyle", "-", "lineSize", 0,
                        "fontSize", 16, "isBold", false, "Alignment", 0))));
        verify(printer, never()).setPrintAppendString(anyString(), any(PrnStrFormat.class));
        verify(printer, never()).setPrintStart();
        assertTrue(pending.isEmpty());
    }

    @Test public void malformedUnknownNestedAndOversizedBatchesAreRejected() {
        for (Object commands : new Object[]{null, "invalid", List.of("invalid"),
                List.of(Map.of("method", 1, "arguments", Map.of())),
                List.of(Map.of("method", "printText", "arguments", "invalid")),
                List.of(Map.of("method", "unknown", "arguments", Map.of())),
                List.of(Map.of("method", "printBatch", "arguments", Map.of())),
                java.util.Collections.nCopies(1001,
                        Map.of("method", "printSpace", "arguments", Map.of("space", 0)))}) {
            assertThrows(IllegalArgumentException.class, () -> Z92PosPrinter.executePrint(
                    printer, null, call("printBatch", "commands", commands)));
            assertTrue(pending.isEmpty());
        }
        verify(printer, never()).setPrintAppendString(anyString(), any(PrnStrFormat.class));
        verify(printer, never()).setPrintStart();
    }

    @Test public void invalidLaterCommandClearsEarlierAppendsWithoutSubmission() {
        doAnswer(invocation -> {
            pending.add(invocation.getArgument(0));
            return null;
        }).when(printer).setPrintAppendString(anyString(), any(PrnStrFormat.class));
        for (MethodCall invalid : new MethodCall[]{call("printSpace", "space", -1),
                call("printQrCode", "text", "", "width", 200, "height", 200),
                call("printImage", "pathImage", new byte[0]),
                call("printImage", "pathImage", new byte[]{1, 2, 3})}) {
            assertThrows(IllegalArgumentException.class, () -> Z92PosPrinter.executePrint(
                    printer, null, batch(text(), invalid)));
            assertTrue(pending.isEmpty());
        }
        verify(printer, never()).setPrintStart();
    }

    @Test public void batchAppendAndStartExceptionsAlwaysClearWithoutRetry() {
        for (Throwable failure : new Throwable[]{new IllegalStateException(),
                new UnsatisfiedLinkError(), new OutOfMemoryError()}) {
            doAnswer(invocation -> {
                pending.add(invocation.getArgument(0));
                throw failure;
            }).when(printer).setPrintAppendString(anyString(), any(PrnStrFormat.class));
            assertThrows(failure.getClass(),
                    () -> Z92PosPrinter.executePrint(printer, null, batch(text())));
            assertTrue(pending.isEmpty());
            verify(printer, never()).setPrintStart();
        }
        doAnswer(invocation -> {
            pending.add(invocation.getArgument(0));
            return null;
        }).when(printer).setPrintAppendString(anyString(), any(PrnStrFormat.class));
        for (Throwable failure : new Throwable[]{new IllegalStateException(),
                new UnsatisfiedLinkError(), new OutOfMemoryError()}) {
            doThrow(failure).when(printer).setPrintStart();
            clearInvocations(printer);
            assertThrows(failure.getClass(),
                    () -> Z92PosPrinter.executePrint(printer, null, batch(text())));
            verify(printer, times(1)).setPrintStart();
            assertTrue(pending.isEmpty());
        }
    }

    @Test public void batchFailedStartIsNotRetriedAndDoesNotContaminateNextJob() {
        doAnswer(invocation -> {
            pending.add(invocation.getArgument(0));
            return null;
        }).when(printer).setPrintAppendString(anyString(), any(PrnStrFormat.class));
        when(printer.setPrintStart()).thenReturn(SdkResult.SDK_PRN_STATUS_PAPEROUT)
                .thenAnswer(invocation -> {
                    assertEquals(List.of("Receipt"), pending);
                    return SdkResult.SDK_OK;
                });
        assertFalse(Z92PosPrinter.executePrint(printer, null, batch(text())));
        assertTrue(pending.isEmpty());
        verify(printer, times(1)).setPrintStart();
        assertTrue(Z92PosPrinter.executePrint(printer, null, batch(text())));
        verify(printer, times(2)).setPrintStart();
        assertTrue(pending.isEmpty());
    }

    @Test public void detachmentBetweenBatchAppendsPreventsFurtherAppendAndStart() throws Exception {
        Z92PosPrinter engine = new Z92PosPrinter();
        Field channel = Z92PosPrinter.class.getDeclaredField("channel");
        channel.setAccessible(true);
        channel.set(engine, mock(MethodChannel.class));
        doAnswer(invocation -> {
            pending.add(invocation.getArgument(0));
            engine.onDetachedFromEngine(null);
            return null;
        }).when(printer).setPrintAppendString(anyString(), any(PrnStrFormat.class));
        assertFalse(Z92PosPrinter.executePrint(printer, null, batch(text(),
                call("printQrCode", "text", "QR", "width", 200, "height", 120)),
                engine, 0, 1000));
        verify(printer, never()).setPrintAppendQRCode(anyString(), anyInt(), anyInt(), any());
        verify(printer, never()).setPrintStart();
        assertTrue(pending.isEmpty());
    }

    @Test public void printStartFailureIsNotReportedAsSuccess() {
        when(printer.setPrintStart()).thenReturn(SdkResult.SDK_PRN_STATUS_PAPEROUT);
        assertFalse(Z92PosPrinter.executePrint(printer, null, text()));
        verify(printer).setPrintAppendString(eq("Receipt"), any(PrnStrFormat.class));
        verify(printer, times(1)).setPrintStart();
        assertTrue(pending.isEmpty());
    }

    @Test public void allTerminalStatusesPreventAppending() {
        for (int status : new int[]{SdkResult.SDK_PRN_STATUS_PAPEROUT,
                SdkResult.SDK_PRN_STATUS_TOOHEAT, SdkResult.SDK_PRN_STATUS_FAULT,
                SdkResult.SDK_ERROR, SdkResult.SDK_TIMEOUT, SdkResult.SDK_RECV_DATA_ERROR}) {
            when(printer.getPrinterStatus()).thenReturn(status);
            assertFalse(Z92PosPrinter.executePrint(printer, null, text()));
        }
        verify(printer, never()).setPrintAppendString(anyString(), any(PrnStrFormat.class));
        verify(printer, never()).setPrintStart();
    }

    @Test public void printingThenReadyDispatchesOnceAndKeepsCommandOrder() {
        when(printer.getPrinterStatus()).thenReturn(SdkResult.SDK_OK,
                SdkResult.SDK_PRN_STATUS_PRINTING, SdkResult.SDK_PRN_STATUS_PRINTING,
                SdkResult.SDK_OK);
        assertTrue(Z92PosPrinter.executePrint(printer, null, text()));
        assertTrue(Z92PosPrinter.executePrint(printer, null,
                call("printQrCode", "text", "QR", "width", 200, "height", 120)));

        InOrder order = inOrder(printer);
        order.verify(printer).getPrinterStatus();
        order.verify(printer).setPrintAppendString(eq("Receipt"), any(PrnStrFormat.class));
        order.verify(printer).setPrintStart();
        order.verify(printer, times(3)).getPrinterStatus();
        order.verify(printer).setPrintAppendQRCode("QR", 200, 120, Layout.Alignment.ALIGN_CENTER);
        order.verify(printer).setPrintStart();
        order.verifyNoMoreInteractions();
        assertTrue(pending.isEmpty());
    }

    @Test public void printingThenPaperOutDoesNotDispatch() {
        pending.add("Stale receipt");
        when(printer.getPrinterStatus()).thenReturn(SdkResult.SDK_PRN_STATUS_PRINTING,
                SdkResult.SDK_PRN_STATUS_PAPEROUT);
        assertFalse(Z92PosPrinter.executePrint(printer, null, text()));
        verify(printer, times(2)).getPrinterStatus();
        verify(printer, never()).setPrintAppendString(anyString(), any(PrnStrFormat.class));
        verify(printer, never()).setPrintStart();
        assertTrue(pending.isEmpty());
    }

    @Test(timeout = 2000) public void permanentlyBusyReachesDeadlineWithoutDispatch() {
        when(printer.getPrinterStatus()).thenReturn(SdkResult.SDK_PRN_STATUS_PRINTING);
        long started = System.nanoTime();
        assertFalse(Z92PosPrinter.executePrint(printer, null, text(), null, 0, 30));
        assertTrue(System.nanoTime() - started >= TimeUnit.MILLISECONDS.toNanos(30));
        verify(printer).getPrinterStatus();
        verify(printer, never()).setPrintAppendString(anyString(), any(PrnStrFormat.class));
        verify(printer, never()).setPrintStart();
        assertTrue(pending.isEmpty());
    }

    @Test(timeout = 2000) public void interruptedWaitPreservesInterruptAndDoesNotDispatch()
            throws Exception {
        CountDownLatch polling = new CountDownLatch(1);
        when(printer.getPrinterStatus()).thenAnswer(invocation -> {
            polling.countDown();
            return SdkResult.SDK_PRN_STATUS_PRINTING;
        });
        AtomicBoolean success = new AtomicBoolean(true);
        AtomicBoolean interrupted = new AtomicBoolean(false);
        Thread worker = new Thread(() -> {
            success.set(Z92PosPrinter.executePrint(printer, null, text()));
            interrupted.set(Thread.currentThread().isInterrupted());
        });
        worker.start();
        try {
            assertTrue(polling.await(1, TimeUnit.SECONDS));
            worker.interrupt();
            worker.join(1000);
            assertFalse(worker.isAlive());
            assertFalse(success.get());
            assertTrue(interrupted.get());
        } finally {
            worker.interrupt();
            worker.join(1000);
        }
        verify(printer, never()).setPrintAppendString(anyString(), any(PrnStrFormat.class));
        verify(printer, never()).setPrintStart();
        assertTrue(pending.isEmpty());
    }

    @Test(timeout = 2000) public void readyStatusReturnedAfterDeadlineDoesNotDispatch() {
        when(printer.getPrinterStatus()).thenReturn(SdkResult.SDK_PRN_STATUS_PRINTING)
                .thenAnswer(invocation -> {
                    Thread.sleep(100);
                    return SdkResult.SDK_OK;
                });
        assertFalse(Z92PosPrinter.executePrint(printer, null, text(), null, 0, 150));
        verify(printer, never()).setPrintAppendString(anyString(), any(PrnStrFormat.class));
        verify(printer, never()).setPrintStart();
        assertTrue(pending.isEmpty());
    }

    @Test public void engineDetachmentDuringWaitingPreventsReadyDispatch() throws Exception {
        Z92PosPrinter engine = new Z92PosPrinter();
        Field channel = Z92PosPrinter.class.getDeclaredField("channel");
        channel.setAccessible(true);
        channel.set(engine, mock(MethodChannel.class));
        when(printer.getPrinterStatus()).thenReturn(SdkResult.SDK_PRN_STATUS_PRINTING)
                .thenAnswer(invocation -> {
                    engine.onDetachedFromEngine(null);
                    return SdkResult.SDK_OK;
                });
        assertFalse(Z92PosPrinter.executePrint(printer, null, text(), engine, 0, 1000));
        verify(printer, times(2)).getPrinterStatus();
        verify(printer, never()).setPrintAppendString(anyString(), any(PrnStrFormat.class));
        verify(printer, never()).setPrintStart();
        assertTrue(pending.isEmpty());
    }

    @Test public void readinessExceptionStillClearsBuffer() {
        pending.add("Stale receipt");
        when(printer.getPrinterStatus()).thenThrow(new IllegalStateException());
        assertThrows(IllegalStateException.class,
                () -> Z92PosPrinter.executePrint(printer, null, text()));
        verify(printer, never()).setPrintStart();
        assertTrue(pending.isEmpty());
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
