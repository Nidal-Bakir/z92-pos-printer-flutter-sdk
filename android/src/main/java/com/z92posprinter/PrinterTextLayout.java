package com.z92posprinter;

import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Typeface;
import android.text.Layout;
import android.text.SpannableString;
import android.text.Spanned;
import android.text.StaticLayout;
import android.text.TextPaint;
import android.text.style.AlignmentSpan;

import com.zcs.sdk.print.PrnStrFormat;
import com.zcs.sdk.print.PrnTextFont;
import com.zcs.sdk.print.PrnTextStyle;

import java.text.Bidi;

/** Physical left/right alignment using public Android APIs, including mixed paragraphs. */
final class PrinterTextLayout {
    private PrinterTextLayout() {}

    static boolean needed(String text, PrnStrFormat format) {
        return format.getAli() != Layout.Alignment.ALIGN_CENTER
                && Bidi.requiresBidi(text.toCharArray(), 0, text.length());
    }

    // Match the bundled SDK's paint, line spacing, padding and column proportions.
    @SuppressWarnings("deprecation") // This constructor also supports our minSdk 21.
    static StaticLayout layout(String text, PrnStrFormat format, int width) {
        TextPaint paint = new TextPaint();
        paint.setColor(Color.BLACK);
        paint.setTextSize(format.getTextSize());
        paint.setUnderlineText(format.isUnderline());
        paint.setTextScaleX(format.getTextScaleX());
        paint.setLetterSpacing(format.getLetterSpacing());
        Typeface font = format.getFont() == PrnTextFont.SANS_SERIF
                ? Typeface.SANS_SERIF : Typeface.MONOSPACE;
        paint.setTypeface(Typeface.create(font,
                format.getStyle() == PrnTextStyle.BOLD ? Typeface.BOLD : Typeface.NORMAL));
        StaticLayout measured = new StaticLayout(text, paint, width, format.getAli(),
                format.getLineSpacing(), 0, false);
        if (format.getAli() == Layout.Alignment.ALIGN_CENTER) return measured;

        SpannableString aligned = new SpannableString(text);
        for (int start = 0; start < text.length();) {
            int newline = text.indexOf('\n', start);
            int end = newline < 0 ? text.length() : newline + 1;
            boolean rtl = measured.getParagraphDirection(measured.getLineForOffset(start))
                    == Layout.DIR_RIGHT_TO_LEFT;
            // The bridge uses NORMAL for physical left and OPPOSITE for physical right.
            Layout.Alignment alignment = format.getAli();
            if (rtl) {
                alignment = alignment == Layout.Alignment.ALIGN_NORMAL
                        ? Layout.Alignment.ALIGN_OPPOSITE : Layout.Alignment.ALIGN_NORMAL;
            }
            aligned.setSpan(new AlignmentSpan.Standard(alignment), start, end,
                    Spanned.SPAN_PARAGRAPH);
            start = end;
        }
        return new StaticLayout(aligned, paint, width, format.getAli(),
                format.getLineSpacing(), 0, false);
    }

    static Bitmap render(String[] texts, int[] weights, PrnStrFormat[] formats, int width) {
        int totalWeight = 0;
        for (int weight : weights) totalWeight += weight;
        StaticLayout[] layouts = new StaticLayout[texts.length];
        int height = 0;
        for (int i = 0; i < texts.length; i++) {
            int columnWidth = (int) ((float) weights[i] / totalWeight * width);
            layouts[i] = layout(texts[i], formats[i], columnWidth);
            height = Math.max(height, layouts[i].getHeight() + 2);
        }
        Bitmap bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888);
        try {
            Canvas canvas = new Canvas(bitmap);
            canvas.drawColor(Color.WHITE);
            int x = 0;
            for (StaticLayout layout : layouts) {
                canvas.save();
                canvas.clipRect(x, 0, x + layout.getWidth(), height);
                canvas.translate(x + 1, 0); // Same one-dot inset as the vendor renderer.
                layout.draw(canvas);
                canvas.restore();
                x += layout.getWidth();
            }
            return bitmap;
        } catch (RuntimeException | Error e) {
            bitmap.recycle();
            throw e;
        }
    }
}
