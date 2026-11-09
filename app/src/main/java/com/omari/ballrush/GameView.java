// برمجة وتطوير: محمد العمري - 730215825
package com.omari.ballrush;

import android.content.Context;
import android.content.SharedPreferences;
import android.graphics.Canvas;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.RectF;
import android.graphics.Shader;
import android.graphics.Typeface;
import android.view.MotionEvent;
import android.view.SurfaceHolder;
import android.view.SurfaceView;

import java.util.ArrayList;
import java.util.Random;

public class GameView extends SurfaceView implements SurfaceHolder.Callback, Runnable {

    // ===== إعدادات يمكن تعديلها لضبط الصعوبة =====
    static final int TOTAL = 100;          // عدد المراحل
    static final int MAXB = 900;           // أقصى عدد كرات ظاهرة
    static final float GOAL_BASE = 3f;     // مضاعف الهدف في أول مرحلة
    static final float GOAL_GROW = 13f;    // زيادة المضاعف مع الصعوبة
    static final int[] MULTS = {2, 3, 2, 4, 5, 3, 10, 6, 20, 50, 100, 500};
    // ============================================

    static final float WW = 1000f, WH = 1900f;
    static final float R = 7f, G = 1500f, REST = 0.35f;
    static final float WALL_L = 70f, WALL_R = 930f, BUCKET_TOP = 1640f;
    static final int MENU = 0, LEVELS = 1, PLAY = 2, RESULT = 3;

    static class Gate { float baseX, y, w = 240, h = 56, amp, speed, phase, curX, remaining; int type, val, idx; boolean alive = true; }
    static class Peg { float x, y; }
    static class Bar { float x, y, len, ang; }
    static class Spinner { float x, y, len, speed, ang; }
    static class Btn { RectF r = new RectF(); String t; int id, color; }

    final Object lock = new Object();
    Thread thread; volatile boolean running; SurfaceHolder holder;
    SharedPreferences prefs;
    int state = MENU, level = 1, unlocked = 1;
    int[] stars = new int[TOTAL + 2];
    int sw = 1, sh = 1; float sc = 1, ox, oy;
    Paint p = new Paint(Paint.ANTI_ALIAS_FLAG), tp = new Paint(Paint.ANTI_ALIAS_FLAG);
    Shader bg;
    int[] cols = {0xFF22E6F0, 0xFFE63CE6, 0xFFF5F03C, 0xFFFFFFFF, 0xFF3CE68C};
    Random rnd = new Random();
    float anim;

    float[] bx = new float[MAXB], by = new float[MAXB], bvx = new float[MAXB], bvy = new float[MAXB];
    int[] bw = new int[MAXB]; long[] bm = new long[MAXB]; byte[] bcol = new byte[MAXB]; int n;

    ArrayList<Gate> gates = new ArrayList<>(); ArrayList<Peg> pegs = new ArrayList<>();
    ArrayList<Bar> bars = new ArrayList<>(); ArrayList<Spinner> spins = new ArrayList<>();
    float[] sg = new float[5 * 80]; int sgn;
    int supply, supplyTotal, goal; long collected;
    boolean started, finished, resWin; int resStars;
    float funnelX = 500, targetX = 500, time, emitAcc, endTimer, bucketX = 500, bucketW, bucketAmp, bucketSpeed, bump;

    ArrayList<Btn> btns = new ArrayList<>();
    float scroll, maxScroll, downY, lastY, moved;
    float[] decX = new float[40], decP = new float[40], decS = new float[40];

    public GameView(Context ctx) {
        super(ctx);
        holder = getHolder(); holder.addCallback(this);
        prefs = ctx.getSharedPreferences("ballrush", Context.MODE_PRIVATE);
        unlocked = prefs.getInt("unlocked", 1);
        for (int i = 1; i <= TOTAL; i++) stars[i] = prefs.getInt("s" + i, 0);
        tp.setTypeface(Typeface.DEFAULT_BOLD); tp.setStrokeJoin(Paint.Join.ROUND);
        for (int i = 0; i < 40; i++) { decX[i] = rnd.nextFloat(); decP[i] = rnd.nextFloat(); decS[i] = 0.08f + rnd.nextFloat() * 0.15f; }
        startLevel(1);
    }

