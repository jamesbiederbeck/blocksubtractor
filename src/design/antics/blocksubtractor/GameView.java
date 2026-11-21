package design.antics.blocksubtractor;

import android.content.Context;
import android.content.SharedPreferences;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.RectF;
import android.view.MotionEvent;
import android.view.View;

import java.util.Random;

/**
 * The whole game: physics, input and drawing in one View. A fixed-timestep
 * loop driven by postOnAnimation keeps ball speed independent of refresh rate.
 */
public class GameView extends View {

    private static final int COLS = 8, ROWS = 6;
    private static final float STEP = 1f / 120f;

    private enum State { READY, PLAYING, PAUSED, MATH }

    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF rect = new RectF();
    private final Theme.Pops pops = new Theme.Pops();
    private boolean menuGesture;
    private final Theme.ThreeTap threeTap = new Theme.ThreeTap();
    private final Theme.Trail trail = new Theme.Trail();
    private final SharedPreferences prefs;

    private State state = State.READY;
    private float w, h, unit;                    // unit = 1% of width
    private float padX, padW, padH, padY;
    private float ballX, ballY, ballVX, ballVY, ballR;
    private final int[][] hp = new int[ROWS][COLS];   // hits left; 0 = gone
    private int bricksLeft, score, best, drops, level = 1;
    private final Random rng = new Random();
    private int answer, needed, solved;
    private String input = "", question = "", feedback = "";
    private final RectF[] keys = new RectF[12];   // 1-9, back, 0, OK
    private static final String[] KEY_LABELS = {"1","2","3","4","5","6","7","8","9","<","0","OK"};
    private float brickW, brickH, brickTop, brickGap;
    private float speed;
    private long lastNs;
    private float acc;
    private boolean running;
    private int packs;
    private final RectF menuBtn = new RectF();
    Runnable onMenu;

    public GameView(Context c) {
        super(c);
        Theme.init(paint);
        prefs = c.getSharedPreferences("game", Context.MODE_PRIVATE);
        best = prefs.getInt("best", 0);
        packs = prefs.getInt("packs2", Packs.DEFAULT_MASK);
        growth = new int[] {prefs.getInt("gmin", 1), prefs.getInt("gafter", 3), prefs.getInt("grate", 1), prefs.getInt("gmax", 20)};
        setKeepScreenOn(true);
    }

    @Override
    protected void onSizeChanged(int nw, int nh, int ow, int oh) {
        w = nw; h = nh; unit = w / 100f;
        padW = 18 * unit; padH = 2.4f * unit; padY = h - 14 * unit;
        ballR = 1.6f * unit;
        brickGap = 0.6f * unit;
        brickW = (w - brickGap * (COLS + 1)) / COLS;
        brickH = 3.6f * unit;
        brickTop = 16 * unit;
        menuBtn.set(0, 0, Theme.MENU_W * unit, Theme.MENU_H * unit);
        float kw = 22 * unit, kh = 12 * unit, gap = 2 * unit;
        float left = (w - 3 * kw - 2 * gap) / 2, top = h - 4 * kh - 3 * gap - 6 * unit;
        for (int i = 0; i < 12; i++)
            keys[i] = new RectF(left + (i % 3) * (kw + gap), top + (i / 3) * (kh + gap),
                                left + (i % 3) * (kw + gap) + kw, top + (i / 3) * (kh + gap) + kh);
        newGame();
    }

    private void newGame() { score = 0; drops = 0; level = 1; startLevel(); }

    /** Problems owed after the Nth lost ball: 1,1,1,2,3,4,... */
    private int[] growth = {1, 3, 1, 20};   // min, grow after ball, extra per ball, max
    private int problemsFor(int drop) { return ParentsMenu.problems(growth, drop); }

    private void nextProblem() {
        Object[] p = Packs.generate(packs, rng);
        question = (String) p[0];
        answer = (Integer) p[1];
        input = "";
    }

    private void startMath() {
        drops++;
        needed = problemsFor(drops);
        solved = 0;
        feedback = "";
        state = State.MATH;
        nextProblem();
    }

    private void pressKey(int k) {
        if (k == 9) { if (!input.isEmpty()) input = input.substring(0, input.length() - 1); }
        else if (k == 11) {
            if (input.isEmpty()) return;
            if (Integer.parseInt(input) == answer) {
                if (++solved >= needed) { resetBall(); return; }
                feedback = "Correct!";
            } else feedback = "Not quite - try this one";
            nextProblem();
        } else if (input.length() < 5) input += KEY_LABELS[k];
    }

    private void startLevel() {
        for (int r = 0; r < ROWS; r++)
            for (int c = 0; c < COLS; c++)
                hp[r][c] = randomHp();
        bricksLeft = ROWS * COLS;
        speed = (62 + 7 * (level - 1)) * unit;   // px/s
        padX = w / 2;
        resetBall();
    }

