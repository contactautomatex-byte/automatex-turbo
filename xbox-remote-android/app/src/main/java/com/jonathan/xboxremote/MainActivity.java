package com.jonathan.xboxremote;

import android.Manifest;
import android.app.Activity;
import android.content.pm.PackageManager;
import android.content.res.ColorStateList;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.RippleDrawable;
import android.os.Build;
import android.os.Bundle;
import android.view.Gravity;
import android.view.HapticFeedbackConstants;
import android.view.View;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import java.util.ArrayList;
import java.util.List;

public class MainActivity extends Activity implements SmartGlassClient.Listener {
    private SmartGlassClient client;
    private TextView status;
    private EditText ipField;
    private Button connectButton;
    private final List<Button> remoteButtons = new ArrayList<>();
    private static final int REQ_NEARBY_WIFI = 2001;
    private String pendingIp = "";

    private static final int BG = Color.rgb(8, 12, 17);
    private static final int PANEL = Color.rgb(22, 28, 35);
    private static final int PANEL_2 = Color.rgb(31, 38, 47);
    private static final int BORDER = Color.rgb(49, 58, 70);
    private static final int GREEN = Color.rgb(16, 124, 16);
    private static final int TEXT = Color.rgb(246, 248, 250);
    private static final int MUTED = Color.rgb(157, 167, 179);
    private static final int RED = Color.rgb(218, 54, 51);
    private static final int BLUE = Color.rgb(35, 124, 213);
    private static final int YELLOW = Color.rgb(245, 195, 40);

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        getWindow().setStatusBarColor(BG);
        getWindow().setNavigationBarColor(BG);
        enableImmersive();
        client = new SmartGlassClient(this);
        setContentView(buildLandscapeGamepad());
        setRemoteEnabled(false);
    }

    private void enableImmersive() {
        getWindow().getDecorView().setSystemUiVisibility(
                View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY |
                View.SYSTEM_UI_FLAG_FULLSCREEN |
                View.SYSTEM_UI_FLAG_HIDE_NAVIGATION |
                View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN |
                View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION |
                View.SYSTEM_UI_FLAG_LAYOUT_STABLE
        );
    }

    private View buildLandscapeGamepad() {
        FrameLayout root = new FrameLayout(this);
        root.setBackgroundColor(BG);
        root.setPadding(dp(8), dp(6), dp(8), dp(6));

        int sw = getResources().getDisplayMetrics().widthPixels;
        int sh = getResources().getDisplayMetrics().heightPixels;
        int topBarHeight = dp(50);
        int availableHeight = Math.max(dp(180), sh - topBarHeight - dp(24));
        int face = clamp((availableHeight - dp(16)) / 3, dp(48), dp(72));
        int cluster = face * 3 + dp(16);
        int edge = clamp(sw / 55, dp(14), dp(34));
        int bottom = dp(8);

        LinearLayout topBar = new LinearLayout(this);
        topBar.setOrientation(LinearLayout.HORIZONTAL);
        topBar.setGravity(Gravity.CENTER_VERTICAL);
        topBar.setPadding(dp(8), dp(5), dp(8), dp(5));
        topBar.setBackground(roundRectStroke(PANEL, BORDER, 14, 1));

        TextView logo = text("XBOX", 11, Color.WHITE, true);
        logo.setGravity(Gravity.CENTER);
        logo.setBackground(roundRect(GREEN, 12));
        topBar.addView(logo, linearLp(dp(58), dp(32), 0, 0, dp(8), 0));

        status = text("Desconectado", 12, TEXT, true);
        status.setSingleLine(true);
        status.setGravity(Gravity.CENTER_VERTICAL);
        topBar.addView(status, new LinearLayout.LayoutParams(0, dp(34), 1f));

        Button view = smallRemote("VIEW", SmartGlassClient.BTN_VIEW);
        Button home = smallRemote("⌂", SmartGlassClient.BTN_NEXUS);
        home.setTextSize(20);
        Button menu = smallRemote("MENU", SmartGlassClient.BTN_MENU);
        topBar.addView(view, linearLp(dp(58), dp(34), dp(4), 0, dp(4), 0));
        topBar.addView(home, linearLp(dp(44), dp(36), 0, 0, dp(4), 0));
        topBar.addView(menu, linearLp(dp(58), dp(34), 0, 0, dp(6), 0));

        connectButton = new Button(this);
        connectButton.setText("Conectar");
        connectButton.setAllCaps(false);
        connectButton.setTextColor(Color.WHITE);
        connectButton.setTextSize(10);
        connectButton.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        connectButton.setPadding(dp(7), 0, dp(7), 0);
        connectButton.setMinWidth(0);
        connectButton.setMinHeight(0);
        connectButton.setBackground(rippleRound(GREEN, 11));
        connectButton.setOnClickListener(v -> beginConnect());
        topBar.addView(connectButton, linearLp(dp(84), dp(34), 0, 0, 0, 0));

        FrameLayout.LayoutParams topParams = new FrameLayout.LayoutParams(-1, topBarHeight, Gravity.TOP | Gravity.CENTER_HORIZONTAL);
        topParams.leftMargin = dp(8);
        topParams.rightMargin = dp(8);
        root.addView(topBar, topParams);

        // Mantido fora da área de jogo: a descoberta automática encontra o Xbox.
        // Quando uma conexão é feita, o IP é salvo neste campo invisível para reconectar rapidamente.
        ipField = new EditText(this);
        ipField.setSingleLine(true);
        ipField.setVisibility(View.GONE);

        View dpad = buildDpad(face);
        FrameLayout.LayoutParams dpadParams = new FrameLayout.LayoutParams(cluster, cluster, Gravity.LEFT | Gravity.BOTTOM);
        dpadParams.leftMargin = edge;
        dpadParams.bottomMargin = bottom;
        root.addView(dpad, dpadParams);

        View abxy = buildFaceCluster(face);
        FrameLayout.LayoutParams abxyParams = new FrameLayout.LayoutParams(cluster, cluster, Gravity.RIGHT | Gravity.BOTTOM);
        abxyParams.rightMargin = edge;
        abxyParams.bottomMargin = bottom;
        root.addView(abxy, abxyParams);

        return root;
    }

    private View buildDpad(int size) {
        FrameLayout box = clusterBox();
        int b = size;
        int total = b * 3 + dp(16);
        int center = (total - b) / 2;

        addAt(box, dpadButton("▲", SmartGlassClient.BTN_UP), center, dp(4), b, b);
        addAt(box, dpadButton("◀", SmartGlassClient.BTN_LEFT), dp(4), center, b, b);
        addAt(box, dpadButton("▶", SmartGlassClient.BTN_RIGHT), total - b - dp(4), center, b, b);
        addAt(box, dpadButton("▼", SmartGlassClient.BTN_DOWN), center, total - b - dp(4), b, b);

        View middle = new View(this);
        middle.setBackground(roundRect(Color.rgb(49, 57, 67), 16));
        addAt(box, middle, center, center, b, b);
        return box;
    }

    private View buildFaceCluster(int size) {
        FrameLayout box = clusterBox();
        int b = size;
        int total = b * 3 + dp(16);
        int center = (total - b) / 2;

        addAt(box, faceButton("Y", SmartGlassClient.BTN_Y, YELLOW, Color.BLACK), center, dp(4), b, b);
        addAt(box, faceButton("X", SmartGlassClient.BTN_X, BLUE, Color.WHITE), dp(4), center, b, b);
        addAt(box, faceButton("B", SmartGlassClient.BTN_B, RED, Color.WHITE), total - b - dp(4), center, b, b);
        addAt(box, faceButton("A", SmartGlassClient.BTN_A, GREEN, Color.WHITE), center, total - b - dp(4), b, b);
        return box;
    }

    private FrameLayout clusterBox() {
        FrameLayout box = new FrameLayout(this);
        box.setBackground(roundRectStroke(Color.rgb(13, 18, 24), BORDER, 26, 1));
        return box;
    }

    private void addAt(FrameLayout parent, View child, int x, int y, int w, int h) {
        FrameLayout.LayoutParams p = new FrameLayout.LayoutParams(w, h);
        p.leftMargin = x;
        p.topMargin = y;
        parent.addView(child, p);
    }

    private Button dpadButton(String label, int mask) {
        Button b = remoteBase(label, mask);
        b.setTextSize(23);
        b.setBackground(rippleRound(Color.rgb(47, 55, 65), 18));
        return b;
    }

    private Button faceButton(String label, int mask, int color, int textColor) {
        Button b = remoteBase(label, mask);
        b.setTextColor(textColor);
        b.setTextSize(24);
        b.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        b.setBackground(rippleRound(color, 100));
        return b;
    }

    private Button smallRemote(String label, int mask) {
        Button b = remoteBase(label, mask);
        b.setTextSize(label.length() > 2 ? 9 : 18);
        b.setBackground(rippleRound(PANEL_2, 11));
        return b;
    }

    private Button remoteBase(String label, int mask) {
        Button b = new Button(this);
        b.setText(label);
        b.setTextColor(TEXT);
        b.setAllCaps(false);
        b.setGravity(Gravity.CENTER);
        b.setPadding(0, 0, 0, 0);
        b.setMinWidth(0);
        b.setMinHeight(0);
        b.setOnClickListener(v -> {
            v.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP);
            if (!client.isConnected()) {
                Toast.makeText(this, "Conecte ao Xbox primeiro", Toast.LENGTH_SHORT).show();
                return;
            }
            client.sendButton(mask);
        });
        remoteButtons.add(b);
        return b;
    }

    private void beginConnect() {
        pendingIp = ipField.getText().toString().trim();
        if (Build.VERSION.SDK_INT >= 33 && checkSelfPermission(Manifest.permission.NEARBY_WIFI_DEVICES) != PackageManager.PERMISSION_GRANTED) {
            status.setTextColor(TEXT);
            status.setText("Permita acesso ao Wi‑Fi");
            requestPermissions(new String[]{Manifest.permission.NEARBY_WIFI_DEVICES}, REQ_NEARBY_WIFI);
            return;
        }
        connectNow(pendingIp);
    }

    private void connectNow(String ip) {
        connectButton.setEnabled(false);
        setRemoteEnabled(false);
        status.setTextColor(TEXT);
        status.setText("Procurando Xbox…");
        client.connect(ip);
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == REQ_NEARBY_WIFI) {
            if (grantResults.length > 0 && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                connectNow(pendingIp);
            } else {
                status.setTextColor(RED);
                status.setText("Permissão de Wi‑Fi necessária");
                connectButton.setEnabled(true);
            }
        }
    }

    private void setRemoteEnabled(boolean enabled) {
        for (Button b : remoteButtons) {
            b.setEnabled(enabled);
            b.setAlpha(enabled ? 1f : 0.42f);
        }
    }

    @Override public void onStatus(String message) {
        runOnUiThread(() -> status.setText(message));
    }

    @Override public void onConnected(String consoleName, String ip) {
        runOnUiThread(() -> {
            ipField.setText(ip);
            status.setText("● " + consoleName + "  •  " + ip);
            status.setTextColor(Color.rgb(74, 210, 96));
            connectButton.setText("Reconectar");
            connectButton.setEnabled(true);
            setRemoteEnabled(true);
        });
    }

    @Override public void onConnectionFailed(String reason) {
        runOnUiThread(() -> {
            status.setText(reason);
            status.setTextColor(RED);
            connectButton.setEnabled(true);
            connectButton.setText("Tentar");
            setRemoteEnabled(false);
        });
    }

    @Override public void onDisconnected(String reason) {
        runOnUiThread(() -> {
            status.setText(reason);
            status.setTextColor(MUTED);
            connectButton.setEnabled(true);
            connectButton.setText("Conectar");
            setRemoteEnabled(false);
        });
    }

    @Override public void onWindowFocusChanged(boolean hasFocus) {
        super.onWindowFocusChanged(hasFocus);
        if (hasFocus) enableImmersive();
    }

    @Override protected void onDestroy() {
        if (client != null) client.shutdown();
        super.onDestroy();
    }

    private TextView text(String value, int sp, int color, boolean bold) {
        TextView t = new TextView(this);
        t.setText(value);
        t.setTextSize(sp);
        t.setTextColor(color);
        if (bold) t.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        return t;
    }

    private GradientDrawable roundRect(int color, int radiusDp) {
        GradientDrawable g = new GradientDrawable();
        g.setColor(color);
        g.setCornerRadius(dp(radiusDp));
        return g;
    }

    private GradientDrawable roundRectStroke(int color, int strokeColor, int radiusDp, int strokeDp) {
        GradientDrawable g = roundRect(color, radiusDp);
        g.setStroke(dp(strokeDp), strokeColor);
        return g;
    }

    private RippleDrawable rippleRound(int color, int radiusDp) {
        GradientDrawable content = roundRect(color, radiusDp);
        GradientDrawable mask = roundRect(Color.WHITE, radiusDp);
        return new RippleDrawable(ColorStateList.valueOf(Color.argb(70, 255, 255, 255)), content, mask);
    }

    private LinearLayout.LayoutParams linearLp(int w, int h, int l, int t, int r, int b) {
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(w, h);
        p.setMargins(l, t, r, b);
        return p;
    }

    private int clamp(int value, int min, int max) {
        return Math.max(min, Math.min(max, value));
    }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }
}