    // ================= المراحل =================
    float rf(float a, float b) { return a + rnd.nextFloat() * (b - a); }

    void startLevel(int lv) {
        synchronized (lock) {
            level = lv; Random r = new Random(lv * 7919L + 13);
            float diff = Math.min(lv, 60) / 60f;
            supplyTotal = 20 + (int) (lv * 1.2f); supply = supplyTotal;
            goal = (int) (supplyTotal * (GOAL_BASE + GOAL_GROW * diff));
            gates.clear(); pegs.clear(); bars.clear(); spins.clear();
            int rows = 3 + Math.min(lv / 5, 4);
            float top = 520, bot = 1450, gap = (bot - top) / (rows - 1);
            int idx = 0;
            for (int row = 0; row < rows; row++) {
                float y = top + row * gap + r.nextInt(41) - 20;
                boolean two = row > 0 && r.nextFloat() < 0.4f + 0.4f * diff;
                boolean prevMinus = false;
                for (int k = 0; k < (two ? 2 : 1); k++) {
                    Gate g = new Gate(); g.idx = idx++; g.y = y;
                    g.baseX = two ? (k == 0 ? 290 + r.nextInt(60) : 710 - r.nextInt(60)) : 250 + r.nextInt(500);
                    boolean minus = row > 0 && !prevMinus && r.nextFloat() < 0.12f + 0.38f * diff;
                    if (minus) {
                        g.type = 2;
                        g.val = Math.max(8, (int) (supplyTotal * (0.4f + row * 0.5f * (0.3f + diff * 2f)) * (0.6f + r.nextFloat() * 0.8f)));
                        g.remaining = g.val; prevMinus = true;
                    } else if (row > 0 && r.nextFloat() < 0.2f) {
                        g.type = 1; g.val = 5 + r.nextInt(Math.min(10 + lv, 55));
                    } else {
                        g.type = 0; g.val = MULTS[r.nextInt(Math.min(MULTS.length, 2 + lv / 6))];
                    }
                    g.baseX = Math.max(190, Math.min(810, g.baseX));
                    if (lv >= 4) g.amp = Math.min(r.nextFloat() * Math.min(120f, lv * 6f), Math.min(g.baseX - 190, 810 - g.baseX));
                    g.speed = 0.6f + r.nextFloat() * 0.9f; g.phase = r.nextFloat() * 6.28f;
                    g.curX = g.baseX; gates.add(g);
                }
            }
            if (lv >= 2) for (int i = 0, c = Math.min(2 + lv / 3, 16); i < c; i++) {
                Peg pg = new Peg(); pg.x = 150 + r.nextInt(700); pg.y = 380 + r.nextInt(1100); pegs.add(pg);
            }
            if (lv >= 8) for (int i = 0, c = Math.min(1 + (lv - 8) / 5, 4); i < c; i++) {
                Bar b = new Bar(); b.x = 200 + r.nextInt(600); b.y = 400 + r.nextInt(1100); b.len = 160;
                b.ang = (r.nextBoolean() ? 1 : -1) * (0.4f + r.nextFloat() * 0.4f); bars.add(b);
            }
            if (lv >= 15) for (int i = 0, c = Math.min(1 + (lv - 15) / 8, 3); i < c; i++) {
                Spinner s = new Spinner(); s.x = 250 + r.nextInt(500); s.y = 650 + r.nextInt(750); s.len = 200;
                s.speed = (r.nextBoolean() ? 1 : -1) * (1f + r.nextFloat() * 2f); spins.add(s);
            }
            bucketW = Math.max(220f, 420f - 200f * diff);
            bucketAmp = lv >= 3 ? Math.min(Math.min(280f, lv * 10f), (860f - bucketW) / 2f - 10f) : 0;
            bucketSpeed = 0.5f + diff * 1.2f;
            n = 0; collected = 0; started = false; finished = false; time = 0; emitAcc = 0; endTimer = 0;
            funnelX = targetX = 500; bucketX = 500; bump = 0;
            updateSegs();
            state = PLAY;
        }
    }

