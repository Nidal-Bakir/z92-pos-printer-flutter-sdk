# Z92 POS Printer Flutter SDK

Maintained by **Nidal Bakir**. Version **1.0.0** provides ZCS printer integration for Flutter applications.

Flutter plugin for the **built-in printer on ZCS Z92 and Z91 Android POS terminals**. The software validation target is Flutter **3.44.2** and Z92 devices running **ZOS based on Android 13**. Physical printing on the deployment device must be verified before production use.

## Compatibility and SDK

- Android implementation only; iOS and desktop printing are not implemented.
- Uses the bundled `android/libs/SmartPos_1.9.0_R240612.jar` and `libSmartPosJni.so` / `libEmvCoreJni.so`. Native libraries are supplied for `armeabi-v7a` and `arm64-v8a` only. An x86 emulator cannot exercise this SDK.
- The plugin keeps its Android minimum SDK at 21. The example follows Flutter's minimum/target SDK settings; Flutter 3.44.2 uses minimum API 24 and compile/target API 36. Android 13 is API 33 and meets those minimums.
- The plugin and example use Java 17 source/target compatibility. The example uses AGP 8.13.1, Gradle 8.14.3, Kotlin 2.2.21, with the legacy Kotlin plugin needed for this Flutter target. Run Gradle with JDK 17 or a compatible newer JDK. Local validation uses JDK 21.
- Dart package constraint: `>=3.0.0 <4.0.0`. Software checks use Flutter 3.44.2; compatibility with other Flutter versions requires validation.
- Release builds keep the vendor `com.zcs` classes and members because the SDK JNI bridge uses reflection. Shrinking of the rest of the host app remains enabled. The consumer rules suppress only ten missing `javax.smartcardio` types used by the SDK's optional card-reading package; `DriverManager`, `Sys`, and `Printer` do not reference that package. This plugin exposes printing, not smart-card APIs.
- Match the actual device firmware and printer module to the bundled SDK. Do not assume every terminal sold as “Z92” is equivalent. No vendor binaries have been replaced.

