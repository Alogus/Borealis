package io.borealis;

import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Path;

import java.util.Random;

/**
 * Confetti and streamers for a celebration. Pieces are fired from the bottom corners (cannons) or fall from the top
 * (a shower); streamers are ribbons that curl through the air and then drop. Everything is in screen pixels and fixed
 * arrays: nothing is allocated while it plays.
 */
final class Confetti {
    private static final int PIECES = 260, STREAMERS = 18, TRAIL = 22;
    private static final int[] COLORS = {Brand.LILY, Brand.PEACH, Brand.MELON, 0xFFFFFFFF, Brand.LILY, Brand.PEACH, Brand.MELON, 0xFFFFE9A8};

    private final Random rnd = new Random(2000);
    private float w, h, k = 1;

    private final boolean[] live = new boolean[PIECES];
    private final float[] x = new float[PIECES], y = new float[PIECES], vx = new float[PIECES], vy = new float[PIECES];
    private final float[] rot = new float[PIECES], vr = new float[PIECES], size = new float[PIECES], phase = new float[PIECES], flap = new float[PIECES];
    private final int[] color = new int[PIECES];
    private int nextPiece;

    private final boolean[] sLive = new boolean[STREAMERS];
    private final float[][] px = new float[STREAMERS][TRAIL], py = new float[STREAMERS][TRAIL];
    private final int[] count = new int[STREAMERS], sColor = new int[STREAMERS];
    private final float[] hx = new float[STREAMERS], hy = new float[STREAMERS], hvx = new float[STREAMERS], hvy = new float[STREAMERS];
    private final float[] age = new float[STREAMERS], life = new float[STREAMERS], since = new float[STREAMERS], droop = new float[STREAMERS], sPhase = new float[STREAMERS];
    private int nextStreamer;

    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Path path = new Path();

    void size(int width, int height) {
        w = width;
        h = height;
        k = width / 1920f;
    }

    private float rand(float a, float b) { return a + rnd.nextFloat() * (b - a); }

    private int spawnPiece() {
        int i = nextPiece;
        nextPiece = (nextPiece + 1) % PIECES;
        live[i] = true;
        color[i] = COLORS[rnd.nextInt(COLORS.length)];
        size[i] = rand(14, 26) * k;
        rot[i] = rand(0, 360);
        vr[i] = rand(-420, 420);
        phase[i] = rand(0, 6.28f);
        flap[i] = rand(7, 14);
        return i;
    }

    /** Two cannons, one at each bottom corner, firing up and toward the middle. */
    void cannons(int pieces, int streamers) {
        for (int n = 0; n < pieces; n++) {
            boolean left = (n & 1) == 0;
            int i = spawnPiece();
            double ang = Math.toRadians(rand(48, 84));
            float speed = rand(1300, 2700) * k;
            x[i] = (left ? 0.015f : 0.985f) * w;
            y[i] = h * 0.99f;
            vx[i] = (float) Math.cos(ang) * speed * (left ? 1 : -1);
            vy[i] = -(float) Math.sin(ang) * speed;
        }
        for (int n = 0; n < streamers; n++) {
            boolean left = (n & 1) == 0;
            int s = nextStreamer;
            nextStreamer = (nextStreamer + 1) % STREAMERS;
            sLive[s] = true;
            sColor[s] = COLORS[rnd.nextInt(4)];
            double ang = Math.toRadians(rand(55, 82));
            float speed = rand(1500, 2500) * k;
            hx[s] = (left ? 0.02f : 0.98f) * w;
            hy[s] = h * 0.98f;
            hvx[s] = (float) Math.cos(ang) * speed * (left ? 1 : -1);
            hvy[s] = -(float) Math.sin(ang) * speed;
            count[s] = 0;
            age[s] = 0;
            life[s] = rand(0.9f, 1.5f);
            since[s] = 1;
            droop[s] = 0;
            sPhase[s] = rand(0, 6.28f);
        }
    }