    void addSeg(float x1, float y1, float x2, float y2, float half) {
        int o = sgn * 5; sg[o] = x1; sg[o + 1] = y1; sg[o + 2] = x2; sg[o + 3] = y2; sg[o + 4] = half; sgn++;
    }

    void updateSegs() {
        sgn = 0;
        addSeg(funnelX - 120, 140, funnelX - 30, 250, 10);
        addSeg(funnelX + 120, 140, funnelX + 30, 250, 10);
        for (Bar b : bars) {
            float dx = (float) Math.cos(b.ang) * b.len / 2, dy = (float) Math.sin(b.ang) * b.len / 2;
            addSeg(b.x - dx, b.y - dy, b.x + dx, b.y + dy, 10);
        }
        for (Spinner s : spins) {
            float dx = (float) Math.cos(s.ang) * s.len / 2, dy = (float) Math.sin(s.ang) * s.len / 2;
            addSeg(s.x - dx, s.y - dy, s.x + dx, s.y + dy, 9);
        }
        for (Peg pg : pegs) addSeg(pg.x, pg.y, pg.x, pg.y, 14);
        addSeg(bucketX - bucketW / 2, BUCKET_TOP, bucketX - bucketW / 2, BUCKET_TOP + 60, 9);
        addSeg(bucketX + bucketW / 2, BUCKET_TOP, bucketX + bucketW / 2, BUCKET_TOP + 60, 9);
    }

    // ================= الفيزياء =================
    int spawn(float x, float y, float vx, float vy, int w, long m) {
        int i = n++; bx[i] = x; by[i] = y; bvx[i] = vx; bvy[i] = vy; bw[i] = w; bm[i] = m; bcol[i] = (byte) rnd.nextInt(5); return i;
    }

    void collide(int i, int o) {
        float x1 = sg[o], y1 = sg[o + 1], x2 = sg[o + 2], y2 = sg[o + 3], half = sg[o + 4];
        float ex = x2 - x1, ey = y2 - y1, l2 = ex * ex + ey * ey, px = bx[i], py = by[i];
        float t = l2 > 0 ? ((px - x1) * ex + (py - y1) * ey) / l2 : 0;
        if (t < 0) t = 0; else if (t > 1) t = 1;
        float cx = x1 + t * ex, cy = y1 + t * ey, dx = px - cx, dy = py - cy, d2 = dx * dx + dy * dy, rr = R + half;
        if (d2 < rr * rr) {
            float d = (float) Math.sqrt(d2), nx, ny;
            if (d < 1e-3f) { nx = 0; ny = -1; } else { nx = dx / d; ny = dy / d; }
            bx[i] = cx + nx * rr; by[i] = cy + ny * rr;
            float vn = bvx[i] * nx + bvy[i] * ny;
            if (vn < 0) { bvx[i] -= (1 + REST) * vn * nx; bvy[i] -= (1 + REST) * vn * ny; }
            bvx[i] *= 0.995f;
        }
    }

