import 'dart:ui' as ui;

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
  PrintSpeed? _speed;
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
      final speed = _speed;
      if (speed != null && await _printer.setPrintSpeed(speed: speed) != true) {
        status = 'Could not set print speed. Nothing was printed.';
      } else {
        status = await operation() == true
            ? 'SDK reported success. Check the printed output.'
            : 'Printing failed. Check paper, temperature, and device logs.';
      }
    } on PlatformException catch (error) {
      status = 'Platform error: ${error.code}';
    } on MissingPluginException {
      status = 'This example requires the Android ZCS printer plugin.';
    } catch (_) {
      status = 'Could not prepare or print the sample. Check device logs.';
    }
    if (!mounted) return;
    setState(() {
      _printing = false;
      _status = status;
    });
  }

  Future<bool?> _printReceipt() {
    // One submission; a retry could duplicate a partially printed sale.
    return _printCommands([
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
    return _printCommands([
      PrintCommand.image(
          pathImage: asset.buffer
              .asUint8List(asset.offsetInBytes, asset.lengthInBytes)),
      PrintCommand.space(space: 3),
    ]);
  }

  // Same image at the start of every sample, fitted to receipt width.
  Future<Uint8List> _headerImage() async {
    final asset = await rootBundle.load('asset/a4-print-test.png');
    final codec = await ui.instantiateImageCodec(
      asset.buffer.asUint8List(asset.offsetInBytes, asset.lengthInBytes),
      targetWidth: 120,
    );
    final frame = await codec.getNextFrame();
    try {
      final recorder = ui.PictureRecorder();
      final canvas = ui.Canvas(recorder);
      canvas.drawColor(const ui.Color(0xffffffff), ui.BlendMode.src);
      canvas.drawImage(
          frame.image, ui.Offset((384 - frame.image.width) / 2, 0), ui.Paint());
      return await _png(recorder.endRecording(), 384, frame.image.height);
    } finally {
      frame.image.dispose();
      codec.dispose();
    }
  }

  Future<Uint8List> _png(ui.Picture picture, int width, int height) async {
    ui.Image? image;
    try {
      image = await picture.toImage(width, height);
      final bytes = await image.toByteData(format: ui.ImageByteFormat.png);
      if (bytes == null) throw StateError('Could not encode receipt image');
      return bytes.buffer.asUint8List(bytes.offsetInBytes, bytes.lengthInBytes);
    } finally {
      image?.dispose();
      picture.dispose();
    }
  }

  Future<bool?> _printCommands(List<PrintCommand> commands) async {
    final header = await _headerImage();
    return _printer.printBatch(commands: [
      PrintCommand.image(pathImage: header),
      ...commands,
    ]);
  }

  Future<Uint8List> _arabicImage(String text) async {
    final paragraph = (ui.ParagraphBuilder(ui.ParagraphStyle(
      textDirection: ui.TextDirection.rtl,
      textAlign: ui.TextAlign.right,
      fontSize: 24,
    ))
          ..pushStyle(ui.TextStyle(color: const ui.Color(0xff000000)))
          ..addText(text))
        .build();
    try {
      paragraph.layout(const ui.ParagraphConstraints(width: 384));
      final recorder = ui.PictureRecorder();
      final canvas = ui.Canvas(recorder);
      canvas.drawColor(const ui.Color(0xffffffff), ui.BlendMode.src);
      canvas.drawParagraph(paragraph, ui.Offset.zero);
      return await _png(recorder.endRecording(), 384, paragraph.height.ceil());
    } finally {
      paragraph.dispose();
    }
  }

  Future<bool?> _printArabic({required bool asImages}) async {
    const lines = [
      'موقف السيارات',
      'إيصال دخول سيارة',
      'رقم التذكرة: 12345',
      'رقم السيارة: ١٢٣٤٥٦',
      'التاريخ: 2026/11/17',
      'وقت الدخول: 12:30',
      'المدة: ساعتان',
      'المبلغ الإجمالي: 15000 ل.س',
      'شكراً لزيارتكم',
    ];
    final commands = <PrintCommand>[];
    for (final line in lines) {
      commands.add(asImages
          ? PrintCommand.image(pathImage: await _arabicImage(line))
          : PrintCommand.text(
              text: line, alignment: AlignmentPrint.right, fontSize: 24));
    }
    commands.addAll([
      PrintCommand.qrCode(text: 'Z92-DEMO-001', width: 200, height: 200),
      PrintCommand.space(space: 3),
    ]);
    return _printCommands(commands);
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
            const Text('Print speed'),
            const Text(
                'Keep current skips speed configuration; it does not reset the last speed.'),
            DropdownButton<PrintSpeed>(
              value: _speed,
              isExpanded: true,
              hint: const Text('Keep current speed'),
              onChanged:
                  _printing ? null : (speed) => setState(() => _speed = speed),
              items: const [
                DropdownMenuItem(
                  value: null,
                  child: Text('Keep current speed'),
                ),
                DropdownMenuItem(value: PrintSpeed.low, child: Text('Low')),
                DropdownMenuItem(
                  value: PrintSpeed.medium,
                  child: Text('Medium'),
                ),
                DropdownMenuItem(value: PrintSpeed.high, child: Text('High')),
              ],
            ),
            ElevatedButton(
              onPressed: _printing ? null : () => _runPrint(_printReceipt),
              child: const Text('Print sample receipt'),
            ),
            ElevatedButton(
              onPressed: _printing
                  ? null
                  : () => _runPrint(() => _printArabic(asImages: false)),
              child: const Text('Print Arabic — direct text'),
            ),
            ElevatedButton(
              onPressed: _printing
                  ? null
                  : () => _runPrint(() => _printArabic(asImages: true)),
              child: const Text('Print Arabic — images'),
            ),
            ElevatedButton(
              onPressed: _printing
                  ? null
                  : () => _runPrint(() => _printCommands([
                        PrintCommand.qrCode(
                            text: 'Z92-DEMO-001', width: 200, height: 200),
                        PrintCommand.space(space: 3),
                      ])),
              child: const Text('Print QR code'),
            ),
            ElevatedButton(
              onPressed: _printing
                  ? null
                  : () => _runPrint(() => _printCommands([
                        PrintCommand.barCode128(
                            text: '1234567890', width: 300, height: 80),
                        PrintCommand.space(space: 3),
                      ])),
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
