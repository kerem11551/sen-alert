package com.senalert.app;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.RadialGradient;
import android.graphics.Shader;
import android.util.AttributeSet;
import android.view.View;

/**
 * Tek top gösterge (Sig-Fi Compass'daki mantıkla aynı).
 * setLevel(fraction, color) her sensör okumasında çağrılır.
 */
public class ShakeOrbView extends View {

    private final Paint glowPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint corePaint = new Paint(Paint.ANTI_ALIAS_FLAG);

    private int currentColor = Color.parseColor("#35D07F");
    private float level = 0f; // 0..1, top büyüklüğü/parlaklığı için

    public ShakeOrbView(Context context, AttributeSet attrs) {
        super(context, attrs);
        // BlurMaskFilter yazılım katmanı gerektirir
        setLayerType(LAYER_TYPE_SOFTWARE, null);
    }

    /** color: Color.parseColor(...) ile üretilmiş durum rengi, level: 0..1 şiddet */
    public void setLevel(float level, int color) {
        this.level = Math.max(0f, Math.min(1f, level));
        this.currentColor = color;
        invalidate();
    }

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);

        float w = getWidth();
        float h = getHeight();
        float cx = w / 2f;
        float cy = h / 2f;
        float baseRadius = Math.min(w, h) / 2f * 0.62f;
        float radius = baseRadius * (1f + level * 0.12f);

        // Dış parlama (glow) - yarı saydam, büyük çember
        glowPaint.setColor(currentColor);
        glowPaint.setAlpha((int) (70 + level * 90));
        glowPaint.setShader(new RadialGradient(
            cx, cy, radius * 2.1f,
            new int[]{withAlpha(currentColor, 140), withAlpha(currentColor, 0)},
            new float[]{0f, 1f},
            Shader.TileMode.CLAMP
        ));
        canvas.drawCircle(cx, cy, radius * 2.1f, glowPaint);

        // Ana top - iç gradyan (üst-sol parlak, alt-sağ koyu)
        corePaint.setShader(new RadialGradient(
            cx - radius * 0.3f, cy - radius * 0.35f, radius * 1.6f,
            new int[]{lighten(currentColor), currentColor, darken(currentColor)},
            new float[]{0f, 0.55f, 1f},
            Shader.TileMode.CLAMP
        ));
        canvas.drawCircle(cx, cy, radius, corePaint);
    }

    private int withAlpha(int color, int alpha) {
        return Color.argb(alpha, Color.red(color), Color.green(color), Color.blue(color));
    }

    private int lighten(int color) {
        int r = Math.min(255, Color.red(color) + 90);
        int g = Math.min(255, Color.green(color) + 90);
        int b = Math.min(255, Color.blue(color) + 90);
        return Color.rgb(r, g, b);
    }

    private int darken(int color) {
        int r = (int) (Color.red(color) * 0.35f);
        int g = (int) (Color.green(color) * 0.35f);
        int b = (int) (Color.blue(color) * 0.35f);
        return Color.rgb(r, g, b);
    }
}