    void physics(float h) {
        int n0 = n;
        outer:
        for (int i = 0; i < n0; i++) {
            if (bw[i] <= 0) continue;
            bvy[i] += G * h; bvx[i] *= 0.9995f;
            float sp2 = bvx[i] * bvx[i] + bvy[i] * bvy[i];
            if (sp2 > 1600f * 1600f) { float k = 1600f / (float) Math.sqrt(sp2); bvx[i] *= k; bvy[i] *= k; }
            bx[i] += bvx[i] * h; by[i] += bvy[i] * h;
            if (bx[i] < WALL_L + R) { bx[i] = WALL_L + R; if (bvx[i] < 0) bvx[i] *= -REST; }
            else if (bx[i] > WALL_R - R) { bx[i] = WALL_R - R; if (bvx[i] > 0) bvx[i] *= -REST; }
            for (int s = 0; s < sgn; s++) collide(i, s * 5);
            for (int g = 0; g < gates.size(); g++) {
                Gate gt = gates.get(g);
                if (!gt.alive) continue;
                if (Math.abs(bx[i] - gt.curX) < gt.w / 2 && Math.abs(by[i] - gt.y) < gt.h / 2) {
                    if (gt.type == 2) {
                        int take = (int) Math.min(gt.remaining, bw[i]);
                        gt.remaining -= take; bw[i] -= take;
                        if (gt.remaining <= 0) gt.alive = false;
                        if (bw[i] <= 0) continue outer;
                    } else if (((bm[i] >> gt.idx) & 1L) == 0) {
                        bm[i] |= 1L << gt.idx;
                        int extra = gt.type == 0 ? gt.val - 1 : gt.val;
                        if (n + extra <= MAXB) {
                            for (int j = 0; j < extra; j++)
                                spawn(bx[i] + rf(-10, 10), by[i] + rf(-6, 6), bvx[i] + rf(-140, 140), bvy[i] + rf(-40, 80), bw[i], bm[i]);
                        } else if (gt.type == 0) bw[i] = (int) Math.min(100000000L, (long) bw[i] * gt.val);
                        else bw[i] = (int) Math.min(100000000L, (long) bw[i] + gt.val);
                    }
                }
            }
            if (by[i] > BUCKET_TOP + 30 && Math.abs(bx[i] - bucketX) < bucketW / 2) {
                collected += bw[i]; bw[i] = 0; bump = 1f;
            } else if (by[i] > WH + 60) bw[i] = 0;
        }
        int k = 0;
        for (int i = 0; i < n; i++) if (bw[i] > 0) {
            if (k != i) { bx[k] = bx[i]; by[k] = by[i]; bvx[k] = bvx[i]; bvy[k] = bvy[i]; bw[k] = bw[i]; bm[k] = bm[i]; bcol[k] = bcol[i]; }
            k++;
        }
        n = k;
    }

    void update(float dt) {
        time += dt;
        funnelX += (targetX - funnelX) * Math.min(1f, dt * 14f);
        for (Gate g : gates) g.curX = g.baseX + g.amp * (float) Math.sin(time * g.speed + g.phase);
        for (Spinner s : spins) s.ang += s.speed * dt;
        bucketX = 500 + bucketAmp * (float) Math.sin(time * bucketSpeed);
        bump = Math.max(0, bump - dt * 4f);
        updateSegs();
        if (!started || finished) return;
        if (supply > 0) {
            emitAcc = Math.min(emitAcc + dt * 55f, 3f);
            while (emitAcc >= 1f && supply > 0 && n < MAXB) {
                spawn(funnelX + rf(-50, 50), rf(145, 165), rf(-20, 20), 40, 1, 0L);
                supply--; emitAcc -= 1f;
            }
        } else endTimer += dt;
        physics(dt / 2); updateSegs(); physics(dt / 2);
        if (endTimer > 10f) n = 0;
        if (supply == 0 && n == 0) finishLevel();
    }

    void finishLevel() {
        finished = true; resWin = collected >= goal;
        double r = (double) collected / goal;
        resStars = !resWin ? 0 : (r >= 2.5 ? 3 : (r >= 1.6 ? 2 : 1));
        if (resWin) {
            if (resStars > stars[level]) stars[level] = resStars;
            if (level < TOTAL && level + 1 > unlocked) unlocked = level + 1;
            SharedPreferences.Editor e = prefs.edit();
            e.putInt("unlocked", unlocked); e.putInt("s" + level, stars[level]); e.apply();
        }
        state = RESULT; buildResultButtons();
    }

