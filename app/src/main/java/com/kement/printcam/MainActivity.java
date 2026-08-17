package com.kement.printcam;

import android.app.Activity;
import android.os.Bundle;
import android.text.InputType;
import android.view.SurfaceHolder;
import android.view.SurfaceView;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.EditText;
import android.widget.TextView;
import android.widget.Toast;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

public final class MainActivity extends Activity implements KementEngine.Listener {
    private EditText emailInput, passwordInput;
    private Button connectButton, stopButton;
    private TextView statusText, deviceText, statsText, telemetryText, logText;
    private SurfaceView videoSurface;
    private KementEngine engine;
    private final SimpleDateFormat timeFmt = new SimpleDateFormat("HH:mm:ss", Locale.getDefault());

    @Override protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);

        emailInput = findViewById(R.id.emailInput);
        passwordInput = findViewById(R.id.passwordInput);
        connectButton = findViewById(R.id.connectButton);
        stopButton = findViewById(R.id.stopButton);
        statusText = findViewById(R.id.statusText);
        deviceText = findViewById(R.id.deviceText);
        statsText = findViewById(R.id.statsText);
        telemetryText = findViewById(R.id.telemetryText);
        logText = findViewById(R.id.logText);
        videoSurface = findViewById(R.id.videoSurface);

        emailInput.setText(getPreferences(MODE_PRIVATE).getString("email", ""));
        passwordInput.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD);
        engine = new KementEngine(this, this);

        videoSurface.getHolder().addCallback(new SurfaceHolder.Callback() {
            @Override public void surfaceCreated(SurfaceHolder holder) { engine.setSurface(holder.getSurface()); }
            @Override public void surfaceChanged(SurfaceHolder holder, int format, int width, int height) { engine.setSurface(holder.getSurface()); }
            @Override public void surfaceDestroyed(SurfaceHolder holder) { engine.clearSurface(); }
        });

        connectButton.setOnClickListener(v -> connect());
        stopButton.setOnClickListener(v -> disconnect());
        updateButtons(false);
        appendLog("App pronto. Conecte primeiro e depois aperte uma vez a campainha.");
    }

    private void connect() {
        String email = emailInput.getText().toString().trim();
        String password = passwordInput.getText().toString();
        if (email.isEmpty() || password.isEmpty()) {
            Toast.makeText(this, "Informe e-mail e senha da Kement", Toast.LENGTH_SHORT).show();
            return;
        }
        getPreferences(MODE_PRIVATE).edit().putString("email", email).apply();
        passwordInput.setText("");
        updateButtons(true);
        statusText.setText("Conectando...");
        logText.setText("");
        engine.start(email, password);
    }

    private void disconnect() {
        updateButtons(false);
        engine.stop();
    }

    private void updateButtons(boolean active) {
        connectButton.setEnabled(!active);
        stopButton.setEnabled(active);
        emailInput.setEnabled(!active);
        passwordInput.setEnabled(!active);
    }

    private void appendLog(String line) {
        runOnUiThread(() -> {
            String stamp = timeFmt.format(new Date());
            String old = logText.getText().toString();
            String next = old + (old.isEmpty() ? "" : "\n") + stamp + "  " + line;
            if (next.length() > 18000) next = next.substring(next.length() - 16000);
            logText.setText(next);
        });
    }

    @Override public void onStatus(String text) {
        runOnUiThread(() -> statusText.setText(text));
        appendLog(text);
        if (text.startsWith("Falha:") || "Sessão encerrada.".equals(text)) {
            runOnUiThread(() -> updateButtons(false));
        }
    }
    @Override public void onLog(String text) { appendLog(text); }
    @Override public void onDevice(String name, String sn) {
        runOnUiThread(() -> deviceText.setText(name + "\n" + sn));
    }
    @Override public void onFirstFrame() { }
    @Override public void onStats(long packets, long videoPackets, int lastType) {
        runOnUiThread(() -> statsText.setText("P2P: " + packets + " pacotes | vídeo: " + videoPackets + " | tipo: " + lastType));
    }
    @Override public void onBattery(Integer battery, Integer rssi) {
        runOnUiThread(() -> {
            String b = battery == null ? "?" : battery + "%";
            String r = rssi == null ? "?" : rssi + " dBm";
            telemetryText.setText("Bateria: " + b + "   Wi-Fi: " + r);
        });
    }

    @Override protected void onDestroy() {
        if (engine != null) engine.shutdown();
        super.onDestroy();
    }
}
