import 'package:flutter/material.dart';
import 'package:flutter/services.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:z92_pos_printer_flutter_sdk_example/main.dart';

void main() {
  const channel = MethodChannel('z92PosPrinter');

  tearDown(() {
    TestDefaultBinaryMessengerBinding.instance.defaultBinaryMessenger
        .setMockMethodCallHandler(channel, null);
  });

  testWidgets('failed batch is not retried automatically and controls recover',
      (tester) async {
    final calls = <MethodCall>[];
    TestDefaultBinaryMessengerBinding.instance.defaultBinaryMessenger
        .setMockMethodCallHandler(channel, (call) async {
      calls.add(call);
      return false;
    });
    await tester.pumpWidget(const MyApp());
    await tester.tap(find.text('Print sample receipt'));
    await tester.pumpAndSettle();
    expect(calls.map((call) => call.method), ['printBatch']);
    expect(find.textContaining('Printing failed.'), findsOneWidget);
    final button = tester.widget<ElevatedButton>(
      find.widgetWithText(ElevatedButton, 'Print sample receipt'),
    );
    expect(button.onPressed, isNotNull);
  });

  testWidgets(
      'receipt is sent as one ordered batch including QR and final feed',
      (tester) async {
    final calls = <MethodCall>[];
    TestDefaultBinaryMessengerBinding.instance.defaultBinaryMessenger
        .setMockMethodCallHandler(channel, (call) async {
      calls.add(call);
      return true;
    });
    await tester.pumpWidget(const MyApp());
    await tester.tap(find.text('Print sample receipt'));
    await tester.pumpAndSettle();
    expect(calls.map((call) => call.method), ['printBatch']);
    final commands = calls.single.arguments['commands'] as List;
    expect(commands.map((command) => command['method']), [
      'printText',
      'printLine',
      'print3Column',
      'print3Column',
      'print2Column',
      'printText',
      'printQrCode',
      'printSpace',
    ]);
    expect(find.textContaining('SDK reported success.'), findsOneWidget);
  });
}