    // ================= الواجهة =================
    @Override protected void onSizeChanged(int w, int h, int ow, int oh) {
        synchronized (lock) {
            sw = w; sh = h; sc = Math.min(w / WW, h / WH); ox = (w - WW * sc) / 2; oy = (h - WH * sc) / 2;
            bg = new LinearGradient(0, 0, 0, h, 0xFF4DA3FF, 0xFF2E6BE0, Shader.TileMode.CLAMP);
            buildMenuButtons();
        }
    }

    Btn mk(String t, int id, int color, float cx, float cy, float w, float h) {
        Btn b = new Btn(); b.t = t; b.id = id; b.color = color; b.r.set(cx - w / 2, cy - h / 2, cx + w / 2, cy + h / 2); return b;
    }

    void buildMenuButtons() {
        btns.clear();
        if (state == MENU) {
            btns.add(mk("ابدأ اللعب", 1, 0xFF2EC24A, sw / 2f, sh * 0.42f, sw * 0.7f, sh * 0.085f));
            btns.add(mk("المراحل", 2, 0xFFF5A623, sw / 2f, sh * 0.54f, sw * 0.7f, sh * 0.085f));
        } else if (state == LEVELS) {
            btns.add(mk("رجوع", 6, 0xFFE53935, sw * 0.16f, sh * 0.06f, sw * 0.25f, sh * 0.055f));
        }
    }

    void buildResultButtons() {
        btns.clear();
        float cy = sh * 0.56f, w = sw * 0.62f, h = sh * 0.065f;
        if (resWin && level < TOTAL) {
            btns.add(mk("المرحلة التالية", 3, 0xFF2EC24A, sw / 2f, cy, w, h));
            btns.add(mk("إعادة", 4, 0xFFF5A623, sw / 2f, cy + h * 1.3f, w, h));
            btns.add(mk("القائمة", 5, 0xFF8E2FB0, sw / 2f, cy + h * 2.6f, w, h));
        } else {
            btns.add(mk("إعادة", 4, 0xFFF5A623, sw / 2f, cy, w, h));
            btns.add(mk("القائمة", 5, 0xFF8E2FB0, sw / 2f, cy + h * 1.3f, w, h));
        }
    }

    void action(int id) {
        if (id == 1) startLevel(unlocked);
        else if (id == 2) { state = LEVELS; scroll = 0; buildMenuButtons(); }
        else if (id == 3) startLevel(level + 1);
        else if (id == 4) startLevel(level);
        else if (id == 5 || id == 6) { state = MENU; buildMenuButtons(); }
    }

    public boolean onBack() {
        synchronized (lock) {
            if (state == MENU) return false;
            if (state == PLAY || state == RESULT) { state = LEVELS; scroll = 0; } else state = MENU;
            buildMenuButtons(); return true;
        }
    }

    float cell() { return sw * 0.92f / 5f; }

