package com.z92posprinter;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.os.Handler;
import android.os.Looper;
import android.text.Layout;
import android.util.Log;

import androidx.annotation.NonNull;

import com.google.zxing.BarcodeFormat;
import com.zcs.sdk.DriverManager;
import com.zcs.sdk.Printer;
import com.zcs.sdk.SdkResult;
import com.zcs.sdk.print.PrnStrFormat;
import com.zcs.sdk.print.PrnTextFont;
import com.zcs.sdk.print.PrnTextStyle;

import java.lang.reflect.Field;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import io.flutter.embedding.engine.plugins.FlutterPlugin;
import io.flutter.plugin.common.MethodCall;
import io.flutter.plugin.common.MethodChannel;

/** Android bridge to the bundled ZCS SmartPos SDK. */
public class Z92PosPrinter implements FlutterPlugin, MethodChannel.MethodCallHandler {
    private static final String TAG = "z92PosPrinter";
    // The SDK printer and append buffer are process-wide singletons. Keep all engines in order.
    private static final ExecutorService PRINT_QUEUE = Executors.newSingleThreadExecutor();
    private static boolean sdkInitialized;

    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private MethodChannel channel;
    private Context context;
    private volatile int engineGeneration;

    @Override
    public void onAttachedToEngine(@NonNull FlutterPluginBinding binding) {
        context = binding.getApplicationContext();
        engineGeneration++;
        channel = new MethodChannel(binding.getBinaryMessenger(), TAG);
        channel.setMethodCallHandler(this);
    }

    @Override
    public void onDetachedFromEngine(@NonNull FlutterPluginBinding binding) {
        engineGeneration++;
        channel.setMethodCallHandler(null);
        channel = null;
        context = null;
    }

    @Override
    public void onMethodCall(@NonNull MethodCall call, @NonNull MethodChannel.Result result) {
        // Unknown methods must not load native libraries or touch printer hardware.
        if (!isPrintMethod(call.method)) {
            result.notImplemented();
            return;
        }
        final Context appContext = context;
        final int generation = engineGeneration;
        PRINT_QUEUE.execute(() -> {
            boolean success = false;
            try {
                if (appContext != null && generation == engineGeneration) {
                    DriverManager driver = DriverManager.getInstance();
                    if (!sdkInitialized) {
                        // Let the vendor SDK detect the platform and transport (Z91 and Z92 differ).
                        int status = driver.getBaseSysDevice().sdkInit();
                        if (status != SdkResult.SDK_OK) {
                            Log.w(TAG, "SDK initialization failed: " + status);
                            mainHandler.post(() -> result.success(false));
                            return;
                        }
                        sdkInitialized = true;
                    }
                    if (generation == engineGeneration) {
                        success = executePrint(driver.getPrinter(), appContext, call);
                    }
                }
            } catch (Exception | LinkageError | OutOfMemoryError e) {
                // Keep the bool API; never expose receipt content or depend on an Activity/Toast.
                Log.w(TAG, "Printing failed for " + call.method + ": " + e.getClass().getSimpleName());
            }
            final boolean completed = success;
            mainHandler.post(() -> result.success(completed));
        });
    }

    static boolean isPrintMethod(String method) {
        switch (method) {
            case "printText":
            case "printBitMap": // Legacy platform-interface demo call.
            case "printSpace":
            case "print2Column":
            case "print3Column":
            case "printQrCode":
            case "printLine":
            case "printImage":
            case "printImageBitmap":
            case "printBarCode128":
                return true;
            default:
                return false;
        }
    }

    // Package visibility allows hardware-free regression tests against a mock SDK printer.
    static boolean executePrint(Printer printer, Context context, MethodCall call) {
        final List<?> pending;
        try {
            // SmartPos_1.9.0_R240612 has no reset API: h is its bitmap append buffer.
            // consumer-rules.pro preserves vendor field names. Fail closed if the SDK changes.
            Field buffer = Printer.class.getDeclaredField("h");
            buffer.setAccessible(true);
            Object value = buffer.get(printer);
            if (!(value instanceof List<?>)) return false;
            pending = (List<?>) value;
        } catch (ReflectiveOperationException e) {
            Log.w(TAG, "Cannot access SDK print buffer");
            return false;
        }
        try {
            pending.clear();
            return executePrintJob(printer, context, call);
        } finally {
            // The SDK only clears on normal completion; also release content after append/OOM failures.
            pending.clear();
        }
    }