    /** Mostly 1-hit bricks; numbered (2+) bricks get likelier and tougher with level. */
    private int randomHp() {
        int maxHp = Math.min(5, 1 + level);
        float chance = Math.min(0.6f, 0.15f + 0.1f * level);
        return rng.nextFloat() < chance ? 2 + rng.nextInt(maxHp - 1) : 1;
    }

    private void resetBall() {
        state = State.READY;
        trail.clear();
        ballX = padX; ballY = padY - ballR - 1;
        ballVX = 0; ballVY = 0;
    }

    private void launch() {
        double a = Math.toRadians(-70);  // slightly right of vertical
        ballVX = (float) (speed * Math.cos(a));
        ballVY = (float) (speed * Math.sin(a));
        state = State.PLAYING;
    }

    // ---- loop ----------------------------------------------------------

    void resume() {
        if (running) return;
        running = true;
        lastNs = 0;
        postOnAnimation(tick);
    }

    void pause() {
        running = false;
        if (state == State.PLAYING) state = State.PAUSED;
        save();
    }

    private void save() {
        if (score > best) { best = score; prefs.edit().putInt("best", best).apply(); }
    }

    private final Runnable tick = new Runnable() {
        @Override public void run() {
            if (!running) return;
            long now = System.nanoTime();
            if (lastNs != 0) {
                acc += Math.min((now - lastNs) / 1e9f, 0.05f);
                while (acc >= STEP) { if (state == State.PLAYING) step(STEP); pops.update(STEP); acc -= STEP; }
            }
            lastNs = now;
            invalidate();
            postOnAnimation(this);
        }
    };

    private void step(float dt) {
        trail.add(ballX, ballY);
        ballX += ballVX * dt;
        ballY += ballVY * dt;

        if (ballX < ballR)     { ballX = ballR;     ballVX = Math.abs(ballVX); }
        if (ballX > w - ballR) { ballX = w - ballR; ballVX = -Math.abs(ballVX); }
        if (ballY < ballR)     { ballY = ballR;     ballVY = Math.abs(ballVY); }

        // paddle
        if (ballVY > 0 && ballY + ballR >= padY && ballY - ballR <= padY + padH
                && ballX >= padX - padW / 2 - ballR && ballX <= padX + padW / 2 + ballR) {
            float off = Math.max(-1f, Math.min(1f, (ballX - padX) / (padW / 2)));
            double a = Math.toRadians(-90 + off * 60);
            ballVX = (float) (speed * Math.cos(a));
            ballVY = (float) (speed * Math.sin(a));
            ballY = padY - ballR;
        }

        // bricks
        for (int r = 0; r < ROWS; r++) {
            for (int c = 0; c < COLS; c++) {
                if (hp[r][c] == 0) continue;
                float bx = brickGap + c * (brickW + brickGap);
                float by = brickTop + r * (brickH + brickGap);
                float nx = Math.max(bx, Math.min(ballX, bx + brickW));
                float ny = Math.max(by, Math.min(ballY, by + brickH));
                float dx = ballX - nx, dy = ballY - ny;
                if (dx * dx + dy * dy > ballR * ballR) continue;
                if (--hp[r][c] == 0) {
                    bricksLeft--; score += 10 * (ROWS - r) * level;
                    pops.burst(bx + brickW / 2, by + brickH / 2, Theme.TILES[(r + c) % 3], 40 * unit, 9);
                }
                else score += 2 * level;
                // reflect along the axis of least overlap and push the ball out, so
                // a brick that survives the hit can't be hit again next step
                float ol = ballX + ballR - bx, or = bx + brickW - (ballX - ballR);
                float ot = ballY + ballR - by, ob = by + brickH - (ballY - ballR);
                if (Math.min(ol, or) < Math.min(ot, ob)) {
                    ballX += ol < or ? -ol : or;
                    ballVX = -ballVX;
                } else {
                    ballY += ot < ob ? -ot : ob;
                    ballVY = -ballVY;
                }
                if (bricksLeft == 0) { level++; save(); startLevelKeepScore(); return; }
                return;   // one brick per step is plenty at 120 Hz
            }
        }

        if (ballY - ballR > h) { save(); startMath(); }
    }

    private void startLevelKeepScore() { int s = score; startLevel(); score = s; }

    // ---- parents menu hooks ------------------------------------------

    int[] growth() { return growth.clone(); }
    void setGrowth(int[] g) {
        growth = g.clone();
        prefs.edit().putInt("gmin", g[0]).putInt("gafter", g[1]).putInt("grate", g[2]).putInt("gmax", g[3]).apply();
    }
    int packs() { return packs; }
    void setPacks(int m) { packs = m; prefs.edit().putInt("packs2", m).apply(); }
    void resetBest() { best = 0; prefs.edit().putInt("best", 0).apply(); }
    void menuOpened() { if (state == State.PLAYING) state = State.PAUSED; }

