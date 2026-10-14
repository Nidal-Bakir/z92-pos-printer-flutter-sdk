import 'package:flutter/material.dart';
import 'package:flutter/services.dart';
import 'package:z92_pos_printer_flutter_sdk/z92_pos_printer_flutter_sdk.dart';

void main() => runApp(const MyApp());

class MyApp extends StatefulWidget {
  const MyApp({super.key});

  @override
  State<MyApp> createState() => _MyAppState();
}

class _MyAppState extends State<MyApp> {
  final _printer = PrinterPosSystem();
  bool _printing = false;
  String _status = 'Run on a ZCS Z92 or Z91 with paper loaded.';

  Future<void> _runPrint(Future<bool?> Function() operation) async {
    if (_printing) return;
    setState(() {
      _printing = true;
      _status = 'Printing…';
    });
    String status;
    try {
      status = await operation() == true
          ? 'SDK reported success. Check the printed output.'
          : 'Printing failed. Check paper, temperature, and device logs.';
    } on PlatformException catch (error) {
      status = 'Platform error: ${error.code}';
    } on MissingPluginException {
      status = 'This example requires the Android ZCS printer plugin.';
    }
    if (!mounted) return;
    setState(() {
      _printing = false;
      _status = status;
    });
  }

  Future<bool?> _printReceipt() {
    // One submission; a retry could duplicate a partially printed sale.
    return _printer.printBatch(commands: [
      PrintCommand.text(
        text: 'Z92 DEMO STORE',
        alignment: AlignmentPrint.center,
        isBold: true,
        fontSize: 24,
      ),
      PrintCommand.line(fontSize: 16),
      PrintCommand.threeColumn(
        textLeft: 'Item',
        textCenter: 'Qty',
        textRight: 'Price',
        spaceLeft: 60,
        spaceCenter: 15,
        spaceRight: 25,
        isBold: true,
      ),
      PrintCommand.threeColumn(
        textLeft: 'Coffee',
        textCenter: '2',
        textRight: '6.00',
        spaceLeft: 60,
        spaceCenter: 15,
        spaceRight: 25,
      ),
      PrintCommand.twoColumn(
        textLeft: 'TOTAL',
        textRight: '6.00',
        isBold: true,
      ),
      PrintCommand.text(
        text: 'Thank you!',
        alignment: AlignmentPrint.center,
        fontSize: 18,
      ),
      PrintCommand.qrCode(text: 'Z92-DEMO-001', width: 200, height: 200),
      PrintCommand.space(space: 3),
    ]);
  }

  Future<bool?> _printImage() async {
    final asset = await rootBundle.load('asset/a4-print-test.png');
    return _printer.printImage(
      pathImage:
          asset.buffer.asUint8List(asset.offsetInBytes, asset.lengthInBytes),
    );
  }

  @override
  Widget build(BuildContext context) {
    return MaterialApp(
      home: Scaffold(
        appBar: AppBar(title: const Text('Z92 printer example')),
        body: ListView(
          padding: const EdgeInsets.all(16),
          children: [
            Text(_status),
            const SizedBox(height: 16),
            ElevatedButton(
              onPressed: _printing ? null : () => _runPrint(_printReceipt),
              child: const Text('Print sample receipt'),
            ),
            ElevatedButton(
              onPressed: _printing
                  ? null
                  : () => _runPrint(() => _printer.printQRCode(
                        text: 'Z92-DEMO-001',
                        width: 200,
                        height: 200,
                      )),
              child: const Text('Print QR code'),
            ),
            ElevatedButton(
              onPressed: _printing
                  ? null
                  : () => _runPrint(() => _printer.printBarCode128(
                        text: '1234567890',
                        width: 300,
                        height: 80,
                      )),
              child: const Text('Print Code 128'),
            ),
            ElevatedButton(
              onPressed: _printing ? null : () => _runPrint(_printImage),
              child: const Text('Print asset image'),
            ),
          ],
        ),
      ),
    );
  }
}
