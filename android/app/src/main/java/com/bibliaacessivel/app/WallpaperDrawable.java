package com.bibliaacessivel.app;

import android.content.res.Resources;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.ColorFilter;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.graphics.PixelFormat;
import android.graphics.Rect;
import android.graphics.drawable.Drawable;

/**
 * Papel de parede AGB recortado no centro (sem deformar) com uma camada escura por cima,
 * para o texto branco das telas manter contraste alto em qualquer tamanho de celular.
 */
final class WallpaperDrawable extends Drawable {
    private static Bitmap cached;
    private final Bitmap bitmap;
    private final int scrimAlpha;
    private final Paint paint = new Paint(Paint.FILTER_BITMAP_FLAG);
    private final Paint scrim = new Paint();
    private final Matrix matrix = new Matrix();

    WallpaperDrawable(Resources resources, int scrimAlpha) {
        this.bitmap = load(resources);
        this.scrimAlpha = scrimAlpha;
        scrim.setColor(0x031633);
        scrim.setAlpha(scrimAlpha);
    }

    /** Decodifica uma única vez; todas as telas reaproveitam a mesma imagem. */
    private static synchronized Bitmap load(Resources resources) {
        if (cached == null) {
            try {
                cached = BitmapFactory.decodeResource(resources, R.drawable.papel_de_parede);
            } catch (OutOfMemoryError error) {
                cached = null;
            }
        }
        return cached;
    }

    @Override public void draw(Canvas canvas) {
        Rect bounds = getBounds();
        if (bitmap != null && bounds.width() > 0 && bounds.height() > 0) {
            float scale = Math.max(bounds.width() / (float) bitmap.getWidth(),
                    bounds.height() / (float) bitmap.getHeight());
            float dx = bounds.left + (bounds.width() - bitmap.getWidth() * scale) / 2f;
            float dy = bounds.top + (bounds.height() - bitmap.getHeight() * scale) / 2f;
            matrix.setScale(scale, scale);
            matrix.postTranslate(dx, dy);
            canvas.save();
            canvas.clipRect(bounds);
            canvas.drawBitmap(bitmap, matrix, paint);
            canvas.restore();
        }
        if (scrimAlpha > 0) canvas.drawRect(bounds, scrim);
    }

    @Override public void setAlpha(int alpha) {
        paint.setAlpha(alpha);
    }

    @Override public void setColorFilter(ColorFilter colorFilter) {
        paint.setColorFilter(colorFilter);
    }

    @Override public int getOpacity() {
        return PixelFormat.OPAQUE;
    }
}
