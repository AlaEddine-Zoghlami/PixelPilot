package com.openipc.pixelpilot;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Rect;
import android.util.Log;

import java.io.InputStream;

/**
 * Ground-side rendering of Betaflight's OSD from MSP_DISPLAYPORT.
 *
 * <p>The VTX no longer burns the overlay into the video, so the ground draws it instead — which is
 * what allows a genuinely clean recording to exist at all. This is pixel-faithful rather than a
 * lookalike because Betaflight does not send OSD *fields*: it sends the finished canvas as
 * "write these glyph indices at this row/col" commands, and we rasterise them with the same
 * font atlas the VTX used.
 *
 * <p>Atlas layout (verified against the shipped fonts): 4 pages across x 256 glyph rows down, so
 * glyph {@code g} on {@code page} is the source rect {@code (page*gw, g*gh, gw, gh)}. Glyph size is
 * DERIVED from the image, so an HD/WTFOS atlas works unchanged. The grid comes from the STREAM, not
 * the font — Betaflight sends absolute coordinates for its own canvas, so deriving the grid from
 * the atlas would displace every glyph when a different-resolution font is used.
 */
public final class MspOsdCanvas {
    private static final String TAG = "MspOsd";

    private static final int MAX_COLS = 64, MAX_ROWS = 24;
    private static final int DP_CLEAR = 2, DP_WRITE = 3, DP_DRAW = 4;

    private final byte[][] glyph = new byte[MAX_ROWS][MAX_COLS];
    private final byte[][] page  = new byte[MAX_ROWS][MAX_COLS];
    private final byte[][] fGlyph = new byte[MAX_ROWS][MAX_COLS];
    private final byte[][] fPage  = new byte[MAX_ROWS][MAX_COLS];
    private int maxRow = -1, maxCol = -1;
    private volatile int generation = 0;

    private Bitmap atlas;
    private int glyphW, glyphH;
    private final Paint paint = new Paint(Paint.FILTER_BITMAP_FLAG);

    public boolean loadAtlas(Context ctx, String assetName) {
        try (InputStream is = ctx.getAssets().open(assetName)) {
            BitmapFactory.Options o = new BitmapFactory.Options();
            o.inScaled = false;                       // keep exact pixel dimensions
            atlas = BitmapFactory.decodeStream(is, null, o);
        } catch (Exception e) {
            Log.w(TAG, "atlas '" + assetName + "' not loadable: " + e.getMessage());
            return false;
        }
        if (atlas == null) return false;
        int w = atlas.getWidth(), h = atlas.getHeight();
        if (w % 4 != 0 || h % 256 != 0 || w / 4 < 8 || h / 256 < 8) {
            Log.w(TAG, "atlas " + w + "x" + h + " is not a 4-page x 256-row glyph atlas");
            atlas = null;
            return false;
        }
        glyphW = w / 4; glyphH = h / 256;
        Log.i(TAG, "atlas " + w + "x" + h + " -> glyph " + glyphW + "x" + glyphH);
        return true;
    }

    public boolean ready() { return atlas != null; }
    public int generation() { return generation; }

    /** Feed one MSP_DISPLAYPORT payload (bytes after the cmd byte). */
    public synchronized void feed(byte[] b, int off, int len) {
        if (len < 1) return;
        int sub = b[off] & 0xFF;
        if (sub == DP_CLEAR) {
            for (int r = 0; r < MAX_ROWS; r++) java.util.Arrays.fill(glyph[r], (byte) 0);
        } else if (sub == DP_WRITE && len >= 4) {
            int row = b[off + 1] & 0xFF, col = b[off + 2] & 0xFF, attr = b[off + 3] & 0xFF;
            if (row >= MAX_ROWS) return;
            if (row > maxRow) maxRow = row;
            int n = len - 4;
            if (col + n - 1 > maxCol) maxCol = col + n - 1;
            for (int i = 0; i < n; i++) {
                int c = col + i;
                if (c < 0 || c >= MAX_COLS) break;
                glyph[row][c] = b[off + 4 + i];
                page[row][c] = (byte) (attr & 0x03);   // page bits live in attr
            }
        } else if (sub == DP_DRAW) {
            for (int r = 0; r < MAX_ROWS; r++) {
                System.arraycopy(glyph[r], 0, fGlyph[r], 0, MAX_COLS);
                System.arraycopy(page[r], 0, fPage[r], 0, MAX_COLS);
            }
            generation++;
        }
    }

    /** Grid the FC is actually addressing: 53x20 (SD) or 50x18 (HD). */
    private int[] grid0() {
        if (maxCol < 0) return new int[]{ 53, 20 };
        return new int[]{ maxCol >= 50 ? 53 : 50, maxRow >= 18 ? 20 : 18 };
    }

    /**
     * Rasterise the current canvas into {@code out}, scaling glyphs into cells sized from the
     * TARGET bitmap. Cell size must come from the output, not the atlas, or a higher-resolution
     * font shrinks the whole overlay instead of just sharpening it.
     */
    public void render(Bitmap out) {
        if (atlas == null || out == null) return;
        // Copy the published canvas under the lock, then rasterise WITHOUT holding it. Rasterising
        // inside the lock made the renderer contend with feed() on the MSP socket thread.
        final byte[][] sg = new byte[MAX_ROWS][MAX_COLS];
        final byte[][] sp = new byte[MAX_ROWS][MAX_COLS];
        int cols, rows;
        synchronized (this) {
            for (int r = 0; r < MAX_ROWS; r++) {
                System.arraycopy(fGlyph[r], 0, sg[r], 0, MAX_COLS);
                System.arraycopy(fPage[r], 0, sp[r], 0, MAX_COLS);
            }
            int[] g = grid0();
            cols = g[0]; rows = g[1];
        }
        int W = out.getWidth(), H = out.getHeight();
        int cellW = W / cols, cellH = H / rows;
        if (cellW <= 0 || cellH <= 0) return;
        int xoff = (W - cellW * cols) / 2, yoff = (H - cellH * rows) / 2;

        Canvas cv = new Canvas(out);
        out.eraseColor(0);                              // transparent
        Rect src = new Rect(), dst = new Rect();
        for (int r = 0; r < rows; r++) for (int c = 0; c < cols; c++) {
            int gl = sg[r][c] & 0xFF;
            if (gl == 0) continue;                      // empty cell
            int p = sp[r][c] & 0x03;
            src.set(p * glyphW, gl * glyphH, p * glyphW + glyphW, gl * glyphH + glyphH);
            if (src.bottom > atlas.getHeight()) continue;
            dst.set(xoff + c * cellW, yoff + r * cellH,
                    xoff + c * cellW + cellW, yoff + r * cellH + cellH);
            cv.drawBitmap(atlas, src, dst, paint);
        }
    }
}
