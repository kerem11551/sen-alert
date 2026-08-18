package com.senalert.app;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.RectF;
import android.util.AttributeSet;
import android.view.View;

/**
 * Merkezde su terazisi (cihazın eğimini gösterir, ham X/Y ivmesinden),
 * etrafında sabit/animasyonsuz 3 seviye yayı (1=NORMAL, 2=SARSINTI,
 * 3=GÜÇLÜ), dış ince çerçeve. Sarsıntı algılama (dXY - ardışık fark)
 * ile eğim (tilt - ham X/Y) birbirinden bağımsız, karışmaz.
 *
 * BU TURDA: Baloncuğun kayma yönü ters çevrildi (kullanıcı geri
 * bildirimi - eskisi fiziksel sezgiyle tersti).
 */
public class ShakeOrbView extends View {

    public static final int LEVEL_NEUTRAL = 0;
    public static final int LEVEL_GREEN   = 1;
    public static final int LEVEL_YELLOW  = 2;
    public static final int LEVEL_RED     = 3;

    private static final int COLOR_GREEN  = Color.parseColor("#22E88A");
    private static final int COLOR_YELLOW = Color.parseColor("#FFD60A");
    private static final int COLOR_RED    = Color.parseColor("#FF3B30");
    private static final int COLOR_PALE   = Color.parseColor("#2A3634");
    private static final int COLOR_FRAME  = Color.parseColor("#1C2A2C");
    private static final int COLOR_TARGET = Color.parseColor("#3A4A48");
    private static final int COLOR_BUBBLE = Color.parseColor("#E8EEEC");

    private int currentLevel = LEVEL_NEUTRAL;

    private float tiltX = 0f;
    private float tiltY = 0f;

    private final Paint framePaint  = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint arcPaint    = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint numberPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint targetPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint bubblePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF arcRect = new RectF();
    private final RectF frameRect = new RectF();

    private final float density;

    public ShakeOrbView(Context context, AttributeSet attrs) {
        super(context, attrs);
        density = getResources().getDisplayMetrics().density;
        float scaledDensity = getResources().getDisplayMetrics().scaledDensity;

        framePaint.setStyle(Paint.Style.STROKE);
        framePaint.setStrokeWidth(1.5f * density);
        framePaint.setColor(COLOR_FRAME);

        arcPaint.setStyle(Paint.Style.STROKE);
        arcPaint.setStrokeWidth(6f * density);
        arcPaint.setStrokeCap(Paint.Cap.ROUND);

        numberPaint.setTextSize(12f * scaledDensity);
        numberPaint.setFakeBoldText(true);
        numberPaint.setTextAlign(Paint.Align.CENTER);

        targetPaint.setStyle(Paint.Style.STROKE);
        targetPaint.setStrokeWidth(1.5f * density);
        targetPaint.setColor(COLOR_TARGET);

        bubblePaint.setStyle(Paint.Style.FILL);
        bubblePaint.setColor(COLOR_BUBBLE);
        bubblePaint.setShadowLayer(6f * density, 0, 2f * density, Color.parseColor("#40000000"));
        setLayerType(LAYER_TYPE_SOFTWARE, null);
    }

    public void setState(int colorInt, int level) {
        if (this.currentLevel != level) {
            this.currentLevel = level;
            invalidate();
        }
    }

    /** Ham X/Y ivme değerleri (yerçekimi bileşeni) - cihazın eğimini gösterir */
    public void setTilt(float x, float y) {
        this.tiltX = x;
        this.tiltY = y;
        invalidate();
    }

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        float w = getWidth();
        float h = getHeight();
        float cx = w / 2f;
        float cy = h / 2f;

        float framePad = 3f * density;
        frameRect.set(framePad, framePad, w - framePad, h - framePad);
        canvas.drawRoundRect(frameRect, 16f * density, 16f * density, framePaint);

        float baseRadius = Math.min(w, h) * 0.20f;
        float ringGap = Math.min(w, h) * 0.10f;
        drawLevelRing(canvas, cx, cy, baseRadius,               LEVEL_GREEN,  COLOR_GREEN,  "1");
        drawLevelRing(canvas, cx, cy, baseRadius + ringGap,     LEVEL_YELLOW, COLOR_YELLOW, "2");
        drawLevelRing(canvas, cx, cy, baseRadius + ringGap * 2, LEVEL_RED,    COLOR_RED,    "3");

        float targetRadius = baseRadius * 0.60f;
        canvas.drawCircle(cx, cy, targetRadius, targetPaint);

        // Baloncuk - cihazın eğimine göre konumlanır (ham X/Y'den)
        // YÖN TERS ÇEVRİLDİ (kullanıcı geri bildirimi)
        float maxOffset = targetRadius * 0.55f;
        float nx = clamp(tiltX / 9.8f, -1f, 1f);
        float ny = clamp(-tiltY / 9.8f, -1f, 1f);
        float bubbleX = cx + nx * maxOffset;
        float bubbleY = cy + ny * maxOffset;
        float bubbleRadius = targetRadius * 0.28f;
        canvas.drawCircle(bubbleX, bubbleY, bubbleRadius, bubblePaint);
    }

    private void drawLevelRing(Canvas canvas, float cx, float cy, float radius,
                                int level, int activeColor, String label) {
        boolean active = currentLevel >= level;
        int color = active ? activeColor : COLOR_PALE;
        arcPaint.setColor(color);

        float arcSpan = 100f;
        float halfSpan = arcSpan / 2f;
        arcRect.set(cx - radius, cy - radius, cx + radius, cy + radius);

        canvas.drawArc(arcRect, 180f - halfSpan, arcSpan, false, arcPaint);
        canvas.drawArc(arcRect, 0f - halfSpan, arcSpan, false, arcPaint);

        numberPaint.setColor(color);
        float labelX = cx + radius + 14f * density;
        float labelY = cy - (numberPaint.descent() + numberPaint.ascent()) / 2f;
        canvas.drawText(label, labelX, labelY, numberPaint);
    }

    private float clamp(float v, float min, float max) {
        return Math.max(min, Math.min(max, v));
    }
}
