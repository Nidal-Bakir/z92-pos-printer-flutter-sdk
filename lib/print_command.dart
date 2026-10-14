import 'dart:typed_data';

import 'z92_pos_printer_flutter_sdk.dart' show AlignmentPrint, PrinterPosSystem;

/// A command to append to [PrinterPosSystem.printBatch].
///
/// Constructing a command does not print anything. Native validation and
/// submission happen when the batch is sent to the printer.
class PrintCommand {
  final String _method;
  final Map<String, Object> _arguments;

  PrintCommand._(this._method, Map<String, Object> arguments)
      : _arguments = Map.unmodifiable(arguments);

  PrintCommand.text({
    required String text,
    AlignmentPrint alignment = AlignmentPrint.left,
    bool isBold = false,
    int fontSize = 30,
  }) : this._('printText', {
          'text': text,
          'Alignment': alignment.index,
          'isBold': isBold,
          'fontSize': fontSize,
        });

  /// Appends encoded PNG/JPEG bytes, including rasterized Arabic text.
  PrintCommand.image({required Uint8List pathImage})
      : this._('printImage', {'pathImage': pathImage});

  PrintCommand.qrCode({
    required String text,
    int width = 100,
    int height = 100,
  }) : this._('printQrCode', {
          'text': text,
          'width': width,
          'height': height,
        });

  PrintCommand.barCode128({
    required String text,
    int width = 100,
    int height = 100,
  }) : this._('printBarCode128', {
          'text': text,
          'width': width,
          'height': height,
        });

  PrintCommand.twoColumn({
    required String textLeft,
    required String textRight,
    int spaceLeft = 60,
    int spaceRight = 40,
    bool isBold = false,
    int fontSize = 16,
    AlignmentPrint leftAlign = AlignmentPrint.left,
    AlignmentPrint rightAlign = AlignmentPrint.right,
  }) : this._('print2Column', {
          'leftText': textLeft,
          'rightText': textRight,
          'leftTextSize': spaceLeft,
          'rightTextSize': spaceRight,
          'isBold': isBold,
          'fontSize': fontSize,
          'leftAlign': leftAlign.index,
          'rightAlign': rightAlign.index,
        });

  PrintCommand.threeColumn({
    required String textLeft,
    required String textCenter,
    required String textRight,
    int spaceLeft = 60,
    int spaceCenter = 40,
    int spaceRight = 40,
    bool isBold = false,
    int fontSize = 16,
    AlignmentPrint leftAlign = AlignmentPrint.left,
    AlignmentPrint centerAlign = AlignmentPrint.center,
    AlignmentPrint rightAlign = AlignmentPrint.right,
  }) : this._('print3Column', {
          'leftText': textLeft,
          'centerText': textCenter,
          'rightText': textRight,
          'leftTextSize': spaceLeft,
          'centerTextSize': spaceCenter,
          'rightTextSize': spaceRight,
          'isBold': isBold,
          'fontSize': fontSize,
          'leftAlign': leftAlign.index,
          'centerAlign': centerAlign.index,
          'rightAlign': rightAlign.index,
        });

  PrintCommand.line({
    bool isBold = true,
    AlignmentPrint alignment = AlignmentPrint.left,
    int fontSize = 30,
    int lineSize = 19,
    String lineStyle = ' - ',
  }) : this._('printLine', {
          'isBold': isBold,
          'Alignment': alignment.index,
          'fontSize': fontSize,
          'lineSize': lineSize,
          'lineStyle': lineStyle,
        });

  PrintCommand.space({required int space})
      : this._('printSpace', {'space': space});

  /// Encodes this command for the Android bridge.
  Map<String, Object> toMap() => {
        'method': _method,
        'arguments': _arguments,
      };
}