    @Override public boolean onTouchEvent(MotionEvent e) {
        synchronized (lock) {
            float x = e.getX(), y = e.getY(); int a = e.getActionMasked();
            if (state == PLAY) {
                if (a == MotionEvent.ACTION_DOWN) {
                    float wx = (x - ox) / sc, wy = (y - oy) / sc;
                    if (Math.hypot(wx - 60, wy - 56) < 46) { state = LEVELS; scroll = 0; buildMenuButtons(); return true; }
                    started = true;
                }
                if (a == MotionEvent.ACTION_DOWN || a == MotionEvent.ACTION_MOVE)
                    targetX = Math.max(150f, Math.min(850f, (x - ox) / sc));
                return true;
            }
            if (state == LEVELS) {
                if (a == MotionEvent.ACTION_DOWN) { downY = lastY = y; moved = 0; }
                else if (a == MotionEvent.ACTION_MOVE) {
                    scroll = Math.max(0, Math.min(maxScroll, scroll - (y - lastY)));
                    moved += Math.abs(y - lastY); lastY = y;
                } else if (a == MotionEvent.ACTION_UP && moved < 25) {
                    for (Btn b : btns) if (b.r.contains(x, y)) { action(b.id); return true; }
                    float cs = cell(), x0 = (sw - cs * 5) / 2, gy0 = sh * 0.12f;
                    if (y > gy0) {
                        int c = (int) ((x - x0) / cs), r = (int) ((y - gy0 + scroll) / cs), lv = r * 5 + c + 1;
                        if (c >= 0 && c < 5 && lv >= 1 && lv <= TOTAL && lv <= unlocked) startLevel(lv);
                    }
                }
                return true;
            }
            if (a == MotionEvent.ACTION_UP)
                for (Btn b : btns) if (b.r.contains(x, y)) { action(b.id); break; }
            return true;
        }
    }

    // ================= الرسم =================
    static int shade(int c) { return 0xFF000000 | ((c >> 1) & 0x7F7F7F); }

    void txt(Canvas c, String s, float x, float y, float size, int col, boolean outline) {
        tp.setTextSize(size); tp.setTextAlign(Paint.Align.CENTER);
        if (outline) {
            tp.setStyle(Paint.Style.STROKE); tp.setStrokeWidth(size * 0.16f); tp.setColor(0xCC1A0A3A);
            c.drawText(s, x, y, tp);
        }
        tp.setStyle(Paint.Style.FILL); tp.setColor(col); c.drawText(s, x, y, tp);
    }

    void drawBtn(Canvas c, Btn b) {
        p.setStyle(Paint.Style.FILL);
        RectF s = new RectF(b.r); s.offset(0, 8);
        p.setColor(shade(b.color)); c.drawRoundRect(s, 28, 28, p);
        p.setColor(b.color); c.drawRoundRect(b.r, 28, 28, p);
        txt(c, b.t, b.r.centerX(), b.r.centerY() + b.r.height() * 0.15f, b.r.height() * 0.45f, 0xFFFFFFFF, true);
    }

    void draw(Canvas c) {
        p.setShader(bg); p.setStyle(Paint.Style.FILL); c.drawRect(0, 0, sw, sh, p); p.setShader(null);
        if (state == MENU) drawMenu(c);
        else if (state == LEVELS) drawLevels(c);
        else { drawGame(c); if (state == RESULT) drawResult(c); }
    }

    void drawMenu(Canvas c) {
        for (int i = 0; i < 40; i++) {
            float yy = ((decP[i] + anim * decS[i]) % 1f) * (sh + 60) - 30;
            p.setColor(cols[i % 5]); c.drawCircle(decX[i] * sw, yy, sw * (0.012f + (i % 4) * 0.004f), p);
        }
        txt(c, "اجمع الكرات", sw / 2f, sh * 0.2f, sw * 0.14f, 0xFFFFFFFF, true);
        txt(c, "BALL RUSH", sw / 2f, sh * 0.27f, sw * 0.08f, 0xFFF5F03C, true);
        for (Btn b : btns) drawBtn(c, b);
        txt(c, "المرحلة المتاحة: " + unlocked + " / " + TOTAL, sw / 2f, sh * 0.65f, sw * 0.05f, 0xFFFFFFFF, true);
        txt(c, "برمجة وتطوير: محمد العمري", sw / 2f, sh * 0.88f, sw * 0.055f, 0xFFFFFFFF, true);
        txt(c, "730215825", sw / 2f, sh * 0.92f, sw * 0.055f, 0xFFF5F03C, true);
    }

