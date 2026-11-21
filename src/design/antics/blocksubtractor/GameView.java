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
    private static final int[] ROW_COLORS = {
        0xFFE53935, 0xFFFB8C00, 0xFFFDD835, 0xFF43A047, 0xFF1E88E5, 0xFF8E24AA };

    private enum State { READY, PLAYING, PAUSED, MATH }

    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF rect = new RectF();
    private final SharedPreferences prefs;

    private State state = State.READY;
    private float w, h, unit;                    // unit = 1% of width
    private float padX, padW, padH, padY;
    private float ballX, ballY, ballVX, ballVY, ballR;
    private final boolean[][] alive = new boolean[ROWS][COLS];
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
        prefs = c.getSharedPreferences("game", Context.MODE_PRIVATE);
        best = prefs.getInt("best", 0);
        packs = prefs.getInt("packs2", Packs.DEFAULT_MASK);
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
        menuBtn.set(0, 0, 14 * unit, 12 * unit);
        float kw = 22 * unit, kh = 12 * unit, gap = 2 * unit;
        float left = (w - 3 * kw - 2 * gap) / 2, top = h - 4 * kh - 3 * gap - 6 * unit;
        for (int i = 0; i < 12; i++)
            keys[i] = new RectF(left + (i % 3) * (kw + gap), top + (i / 3) * (kh + gap),
                                left + (i % 3) * (kw + gap) + kw, top + (i / 3) * (kh + gap) + kh);
        newGame();
    }

    private void newGame() { score = 0; drops = 0; level = 1; startLevel(); }

    /** Problems owed after the Nth lost ball: 1,1,1,2,3,4,... */
    private static int problemsFor(int drop) { return drop <= 3 ? 1 : drop - 2; }

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
                alive[r][c] = true;
        bricksLeft = ROWS * COLS;
        speed = (62 + 7 * (level - 1)) * unit;   // px/s
        padX = w / 2;
        resetBall();
    }

    private void resetBall() {
        state = State.READY;
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
                while (acc >= STEP) { if (state == State.PLAYING) step(STEP); acc -= STEP; }
            }
            lastNs = now;
            invalidate();
            postOnAnimation(this);
        }
    };

    private void step(float dt) {
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
                if (!alive[r][c]) continue;
                float bx = brickGap + c * (brickW + brickGap);
                float by = brickTop + r * (brickH + brickGap);
                float nx = Math.max(bx, Math.min(ballX, bx + brickW));
                float ny = Math.max(by, Math.min(ballY, by + brickH));
                float dx = ballX - nx, dy = ballY - ny;
                if (dx * dx + dy * dy > ballR * ballR) continue;
                alive[r][c] = false;
                bricksLeft--;
                score += 10 * (ROWS - r) * level;
                // reflect along the axis of least overlap
                float ox = Math.min(ballX + ballR - bx, bx + brickW - (ballX - ballR));
                float oy = Math.min(ballY + ballR - by, by + brickH - (ballY - ballR));
                if (ox < oy) ballVX = -ballVX; else ballVY = -ballVY;
                if (bricksLeft == 0) { level++; save(); startLevelKeepScore(); return; }
                return;   // one brick per step is plenty at 120 Hz
            }
        }

        if (ballY - ballR > h) { save(); startMath(); }
    }

    private void startLevelKeepScore() { int s = score; startLevel(); score = s; }

    // ---- parents menu hooks ------------------------------------------

    int packs() { return packs; }
    void setPacks(int m) { packs = m; prefs.edit().putInt("packs2", m).apply(); }
    void resetBest() { best = 0; prefs.edit().putInt("best", 0).apply(); }
    void menuOpened() { if (state == State.PLAYING) state = State.PAUSED; }

    // ---- input ---------------------------------------------------------

    @Override
    public boolean onTouchEvent(MotionEvent e) {
        int a = e.getActionMasked();
        if (a == MotionEvent.ACTION_DOWN && menuBtn.contains(e.getX(), e.getY())) {
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
        cv.drawColor(0xFF10121A);
        paint.setStyle(Paint.Style.FILL);

        for (int r = 0; r < ROWS; r++) {
            paint.setColor(ROW_COLORS[r % ROW_COLORS.length]);
            for (int c = 0; c < COLS; c++) {
                if (!alive[r][c]) continue;
                float bx = brickGap + c * (brickW + brickGap);
                float by = brickTop + r * (brickH + brickGap);
                rect.set(bx, by, bx + brickW, by + brickH);
                cv.drawRoundRect(rect, unit * 0.6f, unit * 0.6f, paint);
            }
        }

        paint.setColor(Color.WHITE);
        rect.set(padX - padW / 2, padY, padX + padW / 2, padY + padH);
        cv.drawRoundRect(rect, padH / 2, padH / 2, paint);
        cv.drawCircle(ballX, ballY, ballR, paint);

        paint.setTextSize(4 * unit);
        paint.setTextAlign(Paint.Align.LEFT);
        cv.drawText("Score " + score, 14 * unit, 8 * unit, paint);
        for (int i = 0; i < 3; i++)
            cv.drawRect(3 * unit, 3.4f * unit + i * 2 * unit, 9 * unit, 4.4f * unit + i * 2 * unit, paint);
        paint.setTextAlign(Paint.Align.CENTER);
        cv.drawText("Lv " + level + "  Drops " + drops, w / 2, 8 * unit, paint);
        paint.setTextAlign(Paint.Align.RIGHT);
        cv.drawText("Best " + Math.max(best, score), w - 3 * unit, 8 * unit, paint);

        paint.setTextAlign(Paint.Align.CENTER);
        if (state == State.MATH) { drawMath(cv); return; }
        String msg = null;
        if (state == State.READY)  msg = "Drag to move • release to launch";
        if (state == State.PAUSED) msg = "Paused • tap to resume";
        if (msg != null) {
            paint.setTextSize(4.2f * unit);
            cv.drawText(msg, w / 2, h * 0.62f, paint);
        }
    }

    private void drawMath(Canvas cv) {
        paint.setColor(0xDD10121A);
        cv.drawRect(0, 0, w, h, paint);
        paint.setColor(Color.WHITE);
        paint.setTextSize(4.2f * unit);
        cv.drawText("Ball lost! Problem " + (solved + 1) + " of " + needed, w / 2, h * 0.18f, paint);
        paint.setTextSize(8.5f * unit);
        cv.drawText(question, w / 2, h * 0.30f, paint);
        paint.setColor(0xFF90CAF9);
        paint.setTextSize(9 * unit);
        cv.drawText(input.isEmpty() ? "_" : input, w / 2, h * 0.40f, paint);
        paint.setColor(0xFFFFB74D);
        paint.setTextSize(3.6f * unit);
        cv.drawText(feedback, w / 2, h * 0.45f, paint);
        for (int i = 0; i < 12; i++) {
            paint.setColor(i == 11 ? 0xFF43A047 : 0xFF2A2F42);
            cv.drawRoundRect(keys[i], unit, unit, paint);
            paint.setColor(Color.WHITE);
            paint.setTextSize(6 * unit);
            cv.drawText(KEY_LABELS[i], keys[i].centerX(), keys[i].centerY() + 2.2f * unit, paint);
        }
    }
}
