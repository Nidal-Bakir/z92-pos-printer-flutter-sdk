# Z92 printer example

Demonstrates the `z92_pos_printer_flutter_sdk` package on a ZCS Z92 running ZOS based on Android 13. Software checks use Flutter 3.44.2.

```sh
flutter pub get
flutter run -d DEVICE_ID
```

The example prints a sample receipt, QR code, Code 128 barcode, and the bundled image. It awaits each receipt operation, stops at the first failure, and prevents repeated taps while printing.

Run this on a compatible ZCS terminal with paper loaded. A normal Android phone or emulator cannot verify built-in Z92 printing. Check the actual output even when the SDK reports success.

See the [package README](../README.md) for integration requirements, software checks, error codes, and the device acceptance checklist.