    void drawLevels(Canvas c) {
        txt(c, "اختر المرحلة", sw / 2f, sh * 0.075f, sw * 0.07f, 0xFFFFFFFF, true);
        float cs = cell(), x0 = (sw - cs * 5) / 2, gy0 = sh * 0.12f;
        maxScroll = Math.max(0, (TOTAL / 5) * cs - (sh - gy0) + 20);
        c.save(); c.clipRect(0, gy0, sw, sh);
        for (int lv = 1; lv <= TOTAL; lv++) {
            int r = (lv - 1) / 5, col = (lv - 1) % 5;
            float l = x0 + col * cs + cs * 0.06f, t = gy0 + r * cs - scroll + cs * 0.06f, s = cs * 0.88f;
            if (t > sh || t + s < gy0) continue;
            RectF rc = new RectF(l, t, l + s, t + s);
            boolean open = lv <= unlocked;
            p.setColor(open ? 0xFF8E2FB0 : 0xFF6B7A99); c.drawRoundRect(rc, 20, 20, p);
            txt(c, "" + lv, rc.centerX(), rc.centerY() + s * 0.1f, s * 0.38f, 0xFFFFFFFF, true);
            if (open && stars[lv] > 0) {
                String st = ""; for (int k = 0; k < stars[lv]; k++) st += "★";
                txt(c, st, rc.centerX(), rc.bottom - s * 0.08f, s * 0.2f, 0xFFFFD700, false);
            }
        }
        c.restore();
        for (Btn b : btns) drawBtn(c, b);
    }

    void drawGame(Canvas c) {
        c.save(); c.translate(ox, oy); c.scale(sc, sc);
        p.setStyle(Paint.Style.FILL);
        p.setColor(0xFFB347D9); c.drawRect(-2000, 112, WALL_L, 6000, p); c.drawRect(WALL_R, 112, 3000, 6000, p);
        p.setColor(0xFF8E2FB0); c.drawRect(WALL_L - 12, 112, WALL_L, 6000, p); c.drawRect(WALL_R, 112, WALL_R + 12, 6000, p);
        // العوائق
        p.setColor(0xFFC15BE8); p.setStrokeCap(Paint.Cap.ROUND);
        for (int s = 0; s < sgn; s++) {
            int o = s * 5;
            if (sg[o] == sg[o + 2] && sg[o + 1] == sg[o + 3]) { p.setStyle(Paint.Style.FILL); c.drawCircle(sg[o], sg[o + 1], sg[o + 4], p); }
            else { p.setStyle(Paint.Style.STROKE); p.setStrokeWidth(sg[o + 4] * 2); c.drawLine(sg[o], sg[o + 1], sg[o + 2], sg[o + 3], p); }
        }
        p.setStyle(Paint.Style.FILL);
        // السلة
        RectF bk = new RectF(bucketX - bucketW / 2, BUCKET_TOP, bucketX + bucketW / 2, 1870);
        p.setColor(0xFF7A2395); c.drawRoundRect(bk, 50, 50, p);
        p.setColor(0xFF5A1A70); c.drawRoundRect(new RectF(bk.left + 18, bk.top + 20, bk.right - 18, bk.bottom - 20), 40, 40, p);
        txt(c, "" + collected, bucketX, 1790, 60 + bump * 24, 0xFFFFFFFF, true);
        // البوابات
        for (Gate g : gates) {
            if (!g.alive) continue;
            int col = g.type == 2 ? 0xFFE53935 : g.type == 1 ? 0xFF00BCD4 : (g.val >= 10 ? 0xFF2EE62E : 0xFFF5A623);
            RectF r = new RectF(g.curX - g.w / 2, g.y - g.h / 2, g.curX + g.w / 2, g.y + g.h / 2);
            RectF s = new RectF(r); s.offset(0, 8);
            p.setColor(shade(col)); c.drawRoundRect(s, 10, 10, p);
            p.setColor(col); c.drawRoundRect(r, 10, 10, p);
            String t = g.type == 0 ? "X" + g.val : g.type == 1 ? "+" + g.val : "-" + (int) g.remaining;
            txt(c, t, g.curX, g.y + 16, 46, 0xFFFFFFFF, true);
        }
        // الكرات
        for (int i = 0; i < n; i++) {
            p.setColor(cols[bcol[i]]);
            float rr = bw[i] > 1 ? R * (1f + 0.35f * (float) Math.log10(bw[i])) : R;
            c.drawCircle(bx[i], by[i], rr, p);
        }
        if (!finished) txt(c, "" + supply, funnelX, 215, 34, 0xFFFFFFFF, true);
        // الشريط العلوي
        p.setColor(0xFFC8A8E8); c.drawRect(-3000, -3000, 4000, 112, p);
        txt(c, "اجمع " + goal + " كرة", 500, 50, 46, 0xFFFFFFFF, true);
        p.setColor(0xFF6A3A96); c.drawRoundRect(new RectF(150, 64, 850, 94), 15, 15, p);
        float pr = Math.min(1f, (float) collected / goal);
        if (pr > 0) { p.setColor(0xFF2EE62E); c.drawRoundRect(new RectF(150, 64, 150 + 700 * pr, 94), 15, 15, p); }
        txt(c, collected + " / " + goal, 500, 88, 22, 0xFFFFFFFF, false);
        p.setColor(0xFF8E2FB0); c.drawCircle(60, 56, 38, p);
        txt(c, "II", 60, 70, 40, 0xFFFFFFFF, false);
        txt(c, "م " + level, 930, 60, 38, 0xFF4A1A70, false);
        if (!started)
            txt(c, "المس واسحب لتحريك القمع وابدأ", 500, 1000, 46, 0xFFFFFFFF, true);
        c.restore();
    }

