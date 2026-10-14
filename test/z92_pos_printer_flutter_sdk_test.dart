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

  test('mixed batch uses one native call and preserves standalone mappings',
      () async {
    final logo = Uint8List.fromList([137, 80, 78, 71]);
    final arabic = Uint8List.fromList([137, 80, 78, 71, 1]);
    await printer.printImage(pathImage: logo);
    await printer.printImage(pathImage: arabic);
    await printer.printText(
        text: 'Header', alignment: AlignmentPrint.center, isBold: true);
    await printer.print2Column(textLeft: 'Total', textRight: '6.00');
    await printer.print3Column(
        textLeft: 'Item', textCenter: 'Qty', textRight: 'Price');
    await printer.printLine();
    await printer.printBarCode128(text: '1234', width: 300, height: 80);
    await printer.printQRCode(text: 'QR', width: 200, height: 120);
    await printer.printSpace(space: 3);
    final expected = calls
        .map((call) => {'method': call.method, 'arguments': call.arguments})
        .toList();
    calls.clear();

    expect(
        await printer.printBatch(commands: [
          PrintCommand.image(pathImage: logo),
          PrintCommand.image(pathImage: arabic),
          PrintCommand.text(
              text: 'Header', alignment: AlignmentPrint.center, isBold: true),
          PrintCommand.twoColumn(textLeft: 'Total', textRight: '6.00'),
          PrintCommand.threeColumn(
              textLeft: 'Item', textCenter: 'Qty', textRight: 'Price'),
          PrintCommand.line(),
          PrintCommand.barCode128(text: '1234', width: 300, height: 80),
          PrintCommand.qrCode(text: 'QR', width: 200, height: 120),
          PrintCommand.space(space: 3),
        ]),
        isTrue);
    expect(calls, hasLength(1));
    expect(calls.single.method, 'printBatch');
    expect(calls.single.arguments, {'commands': expected});
  });

  test('batch column styling and unequal dimensions are encoded explicitly',
      () async {
    await printer.printBatch(commands: [
      PrintCommand.threeColumn(
          textLeft: 'L',
          textCenter: 'C',
          textRight: 'R',
          spaceLeft: 50,
          spaceCenter: 20,
          spaceRight: 30,
          isBold: true,
          fontSize: 18,
          leftAlign: AlignmentPrint.right,
          centerAlign: AlignmentPrint.left,
          rightAlign: AlignmentPrint.center),
    ]);
    final arguments = calls.single.arguments['commands'][0]['arguments'];
    expect(arguments['leftTextSize'], 50);
    expect(arguments['centerTextSize'], 20);
    expect(arguments['rightTextSize'], 30);
    expect(arguments['isBold'], isTrue);
    expect(arguments['fontSize'], 18);
    expect(arguments['leftAlign'], AlignmentPrint.right.index);
    expect(arguments['centerAlign'], AlignmentPrint.left.index);
    expect(arguments['rightAlign'], AlignmentPrint.center.index);
  });

  test('batch preserves false, null, and channel exceptions without retry',
      () async {
    final commands = [PrintCommand.text(text: 'Receipt')];
    for (final result in <bool?>[false, null]) {
      calls.clear();
      messenger.setMockMethodCallHandler(platform.methodChannel, (call) async {
        calls.add(call);
        return result;
      });
      expect(await printer.printBatch(commands: commands), result);
      expect(calls, hasLength(1));
    }
    calls.clear();
    messenger.setMockMethodCallHandler(platform.methodChannel, (call) async {
      calls.add(call);
      throw PlatformException(code: 'printer_failure');
    });
    await expectLater(printer.printBatch(commands: commands),
        throwsA(isA<PlatformException>()));
    expect(calls, hasLength(1));
  });

  test('empty batch is sent to native validation', () async {
    expect(await printer.printBatch(commands: []), isTrue);
    expect(calls.single.method, 'printBatch');
    expect(calls.single.arguments, {'commands': []});
  });
}
