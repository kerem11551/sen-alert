package com.senalert.app;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.DashPathEffect;
import android.graphics.Paint;
import android.graphics.Path;
import android.util.AttributeSet;
import android.view.View;

/**
 * EKG tarzı akan sarsıntı grafiği - 3 kademeli tasarım (yeşil/sarı/kırmızı).
 * pushSample(0..1) ile beslenir.
 */
public class EkgGraphView extends View {

    private static final int MAX_SAMPLES = 100;
    private final float[] samples = new float[MAX_SAMPLES];
    private int sampleCount = 0;
    private int writeIndex = 0;

    private final Paint linePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint thresholdPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint labelPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Path wavePath = new Path();

    // 3 kademeli sınır çizgileri
    private static final float LINE_YELLOW = 0.40f;
    private static final float LINE_RED    = 0.75f;

    public EkgGraphView(Context context, AttributeSet attrs) {
        super(context, attrs);

        linePaint.setColor(Color.parseColor("#F4FBFA"));
        linePaint.setStyle(Paint.Style.STROKE);
        linePaint.setStrokeWidth(4f);
        linePaint.setStrokeJoin(Paint.Join.ROUND);
        linePaint.setShadowLayer(8f, 0, 0, Color.parseColor("#F4FBFA"));
        setLayerType(LAYER_TYPE_SOFTWARE, null);

        thresholdPaint.setStyle(Paint.Style.STROKE);
        thresholdPaint.setStrokeWidth(2f);
        thresholdPaint.setPathEffect(new DashPathEffect(new float[]{10f, 8f}, 0));
        thresholdPaint.setAlpha(140);

        labelPaint.setTextSize(20f);
        labelPaint.setAntiAlias(true);
    }

    public void pushSample(float frac) {
        frac = Math.max(0f, Math.min(1f, frac));
        samples[writeIndex] = frac;
        writeIndex = (writeIndex + 1) % MAX_SAMPLES;
        if (sampleCount < MAX_SAMPLES) sampleCount++;
        invalidate();
    }

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        float w = getWidth();
        float h = getHeight();
        float topMargin = h * 0.08f;
        float bottomMargin = h * 0.08f;
        float usableH = h - topMargin - bottomMargin;

        drawThresholdLine(canvas, w, topMargin, usableH, LINE_YELLOW, "#F5C518", "SARSINTI");
        drawThresholdLine(canvas, w, topMargin, usableH, LINE_RED, "#FF4438", "GÜÇLÜ");

        if (sampleCount < 2) return;

        wavePath.reset();
        float stepX = w / (MAX_SAMPLES - 1);
        int startIdx = (writeIndex - sampleCount + MAX_SAMPLES) % MAX_SAMPLES;

        for (int i = 0; i < sampleCount; i++) {
            int idx = (startIdx + i) % MAX_SAMPLES;
            float x = i * stepX;
            float y = topMargin + usableH * (1f - samples[idx]);
            if (i == 0) wavePath.moveTo(x, y);
            else wavePath.lineTo(x, y);
        }
        canvas.drawPath(wavePath, linePaint);
    }

    private void drawThresholdLine(Canvas canvas, float w, float topMargin, float usableH,
                                    float frac, String colorHex, String label) {
        float y = topMargin + usableH * (1f - frac);
        thresholdPaint.setColor(Color.parseColor(colorHex));
        canvas.drawLine(0, y, w, y, thresholdPaint);
        labelPaint.setColor(Color.parseColor(colorHex));
        canvas.drawText(label, w - labelPaint.measureText(label) - 8f, y - 6f, labelPaint);
    }
}