    // ---- input ---------------------------------------------------------

    @Override
    public boolean onTouchEvent(MotionEvent e) {
        int a = e.getActionMasked();
        if (threeTap.swallow(e, () -> { if (state == State.PLAYING) state = State.PAUSED; })) return true;   // double three-finger tap pauses
        if (menuGesture) {                       // the tap that opened the menu must not also act on the game
            if (a == MotionEvent.ACTION_UP || a == MotionEvent.ACTION_CANCEL) menuGesture = false;
            return true;
        }
        if (a == MotionEvent.ACTION_DOWN && menuBtn.contains(e.getX(), e.getY())) {
            menuGesture = true;
            if (onMenu != null) onMenu.run();
            return true;
        }
        if (state == State.MATH) {
            if (a == MotionEvent.ACTION_UP)
                for (int i = 0; i < 12; i++)
                    if (keys[i].contains(e.getX(), e.getY())) { pressKey(i); break; }
            return true;
        }
        if (state == State.PLAYING || state == State.READY) {
            padX = Math.max(padW / 2, Math.min(w - padW / 2, e.getX()));
            if (state == State.READY) ballX = padX;
        }
        if (a == MotionEvent.ACTION_UP) {
            switch (state) {
                case READY:  launch(); break;
                case PAUSED: state = State.PLAYING; break;
                default: break;
            }
        } else if (a == MotionEvent.ACTION_DOWN && state == State.PLAYING && e.getY() < brickTop - 2 * unit && e.getX() > w - 14 * unit) {
            state = State.PAUSED;
        }
        return true;
    }

    // ---- drawing -------------------------------------------------------

    @Override
    protected void onDraw(Canvas cv) {
        cv.drawColor(Theme.NAVY);

        for (int r = 0; r < ROWS; r++) {
            for (int c = 0; c < COLS; c++) {
                if (hp[r][c] == 0) continue;
                float bx = brickGap + c * (brickW + brickGap);
                float by = brickTop + r * (brickH + brickGap);
                rect.set(bx, by, bx + brickW, by + brickH);
                Theme.tile(cv, paint, rect, Theme.TILES[(r + c) % 3]);
                if (hp[r][c] > 1)
                    Theme.text(cv, paint, String.valueOf(hp[r][c]), rect.centerX(), rect.centerY(), brickH * 0.72f, Theme.NAVY);
            }
        }

        rect.set(padX - padW / 2, padY, padX + padW / 2, padY + padH);
        Theme.tile(cv, paint, rect, Theme.MINT);
        trail.draw(cv, paint, ballR, Theme.CREAM);
        paint.setStyle(Paint.Style.FILL);
        paint.setColor(Theme.CREAM);
        cv.drawCircle(ballX, ballY, ballR, paint);
        pops.draw(cv, paint, unit * 1.1f);

        Theme.hud(cv, paint, w, unit, "Score " + score, "Lv " + level + "  Drops " + drops, "Best " + Math.max(best, score));

        if (state == State.MATH) { drawMath(cv); return; }
        String msg = null;
        if (state == State.READY)  msg = "Drag to move \u2022 release to launch";
        if (state == State.PAUSED) msg = "Paused \u2022 tap to resume";
        if (msg != null) Theme.text(cv, paint, msg, w / 2, h * 0.62f, 4.2f * unit, Theme.WHITE);
    }

    private void drawMath(Canvas cv) {
        cv.drawColor(Theme.alpha(Theme.NAVY, 0.94f));
        Theme.hud(cv, paint, w, unit, "Score " + score, "Lv " + level + "  Drops " + drops, "Best " + Math.max(best, score));
        Theme.text(cv, paint, "Ball lost! Problem " + (solved + 1) + " of " + needed, w / 2, h * 0.17f, 4.2f * unit, Theme.CREAM);
        Theme.textLines(cv, paint, question, w / 2, h * 0.28f, 8.5f * unit, w * 0.9f, h * 0.13f, Theme.WHITE);
        Theme.text(cv, paint, input.isEmpty() ? "_" : input, w / 2, h * 0.38f, 9 * unit, Theme.MINT);
        Theme.text(cv, paint, feedback, w / 2, h * 0.44f, 3.6f * unit, Theme.SUN);
        for (int i = 0; i < 12; i++) {
            int color = i == 11 ? Theme.MINT : i == 9 ? Theme.CORAL : Theme.SURFACE;
            Theme.tile(cv, paint, keys[i], color);
            Theme.text(cv, paint, KEY_LABELS[i], keys[i].centerX(), keys[i].centerY(), 6 * unit,
                       i == 11 || i == 9 ? Theme.NAVY : Theme.CREAM);
        }
    }
}
