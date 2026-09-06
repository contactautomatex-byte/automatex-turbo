package com.jonathan.xboxremote;

import android.Manifest;
import android.app.Activity;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
import android.os.Build;
import android.os.Bundle;
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
    private EditText ipField;
    private Button connectButton;
    private final List<Button> remoteButtons = new ArrayList<>();
    private static final int REQ_NEARBY_WIFI = 2001;
    private String pendingIp = "";

    private static final int BG = Color.rgb(11, 15, 20);
    private static final int PANEL = Color.rgb(27, 33, 41);
    private static final int GREEN = Color.rgb(16, 124, 16);
    private static final int TEXT = Color.rgb(245, 247, 250);
    private static final int MUTED = Color.rgb(174, 183, 194);

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        client = new SmartGlassClient(this);
        setContentView(buildUi());
        setRemoteEnabled(false);
    }

    private View buildUi() {
        ScrollView scroll = new ScrollView(this);
        scroll.setFillViewport(true);
        scroll.setBackgroundColor(BG);

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(dp(18), dp(20), dp(18), dp(28));
        scroll.addView(root, new ScrollView.LayoutParams(-1, -2));

        TextView title = text("Xbox Controle", 28, TEXT, true);
        root.addView(title);
        TextView subtitle = text("Controle local para Xbox One / Series via Wi‑Fi (SmartGlass)", 14, MUTED, false);
        root.addView(subtitle, lp(-1, -2, 0, 4, 0, 18));

        status = text("Desconectado", 16, TEXT, true);
        status.setPadding(dp(14), dp(12), dp(14), dp(12));
        status.setBackground(roundRect(PANEL, 14));
        root.addView(status, lp(-1, -2, 0, 0, 0, 14));

        ipField = new EditText(this);
        ipField.setHint("IP do Xbox (opcional, ex.: 192.168.1.14)");
        ipField.setHintTextColor(MUTED);
        ipField.setTextColor(TEXT);
        ipField.setSingleLine(true);
        ipField.setPadding(dp(14), dp(12), dp(14), dp(12));
        ipField.setBackground(roundRect(PANEL, 12));
        root.addView(ipField, lp(-1, dp(52), 0, 0, 0, 10));

        connectButton = new Button(this);
        connectButton.setText("LOCALIZAR E CONECTAR");
        connectButton.setTextColor(Color.WHITE);
        connectButton.setTextSize(15);
        connectButton.setBackground(roundRect(GREEN, 12));
        connectButton.setOnClickListener(v -> beginConnect());
        root.addView(connectButton, lp(-1, dp(54), 0, 0, 0, 18));

        TextView hint = text("O celular e o Xbox precisam estar na mesma rede. O campo de IP pode ficar vazio: o app tenta localizar o console automaticamente.", 13, MUTED, false);
        root.addView(hint, lp(-1, -2, 0, 0, 0, 20));

        TextView nav = text("NAVEGAÇÃO", 13, MUTED, true);
        root.addView(nav, lp(-1, -2, 0, 0, 0, 8));

        GridLayout dpad = new GridLayout(this);
        dpad.setColumnCount(3);
        dpad.setRowCount(3);
        addCell(dpad, null, 0);
        addCell(dpad, remote("▲", SmartGlassClient.BTN_UP), 1);
        addCell(dpad, null, 2);
        addCell(dpad, remote("◀", SmartGlassClient.BTN_LEFT), 3);
        Button aCenter = remote("A", SmartGlassClient.BTN_A);
        aCenter.setBackground(roundRect(GREEN, 18));
        addCell(dpad, aCenter, 4);
        addCell(dpad, remote("▶", SmartGlassClient.BTN_RIGHT), 5);
        addCell(dpad, remote("B", SmartGlassClient.BTN_B), 6);
        addCell(dpad, remote("▼", SmartGlassClient.BTN_DOWN), 7);
        addCell(dpad, remote("⌂", SmartGlassClient.BTN_NEXUS), 8);
        root.addView(dpad, lp(-1, dp(246), 0, 0, 0, 18));

        GridLayout actions = new GridLayout(this);
        actions.setColumnCount(4);
        addAction(actions, remote("X", SmartGlassClient.BTN_X));
        addAction(actions, remote("Y", SmartGlassClient.BTN_Y));
        addAction(actions, remote("VIEW", SmartGlassClient.BTN_VIEW));
        addAction(actions, remote("MENU", SmartGlassClient.BTN_MENU));
        root.addView(actions, lp(-1, dp(68), 0, 0, 0, 18));

        TextView note = text("Se aparecer ‘conexões anônimas desativadas’, o próprio Xbox está bloqueando controles locais sem login. O app mostrará o motivo exato retornado pelo console.", 12, MUTED, false);
        root.addView(note);
        return scroll;
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

    private Button remote(String label, int mask) {
        Button b = new Button(this);
        b.setText(label);
        b.setTextSize(label.length() > 2 ? 12 : 21);
        b.setTextColor(TEXT);
        b.setBackground(roundRect(PANEL, 18));
        b.setOnClickListener(v -> {
            if (!client.isConnected()) {
                Toast.makeText(this, "Conecte ao Xbox primeiro", Toast.LENGTH_SHORT).show();
                return;
            }
            client.sendButton(mask);
        });
        remoteButtons.add(b);
        return b;
    }

    private void addCell(GridLayout grid, Button button, int index) {
        View v = button;
        if (v == null) v = new View(this);
        GridLayout.LayoutParams p = new GridLayout.LayoutParams();
        p.rowSpec = GridLayout.spec(index / 3, 1f);
        p.columnSpec = GridLayout.spec(index % 3, 1f);
        p.width = 0;
        p.height = 0;
        p.setMargins(dp(5), dp(5), dp(5), dp(5));
        grid.addView(v, p);
    }

    private void addAction(GridLayout grid, Button b) {
        GridLayout.LayoutParams p = new GridLayout.LayoutParams();
        p.columnSpec = GridLayout.spec(GridLayout.UNDEFINED, 1f);
        p.width = 0;
        p.height = -1;
        p.setMargins(dp(4), dp(4), dp(4), dp(4));
        grid.addView(b, p);
    }

    private void setRemoteEnabled(boolean enabled) {
        for (Button b : remoteButtons) b.setEnabled(enabled);
    }

    @Override public void onStatus(String message) {
        runOnUiThread(() -> status.setText(message));
    }

    @Override public void onConnected(String consoleName, String ip) {
        runOnUiThread(() -> {
            ipField.setText(ip);
            status.setText("Conectado: " + consoleName + "  •  " + ip);
            connectButton.setText("RECONECTAR");
            connectButton.setEnabled(true);
            setRemoteEnabled(true);
        });
    }

    @Override public void onConnectionFailed(String reason) {
        runOnUiThread(() -> {
            status.setText(reason);
            connectButton.setEnabled(true);
            connectButton.setText("TENTAR NOVAMENTE");
            setRemoteEnabled(false);
        });
    }

    @Override public void onDisconnected(String reason) {
        runOnUiThread(() -> {
            status.setText(reason);
            connectButton.setEnabled(true);
            connectButton.setText("CONECTAR NOVAMENTE");
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
        if (bold) t.setTypeface(t.getTypeface(), android.graphics.Typeface.BOLD);
        return t;
    }

    private GradientDrawable roundRect(int color, int radiusDp) {
        GradientDrawable g = new GradientDrawable();
        g.setColor(color);
        g.setCornerRadius(dp(radiusDp));
        return g;
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
