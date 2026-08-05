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
 * EKG tarzı akan sarsıntı grafiği - 3 çizgi de (yeşil/sarı/kırmızı) gösterilir.
 * ÖNEMLİ: tüm boyutlar ekran yoğunluğuna (density) göre ölçekleniyor,
 * aksi halde yüksek yoğunluklu ekranlarda metin/çizgiler çok küçük kalır.
 */
public class EkgGraphView extends View {

    private static final int MAX_SAMPLES = 100;
    private final float[] samples = new float[MAX_SAMPLES];
    private int sampleCount = 0;
    private int writeIndex = 0;

    private final Paint glowPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint linePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint thresholdPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint labelPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Path wavePath = new Path();

    private static final float LINE_GREEN  = 0.08f;
    private static final float LINE_YELLOW = 0.35f;
    private static final float LINE_RED    = 0.72f;

    public EkgGraphView(Context context, AttributeSet attrs) {
        super(context, attrs);

        float density = getResources().getDisplayMetrics().density;
        float scaledDensity = getResources().getDisplayMetrics().scaledDensity;

        glowPaint.setColor(Color.parseColor("#F4FBFA"));
        glowPaint.setStyle(Paint.Style.STROKE);
        glowPaint.setStrokeWidth(11f * density);
        glowPaint.setStrokeJoin(Paint.Join.ROUND);
        glowPaint.setStrokeCap(Paint.Cap.ROUND);
        glowPaint.setAlpha(70);
        glowPaint.setShadowLayer(18f * density, 0, 0, Color.parseColor("#FFFFFF"));

        linePaint.setColor(Color.parseColor("#FFFFFF"));
        linePaint.setStyle(Paint.Style.STROKE);
        linePaint.setStrokeWidth(4.5f * density);
        linePaint.setStrokeJoin(Paint.Join.ROUND);
        linePaint.setStrokeCap(Paint.Cap.ROUND);
        linePaint.setShadowLayer(10f * density, 0, 0, Color.parseColor("#FFFFFF"));
        setLayerType(LAYER_TYPE_SOFTWARE, null);

        thresholdPaint.setStyle(Paint.Style.STROKE);
        thresholdPaint.setStrokeWidth(2.2f * density);
        thresholdPaint.setPathEffect(new DashPathEffect(new float[]{10f * density, 8f * density}, 0));
        thresholdPaint.setAlpha(190);

        labelPaint.setTextSize(18f * scaledDensity); // ~18sp
        labelPaint.setAntiAlias(true);
        labelPaint.setFakeBoldText(true);
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
        float topMargin = h * 0.14f;
        float bottomMargin = h * 0.10f;
        float usableH = h - topMargin - bottomMargin;

        drawThresholdLine(canvas, w, topMargin, usableH, LINE_RED,    "#FF3B30", "GÜÇLÜ");
        drawThresholdLine(canvas, w, topMargin, usableH, LINE_YELLOW, "#FFD60A", "SARSINTI");
        drawThresholdLine(canvas, w, topMargin, usableH, LINE_GREEN,  "#22E88A", "SABİT");

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
        canvas.drawPath(wavePath, glowPaint);
        canvas.drawPath(wavePath, linePaint);
    }

    private void drawThresholdLine(Canvas canvas, float w, float topMargin, float usableH,
                                    float frac, String colorHex, String label) {
        float y = topMargin + usableH * (1f - frac);
        thresholdPaint.setColor(Color.parseColor(colorHex));
        canvas.drawLine(0, y, w, y, thresholdPaint);
        labelPaint.setColor(Color.parseColor(colorHex));
        labelPaint.setShadowLayer(6f, 0, 0, Color.parseColor(colorHex));
        canvas.drawText(label, w - labelPaint.measureText(label) - 10f, y - 10f, labelPaint);
    }
}