ZCS lists Z91 and Z92 in its [Android POS SDK family](https://www.szzcs.com/Support-Service/SDK-Download/). Confirm the SDK against the deployment device's exact firmware and printer module. API signatures, return codes, auto-detected initialization, bitmap copying, and column weight behavior were inspected in the bundled JAR using `javap`. AGP requirements are documented by [Android Developers](https://developer.android.com/build/releases/agp-8-13-0-release-notes).

## Installation

For the first published release, add the package to your app's `pubspec.yaml`:

```yaml
dependencies:
  z92_pos_printer_flutter_sdk: ^1.0.0
```

Run `flutter pub get`, then fully rebuild the Android app so the native plugin and libraries are included. Hot reload alone does not install native changes.

For local development before publication, use:

```yaml
dependencies:
  z92_pos_printer_flutter_sdk:
    path: ../z92-pos-printer-flutter-sdk
```

Source and issue tracker: [GitHub repository](https://github.com/Nidal-Bakir/z92-pos-printer-flutter-sdk) · [Report an issue](https://github.com/Nidal-Bakir/z92-pos-printer-flutter-sdk/issues).

The public API is an instance of `PrinterPosSystem`. Printing methods return `Future<bool?>`. The plugin lazily calls the SDK's `sdkInit()` on its first print request, retries initialization on a later request if initialization fails, and lets the SDK detect its transport. The host app does not need an Activity-based initialization call.

```dart
import 'package:z92_pos_printer_flutter_sdk/z92_pos_printer_flutter_sdk.dart';

final printer = PrinterPosSystem();

Future<bool> printReceipt() async {
  final steps = <Future<bool?> Function()>[
    () => printer.printText(
      text: 'MY STORE',
      alignment: AlignmentPrint.center,
      isBold: true,
      fontSize: 24,
    ),
    () => printer.printLine(fontSize: 16),
    () => printer.print3Column(
      textLeft: 'Coffee',
      textCenter: '2',
      textRight: '6.00',
      spaceLeft: 60,
      spaceCenter: 15,
      spaceRight: 25,
    ),
    () => printer.print2Column(
      textLeft: 'TOTAL',
      textRight: '6.00',
      isBold: true,
    ),
    () => printer.printSpace(space: 3),
  ];
  for (final step in steps) {
    if (await step() != true) return false;
  }
  return true;
}
```

Await each operation and stop on failure. Calls are serialized across plugin engines, but a receipt composed of separate calls is **not an atomic job**: concurrent receipt producers can interleave. Keep one receipt producer active in the host app. Do not automatically retry a failed receipt, because part of it may already have printed.

## Printing operations

| Public method | Input and behavior |
| --- | --- |
| `printText` | Text, alignment, bold, font size (default 30) |
| `print2Column` | Left/right text and relative column weights (default 60/40); default font size 16 |
| `print3Column` | Left/center/right text and relative weights (default 60/40/40); default font size 16 |
| `printLine` | Repeats `lineStyle`; default 19 repetitions |
| `printSpace` | Appends blank lines; zero lines is a no-op |
| `printQRCode` | Text, width and height in pixels; centered |
| `printBarCode128` | Code 128 text, width and height in pixels; centered, without human-readable digits |
| `printImage` | Encoded PNG/JPEG bytes, centered and scaled to printer width by the SDK |
| `printBitMap` | Alias for encoded-image printing |

Column weights are normalized by the SDK and do not have to total 100. Each must be positive; `60/15/25` is a practical three-column starting point. Check long text, wrapping, and the actual receipt language on hardware.

```dart
await printer.printQRCode(text: 'ORDER-001', width: 200, height: 200);
await printer.printBarCode128(text: '1234567890', width: 300, height: 80);
```

Images take encoded file bytes, not a path string or raw bitmap pixels:

```dart
import 'package:flutter/services.dart';

final asset = await rootBundle.load('assets/logo.png');
final bytes = asset.buffer.asUint8List(asset.offsetInBytes, asset.lengthInBytes);
final printed = await printer.printImage(pathImage: bytes);
```

Declare the image asset in the host app's `pubspec.yaml`. Use a receipt-sized image to limit memory and paper use. Images exceeding 16 megapixels or 16,384 pixels on either side, malformed image data, invalid argument types, font sizes outside 1–256, QR/barcode dimensions outside 1–4096, and feed/repeat counts outside 0–1000 are rejected. Column weights are limited to 1–1,000,000 and generated separator text to 65,536 characters.

Paper cutting is not exposed by this plugin.

## Results and recovery

`true` means the vendor SDK returned `SDK_OK` for printing (or a valid zero-length feed/separator completed). `false` means initialization failed, the printer was not ready, arguments were invalid, native libraries were unavailable, or printing failed. Every recognized request has a reply when SDK calls return or raise a handled exception; there is no Dart-side timeout or cancellation of a blocked native call. Unknown channel methods return `notImplemented` without touching hardware.

Channel failures can throw `PlatformException`; unsupported platforms or missing native registration can throw `MissingPluginException`. Handle these in your app, as the example does.

The SDK status codes inspected in this version are:

| Code | Meaning |
| --- | --- |
| `0` | SDK success/ready |
| `-1001` | SDK error |
| `-1002` | SDK parameter error |
| `-1003` | SDK timeout |
| `-1401` | Printer error |
| `-1402` | Printer parameter error |
| `-1403` | Paper out |
| `-1404` | Overheated |
| `-1405` | Printer fault |
| `-1406` | Already printing |

These codes are logged under `z92PosPrinter`; they are not exposed by the boolean Dart API. Inspect logs on the selected terminal:

```sh
adb -s DEVICE_ID logcat -s z92PosPrinter
```

Printing uses application context and a background serial queue. Activity rotation and detachment do not retain an old Activity. Engine detachment skips queued requests belonging to that engine; a request already executing in the vendor SDK can finish and cannot safely be canceled. Resume/reboot recovery and Z91 behavior still need device testing.

## Run the example and software checks

Use Flutter 3.44.2 to reproduce the software checks. The example provides receipt, QR, Code 128, and asset-image buttons, disables them while a job is running, and reports failures. Its release APK uses the example's debug signing key for testing; configure production signing in your own app.

```sh
flutter pub get
flutter analyze
flutter test
cd example
flutter test
flutter build apk --debug --target-platform android-arm,android-arm64
flutter build apk --release --target-platform android-arm,android-arm64
cd android
./gradlew :z92_pos_printer_flutter_sdk:testDebugUnitTest
```

Native unit tests mock the SDK printer to check return codes, readiness, dimensions, column styling, and invalid inputs. They do not load ARM JNI libraries or prove physical printing. The obfuscated vendor JAR lacks JVM stack-map frames, so these desktop tests use `-Xverify:none`; APK builds retain normal Android DEX verification. Flutter generates the local Gradle wrapper when building the example.

## Z92 acceptance checklist

- Record exact Z92 model, ZOS/firmware build, Android version, CPU ABI, printer module, and paper width. Confirm SDK suitability with the supplier.
- Install and run the example on the deployment Android 13 terminal. Confirm cold-start initialization and a successful receipt in both debug and your app's release build.
- Check left/center/right text, bold/font sizes, two/three columns, long values, separators, feed, and the actual languages/fonts.
- Scan printed QR and Code 128 codes; verify unequal width/height values and barcode readability.
- Test PNG/JPEG input through both `printImage` and `printBitMap`; verify image scale, contrast, and memory use.
- Remove/reload paper, open/close the cover if supported, and exercise legitimate SDK error states. Confirm failure feedback and successful recovery without automatically duplicating a sale.
- Test rapid taps, repeated receipts, background/resume, rotation, engine recreation, and a terminal reboot. Confirm no stuck UI or unfinished replies in normal device operation.
- Run a Z91 regression receipt if existing Z91 users are supported. Z91 hardware behavior has not been certified by a Z92 audit.

A passing build or mock test is not evidence that a physical Z92 has printed correctly.
