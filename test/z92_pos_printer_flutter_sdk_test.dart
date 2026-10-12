import 'package:flutter/services.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:z92_pos_printer_flutter_sdk/z92_pos_printer_flutter_sdk.dart';
import 'package:z92_pos_printer_flutter_sdk/z92_pos_printer_flutter_sdk_method_channel.dart';
import 'package:z92_pos_printer_flutter_sdk/z92_pos_printer_flutter_sdk_platform_interface.dart';

void main() {
  TestWidgetsFlutterBinding.ensureInitialized();
  final platform = MethodChannelPosz92printer();
  final printer = PrinterPosSystem();
  final calls = <MethodCall>[];
  final originalPlatform = Posz92printerPlatform.instance;
  final messenger =
      TestDefaultBinaryMessengerBinding.instance.defaultBinaryMessenger;

  setUp(() {
    calls.clear();
    Posz92printerPlatform.instance = platform;
    messenger.setMockMethodCallHandler(platform.methodChannel, (call) async {
      calls.add(call);
      return true;
    });
  });

  tearDown(() {
    Posz92printerPlatform.instance = originalPlatform;
    messenger.setMockMethodCallHandler(platform.methodChannel, null);
  });

  test('public operations use the Android method names and preserve dimensions',
      () async {
    final image = Uint8List.fromList([137, 80, 78, 71]);
    await printer.printText(text: 'Receipt', alignment: AlignmentPrint.right);
    await printer.print2Column(textLeft: 'Total', textRight: '6.00');
    await printer.print3Column(
        textLeft: 'Item', textCenter: 'Qty', textRight: 'Price');
    await printer.printSpace(space: 3);
    await printer.printLine();
    await printer.printQRCode(text: 'QR', width: 200, height: 120);
    await printer.printBarCode128(text: '1234', width: 300, height: 80);
    await printer.printImage(pathImage: image);
    await printer.printBitMap(pathImage: image);

    expect(calls.map((call) => call.method), [
      'printText',
      'print2Column',
      'print3Column',
      'printSpace',
      'printLine',
      'printQrCode',
      'printBarCode128',
      'printImage',
      'printImageBitmap',
    ]);
    expect(calls[0].arguments['Alignment'], AlignmentPrint.right.index);
    expect(calls[1].arguments['leftTextSize'], 60);
    expect(calls[1].arguments['rightTextSize'], 40);
    expect(calls[2].arguments['centerAlign'], AlignmentPrint.center.index);
    expect(calls[5].arguments, {'text': 'QR', 'width': 200, 'height': 120});
    expect(calls[6].arguments, {'text': '1234', 'width': 300, 'height': 80});
    expect(calls[7].arguments['pathImage'], image);
    expect(calls[8].arguments['pathImage'], image);
  });

  test('direct platform column calls have usable defaults', () async {
    await platform.print2ColumnText(textLeft: 'L', textRight: 'R');
    await platform.print3ColumnText(
        textLeft: 'L', textCenter: 'C', textRight: 'R');
    for (final call in calls) {
      expect(call.arguments['leftTextSize'], 60);
      expect(call.arguments['rightTextSize'], 40);
      expect(call.arguments['fontSize'], 16);
    }
    expect(calls.last.arguments['centerTextSize'], 40);
  });

  test('false and platform failures reach callers', () async {
    messenger.setMockMethodCallHandler(
        platform.methodChannel, (_) async => false);
    expect(await printer.printText(text: 'Receipt'), isFalse);
    messenger.setMockMethodCallHandler(platform.methodChannel, (_) async {
      throw PlatformException(code: 'printer_failure');
    });
    await expectLater(
        printer.printText(text: 'Receipt'), throwsA(isA<PlatformException>()));
  });
}
