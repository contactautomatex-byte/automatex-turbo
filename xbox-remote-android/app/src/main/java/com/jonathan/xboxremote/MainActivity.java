package com.jonathan.xboxremote;

import android.Manifest;
import android.app.Activity;
import android.content.pm.PackageManager;
import android.content.res.ColorStateList;
import android.content.res.Configuration;
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
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import java.util.ArrayList;
import java.util.List;

public class MainActivity extends Activity implements SmartGlassClient.Listener {
    private SmartGlassClient client;
    private TextView status;
    private TextView statusDot;
    private EditText ipField;
    private Button connectButton;
    private final List<Button> remoteButtons = new ArrayList<>();
    private static final int REQ_NEARBY_WIFI = 2001;
    private String pendingIp = "";

    private static final int BG = Color.rgb(8, 12, 17);
    private static final int PANEL = Color.rgb(22, 28, 35);
    private static final int PANEL_2 = Color.rgb(31, 38, 47);
    private static final int BORDER = Color.rgb(48, 57, 69);
    private static final int GREEN = Color.rgb(16, 124, 16);
    private static final int TEXT = Color.rgb(246, 248, 250);
    private static final int MUTED = Color.rgb(157, 167, 179);
    private static final int RED = Color.rgb(218, 54, 51);
    private static final int BLUE = Color.rgb(38, 128, 217);
    private static final int YELLOW = Color.rgb(246, 196, 42);

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        getWindow().setStatusBarColor(BG);
        getWindow().setNavigationBarColor(BG);
        client = new SmartGlassClient(this);
        setContentView(buildUi());
        setRemoteEnabled(false);
    }

    private View buildUi() {
        boolean landscape = getResources().getConfiguration().orientation == Configuration.ORIENTATION_LANDSCAPE;
        int screenWidth = getResources().getDisplayMetrics().widthPixels;
        int side = landscape ? dp(18) : dp(16);
        int usable = screenWidth - (side * 2);
        int gap = dp(12);
        int cluster = Math.max(dp(118), Math.min(dp(190), (usable - gap - dp(24)) / 2));

        ScrollView scroll = new ScrollView(this);
        scroll.setFillViewport(true);
        scroll.setBackgroundColor(BG);
        scroll.setClipToPadding(false);

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        int topPad = dp(12) + (Build.VERSION.SDK_INT >= 35 ? statusBarHeight() : 0);
        root.setPadding(side, topPad, side, dp(24));
        scroll.addView(root, new ScrollView.LayoutParams(-1, -2));

        LinearLayout header = new LinearLayout(this);
        header.setOrientation(LinearLayout.HORIZONTAL);
        header.setGravity(Gravity.CENTER_VERTICAL);

        LinearLayout titles = new LinearLayout(this);
        titles.setOrientation(LinearLayout.VERTICAL);
        titles.addView(text("Xbox Controle", landscape ? 24 : 27, TEXT, true));
        titles.addView(text("Controle remoto local", 13, MUTED, false));
        header.addView(titles, new LinearLayout.LayoutParams(0, -2, 1f));

        TextView xboxBadge = text("XBOX", 12, Color.WHITE, true);
        xboxBadge.setGravity(Gravity.CENTER);
        xboxBadge.setBackground(roundRect(GREEN, 16));
        header.addView(xboxBadge, rawLp(dp(70), dp(36), dp(8), 0, 0, 0));
        root.addView(header, rawLp(-1, -2, 0, 0, 0, dp(14)));

        LinearLayout connectionCard = new LinearLayout(this);
        connectionCard.setOrientation(LinearLayout.VERTICAL);
        connectionCard.setPadding(dp(13), dp(11), dp(13), dp(11));
        connectionCard.setBackground(roundRectStroke(PANEL, BORDER, 17, 1));

        LinearLayout statusRow = new LinearLayout(this);
        statusRow.setOrientation(LinearLayout.HORIZONTAL);
        statusRow.setGravity(Gravity.CENTER_VERTICAL);

        statusDot = text("●", 14, MUTED, true);
        statusRow.addView(statusDot, rawLp(dp(20), -2, 0, 0, dp(5), 0));

        status = text("Desconectado", 15, TEXT, true);
        status.setMaxLines(2);
        statusRow.addView(status, new LinearLayout.LayoutParams(0, -2, 1f));

        connectButton = new Button(this);
        connectButton.setText("Conectar");
        connectButton.setTextColor(Color.WHITE);
        connectButton.setTextSize(12);
        connectButton.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        connectButton.setAllCaps(false);
        connectButton.setPadding(dp(8), 0, dp(8), 0);
        connectButton.setMinHeight(0);
        connectButton.setMinWidth(0);
        connectButton.setBackground(rippleRound(GREEN, 13));
        connectButton.setOnClickListener(v -> beginConnect());
        statusRow.addView(connectButton, rawLp(dp(104), dp(42), dp(8), 0, 0, 0));
        connectionCard.addView(statusRow);

        ipField = new EditText(this);
        ipField.setHint("IP do Xbox (opcional)");
        ipField.setHintTextColor(MUTED);
        ipField.setTextColor(TEXT);
        ipField.setTextSize(13);
        ipField.setSingleLine(true);
        ipField.setPadding(dp(12), 0, dp(12), 0);
        ipField.setBackground(roundRectStroke(PANEL_2, BORDER, 11, 1));
        connectionCard.addView(ipField, rawLp(-1, dp(43), 0, dp(9), 0, 0));
        root.addView(connectionCard, rawLp(-1, -2, 0, 0, 0, dp(14)));

        LinearLayout shell = new LinearLayout(this);
        shell.setOrientation(LinearLayout.VERTICAL);
        shell.setPadding(dp(12), dp(12), dp(12), dp(14));
        shell.setBackground(roundRectStroke(Color.rgb(13, 18, 24), BORDER, 24, 1));

        LinearLayout systemRow = new LinearLayout(this);
        systemRow.setGravity(Gravity.CENTER);
        Button view = compactRemote("VIEW", SmartGlassClient.BTN_VIEW);
        Button home = compactRemote("⌂", SmartGlassClient.BTN_NEXUS);
        home.setTextSize(23);
        home.setBackground(rippleRound(Color.rgb(54, 62, 73), 24));
        Button menu = compactRemote("MENU", SmartGlassClient.BTN_MENU);
        systemRow.addView(view, rawLp(dp(76), dp(42), 0, 0, dp(8), 0));
        systemRow.addView(home, rawLp(dp(54), dp(46), 0, 0, dp(8), 0));
        systemRow.addView(menu, rawLp(dp(76), dp(42), 0, 0, 0, 0));
        shell.addView(systemRow, rawLp(-1, -2, 0, 0, 0, dp(12)));

        LinearLayout mainControls = new LinearLayout(this);
        mainControls.setOrientation(LinearLayout.HORIZONTAL);
        mainControls.setGravity(Gravity.CENTER);
        mainControls.addView(buildDpad(), rawLp(cluster, cluster, 0, 0, gap, 0));
        mainControls.addView(buildFaceButtons(), rawLp(cluster, cluster, 0, 0, 0, 0));
        shell.addView(mainControls, new LinearLayout.LayoutParams(-1, -2));

        root.addView(shell, rawLp(-1, -2, 0, 0, 0, dp(10)));

        TextView hint = text("Direcional à esquerda • A/B/X/Y à direita", 12, MUTED, false);
        hint.setGravity(Gravity.CENTER);
        root.addView(hint, rawLp(-1, -2, 0, 0, 0, 0));
        return scroll;
    }

    private View buildDpad() {
        LinearLayout box = clusterBox();
        box.addView(controlRow(null, dpadButton("▲", SmartGlassClient.BTN_UP), null), weightedRow());
        box.addView(controlRow(dpadButton("◀", SmartGlassClient.BTN_LEFT), centerPad(), dpadButton("▶", SmartGlassClient.BTN_RIGHT)), weightedRow());
        box.addView(controlRow(null, dpadButton("▼", SmartGlassClient.BTN_DOWN), null), weightedRow());
        return box;
    }

    private View buildFaceButtons() {
        LinearLayout box = clusterBox();
        box.addView(controlRow(null, faceButton("Y", SmartGlassClient.BTN_Y, YELLOW, Color.BLACK), null), weightedRow());
        box.addView(controlRow(faceButton("X", SmartGlassClient.BTN_X, BLUE, Color.WHITE), null, faceButton("B", SmartGlassClient.BTN_B, RED, Color.WHITE)), weightedRow());
        box.addView(controlRow(null, faceButton("A", SmartGlassClient.BTN_A, GREEN, Color.WHITE), null), weightedRow());
        return box;
    }

    private LinearLayout clusterBox() {
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(dp(4), dp(4), dp(4), dp(4));
        box.setBackground(roundRect(PANEL, 22));
        return box;
    }

    private LinearLayout controlRow(View left, View center, View right) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER);
        addControlCell(row, left);
        addControlCell(row, center);
        addControlCell(row, right);
        return row;
    }

    private void addControlCell(LinearLayout row, View view) {
        View v = view == null ? new View(this) : view;
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(0, -1, 1f);
        p.setMargins(dp(3), dp(3), dp(3), dp(3));
        row.addView(v, p);
    }

    private LinearLayout.LayoutParams weightedRow() {
        return new LinearLayout.LayoutParams(-1, 0, 1f);
    }

    private View centerPad() {
        View v = new View(this);
        v.setBackground(roundRect(Color.rgb(48, 56, 66), 16));
        return v;
    }

    private Button dpadButton(String label, int mask) {
        Button b = remoteBase(label, mask);
        b.setTextSize(22);
        b.setBackground(rippleRound(Color.rgb(48, 56, 66), 16));
        return b;
    }

    private Button faceButton(String label, int mask, int color, int textColor) {
        Button b = remoteBase(label, mask);
        b.setTextColor(textColor);
        b.setTextSize(23);
        b.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        b.setBackground(rippleRound(color, 100));
        return b;
    }

    private Button compactRemote(String label, int mask) {
        Button b = remoteBase(label, mask);
        b.setTextSize(label.length() > 2 ? 11 : 19);
        b.setBackground(rippleRound(PANEL_2, 14));
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
            status.setText("Permita acesso a dispositivos Wi‑Fi próximos.");
            requestPermissions(new String[]{Manifest.permission.NEARBY_WIFI_DEVICES}, REQ_NEARBY_WIFI);
            return;
        }
        connectNow(pendingIp);
    }

    private void connectNow(String ip) {
        connectButton.setEnabled(false);
        setRemoteEnabled(false);
        statusDot.setTextColor(MUTED);
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
                status.setText("Permissão de Wi‑Fi necessária para controlar o Xbox.");
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
            statusDot.setTextColor(Color.rgb(62, 201, 84));
            status.setText(consoleName + "  •  " + ip);
            connectButton.setText("Reconectar");
            connectButton.setEnabled(true);
            setRemoteEnabled(true);
        });
    }

    @Override public void onConnectionFailed(String reason) {
        runOnUiThread(() -> {
            statusDot.setTextColor(RED);
            status.setText(reason);
            connectButton.setEnabled(true);
            connectButton.setText("Tentar");
            setRemoteEnabled(false);
        });
    }

    @Override public void onDisconnected(String reason) {
        runOnUiThread(() -> {
            statusDot.setTextColor(MUTED);
            status.setText(reason);
            connectButton.setEnabled(true);
            connectButton.setText("Conectar");
            setRemoteEnabled(false);
        });
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

    private LinearLayout.LayoutParams rawLp(int w, int h, int l, int t, int r, int b) {
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(w, h);
        p.setMargins(l, t, r, b);
        return p;
    }

    private int statusBarHeight() {
        int id = getResources().getIdentifier("status_bar_height", "dimen", "android");
        return id > 0 ? getResources().getDimensionPixelSize(id) : dp(24);
    }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }
}
