package com.carplay.radio;

import android.Manifest;
import android.app.Activity;
import android.bluetooth.BluetoothA2dp;
import android.bluetooth.BluetoothDevice;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.nfc.NfcAdapter;
import android.os.Bundle;
import android.os.Vibrator;
import android.util.Log;
import android.view.View;
import android.webkit.GeolocationPermissions;
import android.webkit.WebChromeClient;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.Button;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;
import java.util.List;

public class MainActivity extends Activity implements CarRadioEngine.StateListener {

    private static final String TAG = "CarPlayMainActivity";

    private CarRadioEngine radioEngine;
    private TextView tvFreq;
    private TextView tvStationTitle;
    private TextView tvStatus;
    private Button btnPlay;
    private Button btnPrev;
    private Button btnNext;
    private Button btnToggleWeb;
    private Button btnToggleMap;
    private Button btnKeyUnlock;
    private Button btnUpdate;
    private LinearLayout presetContainer;
    private LinearLayout carDashboardLayout;
    private WebView webViewVdomov;
    private WebView webViewGoogleMap;
    private FrameLayout leftContentFrame;
    private FrameLayout rightMapFrame;
    private LinearLayout miniPlayerOverlay;
    private TextView tvMiniStation;
    private Button btnMiniPlay;
    private Button btnMiniPrev;
    private Button btnMiniNext;

    private boolean isShowingWeb = false;
    private boolean isKeyUnlocked = true;
    private int mapDisplayMode = 0; // 0 = SPLIT, 1 = FULL MAP, 2 = HIDE MAP

    private BroadcastReceiver bluetoothReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            if (intent == null || intent.getAction() == null) return;
            String action = intent.getAction();
            Log.d(TAG, "Bluetooth event received: " + action);

            if (BluetoothDevice.ACTION_ACL_CONNECTED.equals(action) ||
                BluetoothA2dp.ACTION_CONNECTION_STATE_CHANGED.equals(action)) {
                Log.d(TAG, "Car Bluetooth connected! Auto playing radio...");
                if (radioEngine != null && !radioEngine.isPlaying()) {
                    radioEngine.playStation(0);
                }
            }
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        tvFreq = findViewById(R.id.tvFreq);
        tvStationTitle = findViewById(R.id.tvStationTitle);
        tvStatus = findViewById(R.id.tvStatus);
        btnPlay = findViewById(R.id.btnPlay);
        btnPrev = findViewById(R.id.btnPrev);
        btnNext = findViewById(R.id.btnNext);
        btnToggleWeb = findViewById(R.id.btnToggleWeb);
        btnToggleMap = findViewById(R.id.btnToggleMap);
        btnKeyUnlock = findViewById(R.id.btnKeyUnlock);
        btnUpdate = findViewById(R.id.btnUpdate);
        presetContainer = findViewById(R.id.presetContainer);
        carDashboardLayout = findViewById(R.id.carDashboardLayout);
        webViewVdomov = findViewById(R.id.webViewVdomov);
        webViewGoogleMap = findViewById(R.id.webViewGoogleMap);
        leftContentFrame = findViewById(R.id.leftContentFrame);
        rightMapFrame = findViewById(R.id.rightMapFrame);
        miniPlayerOverlay = findViewById(R.id.miniPlayerOverlay);
        tvMiniStation = findViewById(R.id.tvMiniStation);
        btnMiniPlay = findViewById(R.id.btnMiniPlay);
        btnMiniPrev = findViewById(R.id.btnMiniPrev);
        btnMiniNext = findViewById(R.id.btnMiniNext);

        radioEngine = new CarRadioEngine(this, this);

