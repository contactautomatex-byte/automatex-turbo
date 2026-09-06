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
import android.widget.GridLayout;
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
    private static final int BLUE = Color.rgb(30, 136, 229);
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
        int screenHeight = getResources().getDisplayMetrics().heightPixels;
        int horizontalPad = landscape ? 18 : 16;
        int availableWidth = screenWidth - dp(horizontalPad * 2);

        ScrollView scroll = new ScrollView(this);
        scroll.setFillViewport(true);
        scroll.setBackgroundColor(BG);
        scroll.setClipToPadding(false);

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(dp(horizontalPad), dp(14), dp(horizontalPad), dp(22));
        scroll.addView(root, new ScrollView.LayoutParams(-1, -2));

        LinearLayout header = new LinearLayout(this);
        header.setOrientation(LinearLayout.HORIZONTAL);
        header.setGravity(Gravity.CENTER_VERTICAL);

        LinearLayout titleBlock = new LinearLayout(this);
        titleBlock.setOrientation(LinearLayout.VERTICAL);
        TextView title = text("Xbox Controle", landscape ? 24 : 28, TEXT, true);
        TextView subtitle = text("Controle local via Wi‑Fi", 13, MUTED, false);
        titleBlock.addView(title);
        titleBlock.addView(subtitle, lp(-2, -2, 0, 1, 0, 0));
        header.addView(titleBlock, new LinearLayout.LayoutParams(0, -2, 1f));

        TextView brand = text("XBOX", 12, TEXT, true);
        brand.setGravity(Gravity.CENTER);
        brand.setBackground(roundRect(GREEN, 14));
        header.addView(brand, lp(dp(64), dp(32), 8, 0, 0, 0));
        root.addView(header, lp(-1, -2, 0, 0, 0, 14));

        LinearLayout connectionCard = new LinearLayout(this);
        connectionCard.setOrientation(LinearLayout.VERTICAL);
        connectionCard.setPadding(dp(14), dp(12), dp(14), dp(12));
        connectionCard.setBackground(roundRectStroke(PANEL, BORDER, 16, 1));

        LinearLayout connectionTop = new LinearLayout(this);
        connectionTop.setOrientation(LinearLayout.HORIZONTAL);
        connectionTop.setGravity(Gravity.CENTER_VERTICAL);

        statusDot = text("●", 15, MUTED, true);
        connectionTop.addView(statusDot, lp(dp(20), -2, 0, 0, 6, 0));

        status = text("Desconectado", 15, TEXT, true);
        status.setMaxLines(2);
        connectionTop.addView(status, new LinearLayout.LayoutParams(0, -2, 1f));

        connectButton = new Button(this);
        connectButton.setText("Conectar");
        connectButton.setTextColor(Color.WHITE);
        connectButton.setTextSize(12);
        connectButton.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        connectButton.setAllCaps(false);
        connectButton.setPadding(dp(12), 0, dp(12), 0);
        connectButton.setMinHeight(0);
        connectButton.setMinWidth(0);
        connectButton.setBackground(rippleRound(GREEN, 12));
        connectButton.setOnClickListener(v -> beginConnect());
        connectionTop.addView(connectButton, lp(dp(104), dp(40), 10, 0, 0, 0));
        connectionCard.addView(connectionTop);

        ipField = new EditText(this);
        ipField.setHint("IP do Xbox (opcional)");
        ipField.setHintTextColor(MUTED);
        ipField.setTextColor(TEXT);
        ipField.setTextSize(13);
        ipField.setSingleLine(true);
        ipField.setPadding(dp(12), 0, dp(12), 0);
        ipField.setBackground(roundRectStroke(PANEL_2, BORDER, 11, 1));
        connectionCard.addView(ipField, lp(-1, dp(44), 0, 10, 0, 0));

        root.addView(connectionCard, lp(-1, -2, 0, 0, 0, landscape ? 12 : 16));

        LinearLayout controllerShell = new LinearLayout(this);
        controllerShell.setOrientation(LinearLayout.VERTICAL);
        controllerShell.setPadding(dp(12), dp(12), dp(12), dp(14));
        controllerShell.setBackground(roundRectStroke(Color.rgb(15, 20, 26), BORDER, 24, 1));

        LinearLayout centerStrip = new LinearLayout(this);
        centerStrip.setGravity(Gravity.CENTER);
        centerStrip.setOrientation(LinearLayout.HORIZONTAL);
        Button view = compactRemote("VIEW", SmartGlassClient.BTN_VIEW);
        Button home = compactRemote("⌂", SmartGlassClient.BTN_NEXUS);
        home.setTextSize(24);
        home.setBackground(rippleRound(Color.rgb(50, 57, 67), 24));
        Button menu = compactRemote("MENU", SmartGlassClient.BTN_MENU);
        centerStrip.addView(view, lp(dp(76), dp(42), 0, 0, 8, 0));
        centerStrip.addView(home, lp(dp(54), dp(46), 0, 0, 8, 0));
        centerStrip.addView(menu, lp(dp(76), dp(42), 0, 0, 0, 0));
        controllerShell.addView(centerStrip, lp(-1, -2, 0, 0, 0, 12));

        int cluster;
        if (landscape) {
            int maxByHeight = Math.max(dp(180), screenHeight - dp(170));
            cluster = Math.min(dp(230), Math.min((availableWidth - dp(70)) / 2, maxByHeight));
        } else {
            int gap = dp(12);
            cluster = Math.min(dp(190), (availableWidth - dp(24) - gap) / 2);
            if (cluster < dp(132)) cluster = dp(132);
        }

        LinearLayout controlRow = new LinearLayout(this);
        controlRow.setOrientation(LinearLayout.HORIZONTAL);
        controlRow.setGravity(Gravity.CENTER);
        controlRow.addView(buildDpad(cluster), lp(cluster, cluster, 0, 0, landscape ? 26 : 12, 0));
        controlRow.addView(buildFaceButtons(cluster), lp(cluster, cluster, 0, 0, 0, 0));
        controllerShell.addView(controlRow, new LinearLayout.LayoutParams(-1, -2));

        root.addView(controllerShell, lp(-1, -2, 0, 0, 0, 12));

        TextView footer = text("Botões grandes e layout responsivo para usar como controle remoto.", 12, MUTED, false);
        footer.setGravity(Gravity.CENTER);
        root.addView(footer, lp(-1, -2, 4, 0, 4, 0));
        return scroll;
    }

    private View buildDpad(int size) {
        GridLayout grid = new GridLayout(this);
        grid.setColumnCount(3);
        grid.setRowCount(3);
        grid.setPadding(dp(3), dp(3), dp(3), dp(3));
        grid.setBackground(roundRect(PANEL, 22));

        addCell(grid, null, 0, size);
        addCell(grid, dpadButton("▲", SmartGlassClient.BTN_UP), 1, size);
        addCell(grid, null, 2, size);
        addCell(grid, dpadButton("◀", SmartGlassClient.BTN_LEFT), 3, size);
        View center = new View(this);
        center.setBackground(roundRect(Color.rgb(46, 53, 62), 16));
        addCell(grid, center, 4, size);
        addCell(grid, dpadButton("▶", SmartGlassClient.BTN_RIGHT), 5, size);
        addCell(grid, null, 6, size);
        addCell(grid, dpadButton("▼", SmartGlassClient.BTN_DOWN), 7, size);
        addCell(grid, null, 8, size);
        return grid;
    }

    private View buildFaceButtons(int size) {
        GridLayout grid = new GridLayout(this);
        grid.setColumnCount(3);
        grid.setRowCount(3);
        grid.setPadding(dp(3), dp(3), dp(3), dp(3));
        grid.setBackground(roundRect(PANEL, 22));

        addCell(grid, null, 0, size);
        addCell(grid, faceButton("Y", SmartGlassClient.BTN_Y, YELLOW, Color.BLACK), 1, size);
        addCell(grid, null, 2, size);
        addCell(grid, faceButton("X", SmartGlassClient.BTN_X, BLUE, Color.WHITE), 3, size);
        addCell(grid, null, 4, size);
        addCell(grid, faceButton("B", SmartGlassClient.BTN_B, RED, Color.WHITE), 5, size);
        addCell(grid, null, 6, size);
        addCell(grid, faceButton("A", SmartGlassClient.BTN_A, GREEN, Color.WHITE), 7, size);
        addCell(grid, null, 8, size);
        return grid;
    }

    private Button dpadButton(String label, int mask) {
        Button b = remoteBase(label, mask);
        b.setTextSize(24);
        b.setBackground(rippleRound(Color.rgb(48, 55, 65), 16));
        return b;
    }

    private Button faceButton(String label, int mask, int color, int textColor) {
        Button b = remoteBase(label, mask);
        b.setTextColor(textColor);
        b.setTextSize(24);
        b.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        b.setBackground(rippleRound(color, 40));
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
            status.setText("Permita acesso a dispositivos Wi‑Fi próximos para controlar o Xbox.");
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
                status.setText("Sem a permissão de dispositivos Wi‑Fi próximos, o Android pode bloquear o envio de comandos ao Xbox.");
                connectButton.setEnabled(true);
            }
        }
    }

    private void addCell(GridLayout grid, View view, int index, int clusterSize) {
        View v = view == null ? new View(this) : view;
        int cell = Math.max(dp(42), clusterSize / 3);
        GridLayout.LayoutParams p = new GridLayout.LayoutParams();
        p.rowSpec = GridLayout.spec(index / 3, 1f);
        p.columnSpec = GridLayout.spec(index % 3, 1f);
        p.width = cell - dp(4);
        p.height = cell - dp(4);
        p.setMargins(dp(2), dp(2), dp(2), dp(2));
        grid.addView(v, p);
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

    private LinearLayout.LayoutParams lp(int w, int h, int l, int t, int r, int b) {
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(w, h);
        p.setMargins(dp(l), dp(t), dp(r), dp(b));
        return p;
    }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }
}
