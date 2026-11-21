package design.antics.blocksubtractor;

import android.app.Dialog;
import android.content.Context;
import android.content.SharedPreferences;
import android.graphics.Color;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

/**
 * PIN-gated parents menu built from plain framework views. The PIN is a speed
 * bump against a child's tap, not security (stored in plain SharedPreferences).
 */
final class ParentsMenu {
    static final String DEFAULT_PIN = "1234";
    private static final int PIN_LEN = 4;

    interface Host {
        int packs();
        void setPacks(int mask);
        void resetBest();
        void onMenuClosed();
    }

    /** Called with the entered digits; return an error message or null to accept. */
    private interface PinListener { String onPin(String pin); }

    static void open(Context c, Host host) {
        SharedPreferences prefs = c.getSharedPreferences("game", Context.MODE_PRIVATE);
        String pin = prefs.getString("pin", DEFAULT_PIN);
        pad(c, "Parents: enter PIN", host, entered -> {
            if (!entered.equals(pin)) return "Wrong PIN";
            settings(c, host, prefs);
            return null;
        });
    }

    private static Dialog dialog(Context c, Host host) {
        Dialog d = new Dialog(c, android.R.style.Theme_DeviceDefault_Dialog);
        d.setCancelable(true);
        d.setOnDismissListener(x -> host.onMenuClosed());
        return d;
    }

    private static int dp(Context c, int v) { return Math.round(v * c.getResources().getDisplayMetrics().density); }

    private static void pad(Context c, String title, Host host, PinListener l) {
        Dialog d = dialog(c, host);
        d.setTitle(title);
        StringBuilder entered = new StringBuilder();
        LinearLayout col = new LinearLayout(c);
        col.setOrientation(LinearLayout.VERTICAL);
        col.setGravity(Gravity.CENTER_HORIZONTAL);
        col.setPadding(dp(c, 16), dp(c, 8), dp(c, 16), dp(c, 16));
        TextView dots = new TextView(c);
        dots.setTextSize(28);
        dots.setGravity(Gravity.CENTER);
        TextView msg = new TextView(c);
        msg.setTextColor(0xFFFFB74D);
        msg.setGravity(Gravity.CENTER);
        col.addView(dots);
        col.addView(msg);
        Runnable refresh = () -> {
            StringBuilder s = new StringBuilder();
            for (int i = 0; i < PIN_LEN; i++) s.append(i < entered.length() ? "● " : "○ ");
            dots.setText(s.toString().trim());
        };
        refresh.run();
        String[] labels = {"1","2","3","4","5","6","7","8","9","<","0","X"};
        for (int r = 0; r < 4; r++) {
            LinearLayout row = new LinearLayout(c);
            for (int k = 0; k < 3; k++) {
                String t = labels[r * 3 + k];
                Button b = new Button(c);
                b.setText(t);
                b.setTextSize(22);
                row.addView(b, new LinearLayout.LayoutParams(dp(c, 76), dp(c, 64)));
                b.setOnClickListener(v -> {
                    msg.setText("");
                    if (t.equals("X")) { d.dismiss(); return; }
                    if (t.equals("<")) { if (entered.length() > 0) entered.setLength(entered.length() - 1); }
                    else if (entered.length() < PIN_LEN) entered.append(t);
                    refresh.run();
                    if (entered.length() == PIN_LEN) {
                        String err = l.onPin(entered.toString());
                        if (err == null) { d.setOnDismissListener(null); d.dismiss(); }
                        else { entered.setLength(0); refresh.run(); msg.setText(err); }
                    }
                });
            }
            col.addView(row);
        }
        d.setContentView(col);
        d.show();
    }

    private static void settings(Context c, Host host, SharedPreferences prefs) {
        Dialog d = dialog(c, host);
        d.setTitle("Parents menu");
        LinearLayout col = new LinearLayout(c);
        col.setOrientation(LinearLayout.VERTICAL);
        col.setPadding(dp(c, 20), dp(c, 8), dp(c, 20), dp(c, 16));

        TextView h = new TextView(c);
        h.setText("Problem packs (at least one)");
        h.setTextSize(16);
        col.addView(h);
        CheckBox[] boxes = new CheckBox[Packs.COUNT];
        for (int i = 0; i < boxes.length; i++) {
            CheckBox cb = new CheckBox(c);
            cb.setText(Packs.NAMES[i] + "  (" + Packs.EXAMPLES[i] + ")");
            cb.setChecked((host.packs() & (1 << i)) != 0);
            boxes[i] = cb;
            final int bit = 1 << i;
            cb.setOnCheckedChangeListener((v, on) -> {
                int m = on ? host.packs() | bit : host.packs() & ~bit;
                if (m == 0) { v.setChecked(true); return; }   // keep one on
                host.setPacks(m);
            });
            col.addView(cb);
        }

        Button reset = new Button(c);
        reset.setText("Reset best score");
        reset.setOnClickListener(v -> { host.resetBest(); reset.setText("Best score reset"); });
        col.addView(reset);

        Button chpin = new Button(c);
        chpin.setText("Change PIN");
        chpin.setOnClickListener(v -> {
            d.setOnDismissListener(null);
            d.dismiss();
            pad(c, "New PIN", host, first -> {
                pad(c, "Repeat new PIN", host, second -> {
                    if (!second.equals(first)) return "PINs did not match";
                    prefs.edit().putString("pin", second).apply();
                    return null;
                });
                return null;
            });
        });
        col.addView(chpin);

        Button done = new Button(c);
        done.setText("Done");
        done.setOnClickListener(v -> d.dismiss());
        col.addView(done);

        ScrollView sv = new ScrollView(c);
        sv.addView(col);
        d.setContentView(sv, new ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        d.show();
    }

    private ParentsMenu() {}
}
