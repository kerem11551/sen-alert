package com.senalert.app;

import android.animation.ValueAnimator;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.RadialGradient;
import android.graphics.RectF;
import android.graphics.Shader;
import android.util.AttributeSet;
import android.view.View;
import android.view.animation.DecelerateInterpolator;

/**
 * Tek beyaz top + iki yanında parantez şeklinde ("(" / ")") yarım-ay yaylar.
 * Yaylar TAM DAİRE değil - 110 derecelik aralık, üstte/altta boşluk bırakır.
 *
 * setState(...) SADECE durum değiştiğinde çağrılmalı (her sensör örneğinde değil).
 * Değişiklik olduğunda ~450ms'lik yumuşak bir geçiş (fade) oynar, sonra durur.
 */
public class ShakeOrbView extends View {

    public static final int LEVEL_NEUTRAL = 0; // kalibrasyon / duraklatıldı - yay yok
    public static final int LEVEL_GREEN   = 1; // 1 yay çifti (küçük)
    public static final int LEVEL_YELLOW  = 2; // 2 yay çifti (orta)
    public static final int LEVEL_RED     = 3; // 3 yay çifti (büyük)

    private static final long TRANSITION_MS = 450;
    private static final float ARC_SWEEP = 110f; // toplam açı, üst/altta boşluk bırakır

    // Yay çiftlerinin göreli yarıçapları (view boyutuna göre ölçeklenir) ve kalınlıkları
    private static final float[] LAYER_RADIUS_FRAC = {0.42f, 0.58f, 0.74f};
    private static final float[] LAYER_STROKE_DP   = {5f, 6f, 7f};

    private int fromLevel = LEVEL_NEUTRAL;
    private int toLevel = LEVEL_NEUTRAL;
    private int fromColor = Color.parseColor("#8A9C9A");
    private int toColor = Color.parseColor("#8A9C9A");
    private float progress = 1f; // 1 = geçiş bitmiş, durağan

    private final Paint ballPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint glowPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint arcPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF arcRect = new RectF();

    private ValueAnimator animator;

    public ShakeOrbView(Context context, AttributeSet attrs) {
        super(context, attrs);
        setLayerType(LAYER_TYPE_SOFTWARE, null); // shadowLayer için gerekli
        arcPaint.setStyle(Paint.Style.STROKE);
        arcPaint.setStrokeCap(Paint.Cap.ROUND);
    }

    /** color: durum rengi (yeşil/sarı/kırmızı/gri), level: LEVEL_* sabitlerinden biri */
    public void setState(int color, int level) {
        if (level == toLevel && color == toColor) return; // zaten bu durumdayız

        fromLevel = interpolatedLevelSnapshot();
        fromColor = interpolatedColorSnapshot();
        toLevel = level;
        toColor = color;
        progress = 0f;

        if (animator != null) animator.cancel();
        animator = ValueAnimator.ofFloat(0f, 1f);
        animator.setDuration(TRANSITION_MS);
        animator.setInterpolator(new DecelerateInterpolator());
        animator.addUpdateListener(a -> {
            progress = (float) a.getAnimatedValue();
            invalidate();
        });
        animator.start();
    }

    private int interpolatedLevelSnapshot() {
        // Geçiş yarıda kesilirse kaldığı yerden devam etmesi için basit yaklaşım: hedefi baz al
        return toLevel;
    }

    private int interpolatedColorSnapshot() {
        return blendColor(fromColor, toColor, progress);
    }

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        float w = getWidth();
        float h = getHeight();
        float cx = w / 2f;
        float cy = h / 2f;
        float ballRadius = Math.min(w, h) * 0.22f;

        int currentColor = blendColor(fromColor, toColor, progress);

        // ---- Top arkasındaki parlama (glow) ----
        glowPaint.setShader(new RadialGradient(
            cx, cy, ballRadius * 2.2f,
            new int[]{withAlpha(currentColor, 130), withAlpha(currentColor, 0)},
            new float[]{0f, 1f}, Shader.TileMode.CLAMP
        ));
        canvas.drawCircle(cx, cy, ballRadius * 2.2f, glowPaint);

        // ---- Yaylar (durum değişimini fade ile yansıtır) ----
        for (int i = 0; i < LAYER_RADIUS_FRAC.length; i++) {
            float fromOpacity = layerTargetOpacity(fromLevel, i);
            float toOpacity = layerTargetOpacity(toLevel, i);
            float opacity = fromOpacity + (toOpacity - fromOpacity) * progress;
            if (opacity <= 0.01f) continue;

            float radius = Math.min(w, h) / 2f * LAYER_RADIUS_FRAC[i];
            float strokeWidth = dpToPx(LAYER_STROKE_DP[i]);
            arcPaint.setStrokeWidth(strokeWidth);
            arcPaint.setColor(currentColor);
            arcPaint.setAlpha((int) (255 * opacity));
            arcPaint.setShadowLayer(strokeWidth * 1.6f, 0, 0, currentColor);

            arcRect.set(cx - radius, cy - radius, cx + radius, cy + radius);

            // Sol yay "(" -> merkez açı 180°, ARC_SWEEP kadar aç
            canvas.drawArc(arcRect, 180f - ARC_SWEEP / 2f, ARC_SWEEP, false, arcPaint);
            // Sağ yay ")" -> merkez açı 0°, ARC_SWEEP kadar aç
            canvas.drawArc(arcRect, 0f - ARC_SWEEP / 2f, ARC_SWEEP, false, arcPaint);
        }

        // ---- Beyaz top (her zaman sabit, animasyonsuz) ----
        ballPaint.setShader(new RadialGradient(
            cx - ballRadius * 0.3f, cy - ballRadius * 0.35f, ballRadius * 1.6f,
            new int[]{Color.WHITE, Color.parseColor("#E8F3F1"), Color.parseColor("#B9C9C7")},
            new float[]{0f, 0.55f, 1f}, Shader.TileMode.CLAMP
        ));
        canvas.drawCircle(cx, cy, ballRadius, ballPaint);
    }

    /** level'a göre bu katmanın (0/1/2) hedef opaklığı: 1 aktifse tam görünür, değilse 0 */
    private float layerTargetOpacity(int level, int layerIndex) {
        return layerIndex < level ? 1f : 0f;
    }

    private int blendColor(int c1, int c2, float t) {
        t = Math.max(0f, Math.min(1f, t));
        int a = (int) (Color.alpha(c1) + (Color.alpha(c2) - Color.alpha(c1)) * t);
        int r = (int) (Color.red(c1)   + (Color.red(c2)   - Color.red(c1))   * t);
        int g = (int) (Color.green(c1) + (Color.green(c2) - Color.green(c1)) * t);
        int b = (int) (Color.blue(c1)  + (Color.blue(c2)  - Color.blue(c1))  * t);
        return Color.argb(a, r, g, b);
    }

    private int withAlpha(int color, int alpha) {
        return Color.argb(alpha, Color.red(color), Color.green(color), Color.blue(color));
    }

    private float dpToPx(float dp) {
        return dp * getResources().getDisplayMetrics().density;
    }
}
