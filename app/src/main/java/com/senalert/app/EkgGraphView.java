package com.senalert.app;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.DashPathEffect;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;
import android.util.AttributeSet;
import android.view.View;

/**
 * EKG tarzı akan sarsıntı grafiği.
 * Her eşik: solda SADE (parlamasız) referans noktası + kesikli çizgi + eş
 * genişlikte renkli rozet. Dikkat beyaz dalgada kalsın diye noktalar artık
 * ışık saçmıyor, sadece sabit bir renk işareti.
 */
public class EkgGraphView extends View {

    private static final int MAX_SAMPLES = 100;
    private final float[] samples = new float[MAX_SAMPLES];
    private int sampleCount = 0;
    private int writeIndex = 0;

    private final Paint linePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint dashPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint dotPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint badgePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint badgeTextPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Path wavePath = new Path();
    private final RectF badgeRect = new RectF();

    private static final float LINE_GREEN  = 0.06f;
    private static final float LINE_YELLOW = 0.30f;
    private static final float LINE_RED    = 0.80f;

    private static final String LABEL_RED    = "GÜÇLÜ";
    private static final String LABEL_YELLOW = "SARSINTI";
    private static final String LABEL_GREEN  = "NORMAL";

    private final float density;
    private float badgeWidth = 0f;
    private float badgeHeight;

    public EkgGraphView(Context context, AttributeSet attrs) {
        super(context, attrs);
        density = getResources().getDisplayMetrics().density;
        float scaledDensity = getResources().getDisplayMetrics().scaledDensity;

        linePaint.setColor(Color.parseColor("#FFFFFF"));
        linePaint.setStyle(Paint.Style.STROKE);
        linePaint.setStrokeWidth(2.2f * density);
        linePaint.setStrokeJoin(Paint.Join.ROUND);
        linePaint.setStrokeCap(Paint.Cap.ROUND);
        linePaint.setShadowLayer(4f * density, 0, 0, Color.parseColor("#F4FBFA"));
        setLayerType(LAYER_TYPE_SOFTWARE, null);

        dashPaint.setStyle(Paint.Style.STROKE);
        dashPaint.setStrokeWidth(2f * density);
        dashPaint.setPathEffect(new DashPathEffect(new float[]{8f * density, 7f * density}, 0));
        dashPaint.setAlpha(200);

        // Sade, parlamasız referans noktası - dikkat beyaz dalgada kalsın
        dotPaint.setStyle(Paint.Style.FILL);

        badgePaint.setStyle(Paint.Style.FILL);

        badgeTextPaint.setTextSize(13f * scaledDensity);
        badgeTextPaint.setAntiAlias(true);
        badgeTextPaint.setFakeBoldText(true);
        badgeTextPaint.setTextAlign(Paint.Align.CENTER);

        badgeHeight = 26f * density;
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

        if (badgeWidth == 0f) {
            float maxTextW = Math.max(badgeTextPaint.measureText(LABEL_YELLOW),
                Math.max(badgeTextPaint.measureText(LABEL_RED), badgeTextPaint.measureText(LABEL_GREEN)));
            badgeWidth = maxTextW + 24f * density;
        }

        drawThresholdRow(canvas, w, topMargin + usableH * (1f - LINE_RED),    "#FF3B30", LABEL_RED,    Color.WHITE);
        drawThresholdRow(canvas, w, topMargin + usableH * (1f - LINE_YELLOW), "#FFD60A", LABEL_YELLOW, Color.BLACK);
        drawThresholdRow(canvas, w, topMargin + usableH * (1f - LINE_GREEN),  "#22E88A", LABEL_GREEN,  Color.BLACK);

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

    private void drawThresholdRow(Canvas canvas, float w, float y, String colorHex, String label, int textColor) {
        int color = Color.parseColor(colorHex);

        float dotRadius = 5.5f * density;
        float dotX = 14f * density + dotRadius;

        // Parlamasız, sade dolgu - sadece renk referansı
        dotPaint.setColor(color);
        dotPaint.setAlpha(230);
        canvas.drawCircle(dotX, y, dotRadius, dotPaint);

        float badgeRight = w - 12f * density;
        float badgeLeft = badgeRight - badgeWidth;
        badgeRect.set(badgeLeft, y - badgeHeight / 2f, badgeRight, y + badgeHeight / 2f);
        badgePaint.setColor(color);
        canvas.drawRoundRect(badgeRect, badgeHeight / 2f, badgeHeight / 2f, badgePaint);

        float lineStart = dotX + dotRadius + 8f * density;
        float lineEnd = badgeLeft - 8f * density;
        dashPaint.setColor(color);
        canvas.drawLine(lineStart, y, lineEnd, y, dashPaint);

        badgeTextPaint.setColor(textColor);
        float textY = y - (badgeTextPaint.descent() + badgeTextPaint.ascent()) / 2f;
        canvas.drawText(label, badgeLeft + badgeWidth / 2f, textY, badgeTextPaint);
    }
}