    /** A shower of pieces falling from above the top edge. */
    void shower(int pieces) {
        for (int n = 0; n < pieces; n++) {
            int i = spawnPiece();
            x[i] = rand(0, w);
            y[i] = rand(-0.5f * h, -20 * k);
            vx[i] = rand(-160, 160) * k;
            vy[i] = rand(60, 420) * k;
        }
    }

    boolean step(float dt) {
        boolean any = false;
        float g = 1250 * k, drag = Math.max(0, 1 - 2.1f * dt);
        for (int i = 0; i < PIECES; i++) {
            if (!live[i]) continue;
            vy[i] += g * dt;
            vx[i] *= drag;
            vy[i] *= drag;
            x[i] += vx[i] * dt;
            y[i] += vy[i] * dt;
            rot[i] += vr[i] * dt;
            phase[i] += flap[i] * dt;
            if (y[i] > h + 60 * k || x[i] < -80 * k || x[i] > w + 80 * k) live[i] = false;
            else any = true;
        }
        for (int s = 0; s < STREAMERS; s++) {
            if (!sLive[s]) continue;
            age[s] += dt;
            if (age[s] < life[s]) {
                // The head flies and curls; every 20 ms it leaves a point of the ribbon behind.
                hvy[s] += g * 0.55f * dt;
                hvx[s] += (float) Math.cos(age[s] * 15 + sPhase[s]) * 1500 * k * dt;
                hvx[s] *= Math.max(0, 1 - 1.6f * dt);
                hvy[s] *= Math.max(0, 1 - 1.6f * dt);
                hx[s] += hvx[s] * dt;
                hy[s] += hvy[s] * dt;
                since[s] += dt;
                if (since[s] >= 0.02f) {
                    since[s] = 0;
                    int n = Math.min(count[s], TRAIL - 1);
                    for (int j = n; j > 0; j--) {
                        px[s][j] = px[s][j - 1];
                        py[s][j] = py[s][j - 1];
                    }
                    px[s][0] = hx[s];
                    py[s][0] = hy[s];
                    count[s] = Math.min(count[s] + 1, TRAIL);
                }
            } else {
                droop[s] += (260 + 700 * (age[s] - life[s])) * k * dt;
                if (age[s] - life[s] > 1.1f) sLive[s] = false;
            }
            if (sLive[s]) any = true;
        }
        return any;
    }

    void draw(Canvas c) {
        paint.setStyle(Paint.Style.FILL);
        for (int i = 0; i < PIECES; i++) {
            if (!live[i]) continue;
            float flip = (float) Math.cos(phase[i]);
            float bw = size[i], bh = size[i] * 0.5f * Math.max(0.12f, Math.abs(flip));
            paint.setColor(color[i]);
            paint.setAlpha(flip < 0 ? 200 : 255);
            c.save();
            c.translate(x[i], y[i]);
            c.rotate(rot[i]);
            c.drawRect(-bw / 2, -bh / 2, bw / 2, bh / 2, paint);
            c.restore();
        }
        paint.setStyle(Paint.Style.STROKE);
        paint.setStrokeCap(Paint.Cap.ROUND);
        paint.setStrokeJoin(Paint.Join.ROUND);
        paint.setStrokeWidth(9 * k);
        for (int s = 0; s < STREAMERS; s++) {
            if (!sLive[s] || count[s] < 2) continue;
            float fade = age[s] < life[s] ? 1 : Ui.clamp(1 - (age[s] - life[s]) / 1.1f);
            path.rewind();
            for (int j = 0; j < count[s]; j++) {
                float yy = py[s][j] + droop[s] * (j / (float) TRAIL);
                if (j == 0) path.moveTo(px[s][j], yy);
                else path.lineTo(px[s][j], yy);
            }
            paint.setColor(sColor[s]);
            paint.setAlpha(Math.round(255 * fade));
            c.drawPath(path, paint);
        }
        paint.setStyle(Paint.Style.FILL);
    }
}