    private static boolean executePrintJob(Printer printer, Context context, MethodCall call) {
        int status = printer.getPrinterStatus();
        if (status != SdkResult.SDK_OK) {
            Log.w(TAG, "Printer unavailable: " + status);
            return false;
        }
        switch (call.method) {
            case "printText":
            case "printBitMap":
                printer.setPrintAppendString(string(call, "text"), format(call, "Alignment"));
                break;
            case "printSpace":
                int lines = integer(call, "space", 0, 1000);
                if (lines == 0) return true;
                PrnStrFormat spaceFormat = new PrnStrFormat();
                for (int i = 0; i < lines; i++) {
                    printer.setPrintAppendString("", spaceFormat);
                }
                break;
            case "print2Column":
                appendColumns(printer, call, false);
                break;
            case "print3Column":
                appendColumns(printer, call, true);
                break;
            case "printQrCode":
                printer.setPrintAppendQRCode(nonEmptyString(call, "text"),
                        integer(call, "width", 1, 4096), integer(call, "height", 1, 4096),
                        Layout.Alignment.ALIGN_CENTER);
                break;
            case "printBarCode128":
                printer.setPrintAppendBarCode(context, nonEmptyString(call, "text"),
                        integer(call, "width", 1, 4096), integer(call, "height", 1, 4096),
                        false, Layout.Alignment.ALIGN_CENTER, BarcodeFormat.CODE_128);
                break;
            case "printLine":
                String style = nonEmptyString(call, "lineStyle");
                int count = integer(call, "lineSize", 0, 1000);
                PrnStrFormat lineFormat = format(call, "Alignment");
                lineFormat.setFont(PrnTextFont.SANS_SERIF);
                if (count == 0) return true;
                if ((long) style.length() * count > 65536) {
                    throw new IllegalArgumentException("Line is too long");
                }
                StringBuilder line = new StringBuilder(style.length() * count);
                for (int i = 0; i < count; i++) line.append(style);
                printer.setPrintAppendString(line.toString(), lineFormat);
                break;
            case "printImage":
            case "printImageBitmap":
                Object image = call.argument("pathImage");
                if (!(image instanceof byte[]) || ((byte[]) image).length == 0) {
                    throw new IllegalArgumentException("Expected encoded image bytes");
                }
                byte[] bytes = (byte[]) image;
                BitmapFactory.Options bounds = new BitmapFactory.Options();
                bounds.inJustDecodeBounds = true;
                BitmapFactory.decodeByteArray(bytes, 0, bytes.length, bounds);
                if (bounds.outWidth <= 0 || bounds.outHeight <= 0
                        || bounds.outWidth > 16384 || bounds.outHeight > 16384
                        || (long) bounds.outWidth * bounds.outHeight > 16 * 1024 * 1024) {
                    throw new IllegalArgumentException("Invalid or oversized image");
                }
                Bitmap bitmap = BitmapFactory.decodeByteArray(bytes, 0, bytes.length);
                if (bitmap == null) throw new IllegalArgumentException("Cannot decode image");
                try {
                    printer.setPrintAppendBitmap(bitmap, Layout.Alignment.ALIGN_CENTER);
                } finally {
                    // The SDK copies the bitmap into its own append buffer.
                    bitmap.recycle();
                }
                break;
            default:
                throw new IllegalArgumentException("Unknown print method");
        }
        status = printer.setPrintStart();
        if (status != SdkResult.SDK_OK) Log.w(TAG, "Print failed: " + status);
        return status == SdkResult.SDK_OK;
    }

    private static void appendColumns(Printer printer, MethodCall call, boolean threeColumns) {
        String[] texts = threeColumns
                ? new String[]{string(call, "leftText"), string(call, "centerText"), string(call, "rightText")}
                : new String[]{string(call, "leftText"), string(call, "rightText")};
        int left = integer(call, "leftTextSize", 1, 1000000);
        int right = integer(call, "rightTextSize", 1, 1000000);
        int[] widths = threeColumns
                ? new int[]{left, integer(call, "centerTextSize", 1, 1000000), right}
                : new int[]{left, right};
        PrnStrFormat[] formats = threeColumns
                ? new PrnStrFormat[]{format(call, "leftAlign"), format(call, "centerAlign"), format(call, "rightAlign")}
                : new PrnStrFormat[]{format(call, "leftAlign"), format(call, "rightAlign")};
        printer.setPrintAppendStrings(texts, widths, formats);
    }

    private static PrnStrFormat format(MethodCall call, String alignmentKey) {
        PrnStrFormat format = new PrnStrFormat();
        format.setFont(PrnTextFont.MONOSPACE);
        format.setTextSize(integer(call, "fontSize", 1, 256));
        Object bold = call.argument("isBold");
        if (!(bold instanceof Boolean)) throw new IllegalArgumentException("Expected isBold boolean");
        format.setStyle((Boolean) bold ? PrnTextStyle.BOLD : PrnTextStyle.NORMAL);
        int alignment = integer(call, alignmentKey, 0, 2);
        format.setAli(alignment == 0 ? Layout.Alignment.ALIGN_CENTER
                : alignment == 1 ? Layout.Alignment.ALIGN_NORMAL : Layout.Alignment.ALIGN_OPPOSITE);
        return format;
    }

    private static String string(MethodCall call, String key) {
        Object value = call.argument(key);
        if (!(value instanceof String)) throw new IllegalArgumentException("Expected string: " + key);
        return (String) value;
    }

    private static String nonEmptyString(MethodCall call, String key) {
        String value = string(call, key);
        if (value.isEmpty()) throw new IllegalArgumentException("Empty string: " + key);
        return value;
    }

    private static int integer(MethodCall call, String key, int min, int max) {
        Object value = call.argument(key);
        if (!(value instanceof Integer) || (Integer) value < min || (Integer) value > max) {
            throw new IllegalArgumentException("Invalid integer: " + key);
        }
        return (Integer) value;
    }
}
