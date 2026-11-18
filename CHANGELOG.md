# Changelog

## 1.2.0

- Fix physical left/right text alignment for Arabic and mixed-direction
  paragraphs, including multiline text, columns, and separators. Keep centered
  text and English-only printing on their existing vendor rendering path.
- Add `setPrintSpeed` with `PrintSpeed.low`, `medium`, and `high` in the Flutter
  API and Android bridge. Serialize configuration with printing, wait for
  readiness, and report vendor failures without printing or feeding paper.
- Preserve existing printer speed when the new API is not called. Add an
  example speed selector and regression tests for configuration, readiness,
  invalid values, failure handling, and engine detachment.

- Add example buttons for direct Arabic text and Flutter-rendered Arabic images,
  with the same image at the start of every sample batch.

## 1.1.0

- Add `printBatch` with typed `PrintCommand` entries for text, columns, images,
  QR codes, Code 128 barcodes, separators, and feed. Append in order and submit
  once on the existing background queue, with a single readiness wait.
- Reject malformed/nested batches and prevent submission after invalid input,
  append failures, interruption, or engine detachment. Preserve buffer cleanup
  and never automatically retry a failed submission.
- Demonstrate batch printing in the example and document migration from
  individual commands. Existing printing APIs retain their behavior.

## 1.0.2

- Wait up to 10 seconds for a printing printer before submitting the next command,
  polling every 100 ms on the background queue with a monotonic deadline.
- Keep terminal printer errors, timeout, interruption, and engine detachment as
  failures without retrying print submission; preserve append-buffer cleanup.
- Include method, numeric status, and readiness/submission phase in failure logs.

## 1.0.1

- clear SDK print buffer after failed jobs

## 1.0.0 — Initial release

- Android integration for built-in ZCS Z92 and Z91 POS printers.
- Text printing with alignment, font size, and bold styling.
- Two- and three-column layouts with relative widths and per-column alignment.
- Encoded PNG/JPEG image printing, QR codes, and Code 128 barcodes.
- Separator lines and blank-line feed.
- SDK initialization, serialized background printing, printer-status handling, and input validation.
- Android release shrinker rules for the vendor JNI bridge.
- Receipt example and Dart, widget, and native regression tests.
