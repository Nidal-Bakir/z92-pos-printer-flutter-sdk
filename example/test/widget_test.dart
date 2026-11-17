import 'package:flutter/material.dart';
import 'package:flutter/services.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:z92_pos_printer_flutter_sdk/z92_pos_printer_flutter_sdk.dart';
import 'package:z92_pos_printer_flutter_sdk_example/main.dart';

Future<void> printSample(WidgetTester tester, List<MethodCall> calls,
    String label, int expectedCalls) async {
  await tester.ensureVisible(find.text(label));
  await tester.runAsync(() async {
    await tester.tap(find.text(label));
    final deadline = DateTime.now().add(const Duration(seconds: 10));
    while (calls.length < expectedCalls && DateTime.now().isBefore(deadline)) {
      await Future<void>.delayed(const Duration(milliseconds: 10));
    }
    expect(calls.length, expectedCalls);
    await Future<void>.delayed(Duration.zero);
  });
  await tester.pumpAndSettle();
}

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
    await printSample(tester, calls, 'Print sample receipt', 1);
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
    await printSample(tester, calls, 'Print sample receipt', 1);
    expect(calls.map((call) => call.method), ['printBatch']);
    final commands = calls.single.arguments['commands'] as List;
    expect(commands.map((command) => command['method']), [
      'printImage',
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

  testWidgets('selected speed is applied before printing the receipt', (
    tester,
  ) async {
    final calls = <MethodCall>[];
    TestDefaultBinaryMessengerBinding.instance.defaultBinaryMessenger
        .setMockMethodCallHandler(channel, (call) async {
      calls.add(call);
      return true;
    });
    await tester.pumpWidget(const MyApp());
    await tester.tap(find.byType(DropdownButton<PrintSpeed>));
    await tester.pumpAndSettle();
    await tester.tap(find.text('Medium').last);
    await tester.pumpAndSettle();
    await printSample(tester, calls, 'Print sample receipt', 2);
    expect(calls.map((call) => call.method), ['setPrintSpeed', 'printBatch']);
    expect(calls.first.arguments, {'speed': 1});
  });

  testWidgets('rejected speed prevents printing and releases the controls', (
    tester,
  ) async {
    final calls = <MethodCall>[];
    TestDefaultBinaryMessengerBinding.instance.defaultBinaryMessenger
        .setMockMethodCallHandler(channel, (call) async {
      calls.add(call);
      return false;
    });
    await tester.pumpWidget(const MyApp());
    await tester.tap(find.byType(DropdownButton<PrintSpeed>));
    await tester.pumpAndSettle();
    await tester.tap(find.text('Low').last);
    await tester.pumpAndSettle();
    await tester.tap(find.text('Print sample receipt'));
    await tester.pumpAndSettle();
    expect(calls.map((call) => call.method), ['setPrintSpeed']);
    expect(find.textContaining('Could not set print speed.'), findsOneWidget);
    expect(
      tester
          .widget<DropdownButton<PrintSpeed>>(
            find.byType(DropdownButton<PrintSpeed>),
          )
          .onChanged,
      isNotNull,
    );
  });
  for (final asImages in [false, true]) {
    testWidgets('Arabic images=$asImages starts with image in one batch',
        (tester) async {
      final calls = <MethodCall>[];
      TestDefaultBinaryMessengerBinding.instance.defaultBinaryMessenger
          .setMockMethodCallHandler(channel, (call) async {
        calls.add(call);
        return true;
      });
      await tester.pumpWidget(const MyApp());
      await printSample(tester, calls,
          asImages ? 'Print Arabic — images' : 'Print Arabic — direct text', 1);
      expect(calls.single.method, 'printBatch');
      final commands = calls.single.arguments['commands'] as List;
      expect(commands.first['method'], 'printImage');
      expect(commands.skip(1).take(9).map((c) => c['method']),
          List.filled(9, asImages ? 'printImage' : 'printText'));
      expect(commands[1]['arguments'][asImages ? 'pathImage' : 'text'],
          asImages ? isNotEmpty : 'موقف السيارات');
      expect(commands[commands.length - 2]['method'], 'printQrCode');
      expect(commands.last['method'], 'printSpace');
    });
  }
}