        if (checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{
                Manifest.permission.ACCESS_FINE_LOCATION,
                Manifest.permission.ACCESS_COARSE_LOCATION
            }, 101);
        }

        btnPlay.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                radioEngine.togglePlayPause();
            }
        });

        btnPrev.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                radioEngine.previousStation();
            }
        });

        btnNext.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                radioEngine.nextStation();
            }
        });

        btnMiniPlay.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                radioEngine.togglePlayPause();
            }
        });

        btnMiniPrev.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                radioEngine.previousStation();
            }
        });

        btnMiniNext.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                radioEngine.nextStation();
            }
        });

        btnToggleWeb.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                toggleWebMode();
            }
        });

        btnToggleMap.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                toggleMapMode();
            }
        });

        btnKeyUnlock.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                toggleKeyUnlock();
            }
        });

        btnUpdate.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                AppUpdater.checkForUpdates(MainActivity.this, true);
            }
        });

        setupWebViews();
        populatePresets();
        updateUI(radioEngine.getCurrentStation(), radioEngine.isPlaying(), radioEngine.isLoading());

        // Register Bluetooth Auto-Play Receiver
        IntentFilter filter = new IntentFilter();
        filter.addAction(BluetoothDevice.ACTION_ACL_CONNECTED);
        filter.addAction(BluetoothA2dp.ACTION_CONNECTION_STATE_CHANGED);
        registerReceiver(bluetoothReceiver, filter);

        handleNfcCarKeyIntent(getIntent());

        // Check for App Updates automatically
        AppUpdater.checkForUpdates(this, false);

        // Auto play on app start
        radioEngine.playStation(0);
    }

    @Override
    protected void onResume() {
        super.onResume();
        AppUpdater.checkResumeInstall(this);
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        handleNfcCarKeyIntent(intent);
    }

    private void handleNfcCarKeyIntent(Intent intent) {
        if (intent != null && (NfcAdapter.ACTION_NDEF_DISCOVERED.equals(intent.getAction()) ||
                               NfcAdapter.ACTION_TAG_DISCOVERED.equals(intent.getAction()))) {
            Log.d(TAG, "NFC Digital Phone Key Tag Discovered! Auto unlocking car...");
            isKeyUnlocked = true;
            vibrateHaptic();
            if (radioEngine != null && !radioEngine.isPlaying()) {
                radioEngine.playStation(0);
            }
        }
    }

    private void toggleKeyUnlock() {
        isKeyUnlocked = !isKeyUnlocked;
        vibrateHaptic();
        if (isKeyUnlocked) {
            btnKeyUnlock.setText("🔑 UNLOCKED");
            btnKeyUnlock.setBackgroundColor(Color.parseColor("#00C853"));
        } else {
            btnKeyUnlock.setText("🔒 LOCKED");
            btnKeyUnlock.setBackgroundColor(Color.parseColor("#FF1744"));
        }
    }

    private void vibrateHaptic() {
        try {
            Vibrator v = (Vibrator) getSystemService(Context.VIBRATOR_SERVICE);
            if (v != null && v.hasVibrator()) {
                v.vibrate(100);
            }
        } catch (Exception ignored) {}
    }

    private void setupWebViews() {
        // Setup VDOmov Full App WebView
        WebSettings webSettings = webViewVdomov.getSettings();
        webSettings.setJavaScriptEnabled(true);
        webSettings.setDomStorageEnabled(true);
        webSettings.setMediaPlaybackRequiresUserGesture(false);
        webViewVdomov.setWebViewClient(new WebViewClient());
        webViewVdomov.loadUrl("https://vdomov.vercel.app/radio");

        // Setup Embedded Google Maps Driving WebView
        WebSettings mapSettings = webViewGoogleMap.getSettings();
        mapSettings.setJavaScriptEnabled(true);
        mapSettings.setDomStorageEnabled(true);
        mapSettings.setDatabaseEnabled(true);
        mapSettings.setGeolocationEnabled(true);
        mapSettings.setMixedContentMode(WebSettings.MIXED_CONTENT_ALWAYS_ALLOW);
        mapSettings.setUserAgentString("Mozilla/5.0 (Linux; Android 13; Mobile) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Mobile Safari/537.36");
        webViewGoogleMap.setWebViewClient(new WebViewClient() {
            @Override
            public boolean shouldOverrideUrlLoading(WebView view, String url) {
                view.loadUrl(url);
                return true;
            }
        });
        webViewGoogleMap.setWebChromeClient(new WebChromeClient() {
            @Override
            public void onGeolocationPermissionsShowPrompt(String origin, GeolocationPermissions.Callback callback) {
                callback.invoke(origin, true, false);
            }
        });

        String googleMapHtml = "<!DOCTYPE html><html><head>"
            + "<meta name='viewport' content='width=device-width, initial-scale=1.0, maximum-scale=1.0, user-scalable=no' />"
            + "<style>"
            + "html, body { width: 100%; height: 100%; margin: 0; padding: 0; overflow: hidden; background: #0A0E17; font-family: sans-serif; }"
            + "#mapframe { width: 100%; height: 100%; border: 0; }"
            + ".hud-card { position: absolute; top: 10px; left: 10px; z-index: 100; background: rgba(20,28,43,0.92); color: #00E5FF; padding: 8px 12px; border-radius: 8px; border: 1px solid #00E5FF; font-size: 13px; font-weight: bold; box-shadow: 0 4px 12px rgba(0,0,0,0.6); display: flex; align-items: center; gap: 8px; }"
            + ".speed-tag { background: #00E5FF; color: #0A0E17; padding: 2px 6px; border-radius: 4px; font-size: 12px; font-weight: bold; margin-left: 6px; }"
            + ".gps-btn { position: absolute; bottom: 64px; right: 12px; z-index: 100; background: #00E5FF; color: #0A0E17; border: none; padding: 10px 14px; border-radius: 20px; font-weight: bold; font-size: 13px; box-shadow: 0 4px 12px rgba(0,0,0,0.6); cursor: pointer; }"
            + "</style></head><body>"
            + "<div class='hud-card' id='hudCard'><span>🚘 DRIVING NAV • SEARCHING GPS</span><span class='speed-tag' id='speedTag'>0 km/h</span></div>"
            + "<button class='gps-btn' onclick='recenterMap()'>🎯 RE-CENTER</button>"
            + "<iframe id='mapframe' src='https://maps.google.com/maps?q=11.5564,104.9282&amp;t=&amp;z=17&amp;ie=UTF8&amp;iwloc=&amp;output=embed' allowfullscreen></iframe>"
            + "<script>"
            + "var lastLat = 0, lastLng = 0;"
            + "function updateDrivingLocation(pos) {"
            + "  var lat = pos.coords.latitude;"
            + "  var lng = pos.coords.longitude;"
            + "  var speedMps = pos.coords.speed || 0;"
            + "  var speedKmh = Math.round(speedMps * 3.6);"
            + "  document.getElementById('hudCard').innerHTML = '<span>🚘 FOLLOWING DRIVING (' + lat.toFixed(4) + ', ' + lng.toFixed(4) + ')</span><span class=\"speed-tag\">' + speedKmh + ' km/h</span>';"
            + "  if (Math.abs(lat - lastLat) > 0.00004 || Math.abs(lng - lastLng) > 0.00004) {"
            + "    lastLat = lat;"
            + "    lastLng = lng;"
            + "    document.getElementById('mapframe').src = 'https://maps.google.com/maps?q=' + lat + ',' + lng + '&t=&z=17&ie=UTF8&iwloc=&output=embed';"
            + "  }"
            + "}"
            + "function recenterMap() {"
            + "  if (navigator.geolocation) {"
            + "    navigator.geolocation.getCurrentPosition(updateDrivingLocation, null, { enableHighAccuracy: true });"
            + "  }"
            + "}"
            + "if (navigator.geolocation) {"
            + "  navigator.geolocation.watchPosition(updateDrivingLocation, function(e) {"
            + "    console.log('GPS error:', e);"
            + "  }, { enableHighAccuracy: true, maximumAge: 1000, timeout: 5000 });"
            + "}"
            + "</script>"
            + "</body></html>";

        webViewGoogleMap.loadDataWithBaseURL("https://maps.google.com", googleMapHtml, "text/html", "UTF-8", null);
    }

    private void toggleWebMode() {
        isShowingWeb = !isShowingWeb;
        if (isShowingWeb) {
            webViewVdomov.setVisibility(View.VISIBLE);
            carDashboardLayout.setVisibility(View.GONE);
            btnToggleWeb.setText("CAR RADIO");
            btnToggleWeb.setBackgroundColor(Color.parseColor("#FFB300"));
        } else {
            webViewVdomov.setVisibility(View.GONE);
            carDashboardLayout.setVisibility(View.VISIBLE);
            btnToggleWeb.setText("FULL APP");
            btnToggleWeb.setBackgroundColor(Color.parseColor("#1C273B"));
        }
    }

    private void toggleMapMode() {
        mapDisplayMode = (mapDisplayMode + 1) % 3;
        if (mapDisplayMode == 0) { // SPLIT MODE
            leftContentFrame.setVisibility(View.VISIBLE);
            rightMapFrame.setVisibility(View.VISIBLE);
            miniPlayerOverlay.setVisibility(View.GONE);
            btnToggleMap.setText("MAP: SPLIT");
            btnToggleMap.setBackgroundColor(Color.parseColor("#00E5FF"));
        } else if (mapDisplayMode == 1) { // FULL MAP MODE
            leftContentFrame.setVisibility(View.GONE);
            rightMapFrame.setVisibility(View.VISIBLE);
            miniPlayerOverlay.setVisibility(View.VISIBLE);
            btnToggleMap.setText("MAP: FULL");
            btnToggleMap.setBackgroundColor(Color.parseColor("#FF9100"));
        } else { // HIDE MAP MODE
            leftContentFrame.setVisibility(View.VISIBLE);
            rightMapFrame.setVisibility(View.GONE);
            miniPlayerOverlay.setVisibility(View.GONE);
            btnToggleMap.setText("MAP: HIDE");
            btnToggleMap.setBackgroundColor(Color.parseColor("#1C273B"));
        }
    }

    private void populatePresets() {
        presetContainer.removeAllViews();
        List<CarRadioEngine.Station> stations = radioEngine.getStationList();

        for (int i = 0; i < stations.size(); i++) {
            final int index = i;
            final CarRadioEngine.Station st = stations.get(i);

            Button btn = new Button(this);
            btn.setText(st.frequencyMhz + " MHz - " + st.name);
            btn.setTextColor(Color.WHITE);
            btn.setTextSize(14);
            btn.setBackgroundColor(Color.parseColor("#1C273B"));
            
            LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            );
            params.setMargins(0, 0, 0, 8);
            btn.setLayoutParams(params);
            btn.setPadding(16, 20, 16, 20);

            btn.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    radioEngine.playStation(index);
                }
            });

            presetContainer.addView(btn);
        }
    }

    @Override
    public void onStationChanged(CarRadioEngine.Station station, boolean isPlaying, boolean isLoading) {
        updateUI(station, isPlaying, isLoading);
    }

    private void updateUI(CarRadioEngine.Station station, boolean isPlaying, boolean isLoading) {
        if (station != null) {
            tvFreq.setText(station.frequencyMhz + " MHz");
            tvStationTitle.setText(station.name);
            tvMiniStation.setText(station.frequencyMhz + " MHz - " + station.name);
        }

        if (isLoading) {
            tvStatus.setText("CONNECTING LIVE STREAM...");
            btnPlay.setText("⏳ LOADING");
            btnPlay.setBackgroundColor(Color.parseColor("#FFB300"));
            btnMiniPlay.setText("⏳");
            btnMiniPlay.setBackgroundColor(Color.parseColor("#FFB300"));
        } else if (isPlaying) {
            tvStatus.setText("● PLAYING LIVE (PHONE KEY ACTIVE)");
            tvStatus.setTextColor(Color.parseColor("#00E5FF"));
            btnPlay.setText("⏸ PAUSE");
            btnPlay.setBackgroundColor(Color.parseColor("#FF1744"));
            btnMiniPlay.setText("⏸");
            btnMiniPlay.setBackgroundColor(Color.parseColor("#FF1744"));
        } else {
            tvStatus.setText("READY TO PLAY");
            tvStatus.setTextColor(Color.parseColor("#8A99AD"));
            btnPlay.setText("▶ PLAY");
            btnPlay.setBackgroundColor(Color.parseColor("#00E5FF"));
            btnMiniPlay.setText("▶");
            btnMiniPlay.setBackgroundColor(Color.parseColor("#00E5FF"));
        }
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        try {
            unregisterReceiver(bluetoothReceiver);
        } catch (Exception ignored) {}
        if (radioEngine != null) {
            radioEngine.stop();
        }
    }
}