    void drawResult(Canvas c) {
        p.setColor(0xAA000000); c.drawRect(0, 0, sw, sh, p);
        RectF pn = new RectF(sw * 0.09f, sh * 0.22f, sw * 0.91f, sh * 0.78f);
        p.setColor(0xFF5A1A70); c.drawRoundRect(pn, 40, 40, p);
        txt(c, resWin ? "أحسنت!" : "حاول مرة أخرى", sw / 2f, sh * 0.30f, sw * 0.11f, resWin ? 0xFF2EE62E : 0xFFFF6B6B, true);
        String st = ""; for (int k = 0; k < 3; k++) st += k < resStars ? "★" : "☆";
        txt(c, st, sw / 2f, sh * 0.39f, sw * 0.12f, 0xFFFFD700, false);
        txt(c, collected + " / " + goal, sw / 2f, sh * 0.47f, sw * 0.07f, 0xFFFFFFFF, true);
        for (Btn b : btns) drawBtn(c, b);
        txt(c, "برمجة وتطوير: محمد العمري - 730215825", sw / 2f, sh * 0.755f, sw * 0.04f, 0xFFFFFFFF, false);
    }

    // ================= الخيط =================
    @Override public void surfaceCreated(SurfaceHolder h) { running = true; thread = new Thread(this); thread.start(); }
    @Override public void surfaceChanged(SurfaceHolder h, int f, int w, int hh) { }
    @Override public void surfaceDestroyed(SurfaceHolder h) {
        running = false;
        try { if (thread != null) thread.join(); } catch (InterruptedException ignored) { }
    }

    @Override public void run() {
        long last = System.nanoTime();
        while (running) {
            long now = System.nanoTime(); float dt = Math.min(0.033f, (now - last) / 1e9f); last = now;
            Canvas c = null;
            try {
                c = holder.lockCanvas();
                if (c != null) synchronized (lock) { anim += dt; if (state == PLAY) update(dt); draw(c); }
            } catch (Exception ignored) {
            } finally {
                if (c != null) try { holder.unlockCanvasAndPost(c); } catch (Exception ignored) { }
            }
            long spent = (System.nanoTime() - now) / 1000000L;
            if (spent < 16) try { Thread.sleep(16 - spent); } catch (InterruptedException ignored) { }
        }
    }
}
