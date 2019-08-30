package com.simonramstedt.yoke;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Context;
import android.content.SharedPreferences;
import android.content.res.Resources;
import android.net.nsd.NsdManager;
import android.net.nsd.NsdServiceInfo;
import android.os.Bundle;
import android.os.Handler;
import android.text.InputType;
import android.util.Log;
import android.view.Display;
import android.view.View;
import android.view.WindowManager;
import android.webkit.JavascriptInterface;
import android.webkit.WebView;
import android.widget.AdapterView;
import android.widget.ArrayAdapter;
import android.widget.EditText;
import android.widget.Spinner;
import android.widget.TextView;
import android.widget.Toast;

import java.io.IOException;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.SocketException;
import java.net.UnknownHostException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;



public class YokeActivity extends Activity implements NsdManager.DiscoveryListener {
    private final String SERVICE_TYPE = "_yoke._udp.";
    private String NOTHING;
    private String ENTER_IP;
    private WindowManager mWindowManager;
    private NsdManager mNsdManager;
    private NsdServiceInfo mService;
    private DatagramSocket mSocket;
    private String vals_str = null;
    private Map<String, NsdServiceInfo> mServiceMap = new HashMap<>();
    private List<String> mServiceNames = new ArrayList<>();
    private SharedPreferences sharedPref;
    private TextView mTextView;
    private Spinner mSpinner;
    private ArrayAdapter<String> mAdapter;
    private Handler handler;
    private WebView wv;
    private Resources res;

    private void log(String m) {
        if (BuildConfig.DEBUG)
            Log.d("Yoke", m);
    }


    class WebAppInterface {
        Context mContext;

        /** Instantiate the interface and set the context */
        WebAppInterface(Context c) {
            mContext = c;
        }

        /** Show a toast from the web page */
        @JavascriptInterface
        public void update_vals(String vals) {
            vals_str = vals;
            update();
        }
    }


    @Override
    public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        sharedPref = getPreferences(Context.MODE_PRIVATE);
        setContentView(R.layout.main_wv);

        wv = findViewById(R.id.webView);

        wv.getSettings().setJavaScriptEnabled(true);
        wv.addJavascriptInterface(new WebAppInterface(this), "Yoke");

        // Get an instance of the WindowManager
        mWindowManager = (WindowManager) getSystemService(WINDOW_SERVICE);

        mNsdManager = (NsdManager) getSystemService(Context.NSD_SERVICE);

        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);

        // Localization. TODO: detect NOTHING and MANUAL CONNECTION buttons by id, not by content.
        res = getResources();
        NOTHING = res.getString(R.string.dropdown_nothing);
        ENTER_IP = res.getString(R.string.dropdown_enter_ip);

        mTextView = (TextView) findViewById(R.id.textView);

        mSpinner = (Spinner) findViewById(R.id.spinner);
        mAdapter = new ArrayAdapter<>(getApplicationContext(), android.R.layout.simple_spinner_item);
        mAdapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        mAdapter.add(NOTHING);
        mAdapter.add(ENTER_IP);

        for (String adr : sharedPref.getString("addresses", "").split(System.lineSeparator())) {
            adr = adr.trim(); // workaround for android bug where random whitespace is added to Strings in shared preferences
            if (!adr.isEmpty()) {
                mAdapter.add(adr);
                mServiceNames.add(adr);
                log(String.format(res.getString(R.string.log_adding_address), adr));
            }
        }
        mSpinner.setAdapter(mAdapter);
        mSpinner.setPrompt(res.getString(R.string.dropdown_connect_to));
        mSpinner.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            @Override
            public void onItemSelected(AdapterView<?> parent, View view, int pos, long l) {

                String tgt = parent.getItemAtPosition(pos).toString();

                // clean up old target if no longer available
                String oldtgt = mSpinner.getSelectedItem().toString();
                if (!mServiceNames.contains(oldtgt) && !oldtgt.equals(NOTHING) && !oldtgt.equals(ENTER_IP)) {
                    mAdapter.remove(oldtgt);
                    if (oldtgt.equals(tgt)) {
                        tgt = NOTHING;
                    }
                }

                closeConnection();

                if (tgt.equals(NOTHING)) {

                } else if (tgt.equals(ENTER_IP)) {
                    AlertDialog.Builder builder = new AlertDialog.Builder(YokeActivity.this);
                    builder.setTitle(res.getString(R.string.enter_ip_title));

                    final EditText input = new EditText(YokeActivity.this);
                    input.setInputType(InputType.TYPE_CLASS_TEXT);
                    input.setHint(res.getString(R.string.enter_ip_hint));

                    builder.setView(input);

                    builder.setPositiveButton(res.getString(R.string.enter_ip_ok), (dialog, which) -> {
                        String name = input.getText().toString();

                        boolean invalid = name.split(":").length != 2;

                        if (!invalid) {
                            try {
                                Integer.parseInt(name.split(":")[1]);
                            } catch (NumberFormatException e) {
                                invalid = true;
                            }
                        }

                        if (invalid) {
                            mSpinner.setSelection(mAdapter.getPosition(NOTHING));
                            Toast.makeText(YokeActivity.this, res.getString(R.string.toast_invalid_address), Toast.LENGTH_SHORT).show();

                        } else {
                            mServiceNames.add(name);
                            mAdapter.add(name);
                            mSpinner.setSelection(mAdapter.getPosition(name));

                            SharedPreferences.Editor editor = sharedPref.edit();
                            String addresses = sharedPref.getString("addresses", "");
                            addresses = addresses + name + System.lineSeparator();
                            editor.putString("addresses", addresses);
                            editor.apply();
                        }
                    });
                    builder.setNegativeButton(res.getString(R.string.enter_ip_cancel), (dialog, which) -> {
                        mSpinner.setSelection(mAdapter.getPosition(NOTHING));
                        dialog.cancel();
                    });

                    builder.show();
                } else {
                    log(String.format(res.getString(R.string.log_service_targeting), tgt));

                    if (mService != null)  // remove
                        log(res.getString(R.string.log_service_not_null));

                    if (mServiceMap.containsKey(tgt)) {
                        connectToService(tgt);
                    } else {
                        connectToAddress(tgt);
                    }
                }
            }

            @Override
            public void onNothingSelected(AdapterView<?> adapterView) {
                log(res.getString(R.string.log_nothing_selected));
            }
        });

    }

    @Override
    protected void onResume() {
        super.onResume();

        mNsdManager.discoverServices(SERVICE_TYPE, NsdManager.PROTOCOL_DNS_SD, this);

        handler = new Handler();
        handler.post(new Runnable() {

            @Override
            public void run() {
                update();

                if (handler != null)
                    handler.postDelayed(this, 20);
            }
        });

    }

    @Override
    protected void onPause() {
        super.onPause();

        mNsdManager.stopServiceDiscovery(this);

        closeConnection();

        handler = null;
    }

    public void onWindowFocusChanged(boolean hasFocus) {
        super.onWindowFocusChanged(hasFocus);
        if (hasFocus) {
            getWindow().getDecorView().setSystemUiVisibility(
                View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
                | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                | View.SYSTEM_UI_FLAG_FULLSCREEN
                | View.SYSTEM_UI_FLAG_IMMERSIVE
                | View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY);
        }
    }

    private void update() {
        if (mSocket != null && vals_str != null) {
            send(vals_str.getBytes());
        }
    }

    public void send(byte[] msg) {
        try {
            mSocket.send(new DatagramPacket(msg, msg.length));
        } catch (IOException e) {
            log(String.format(res.getString(R.string.log_send_error), e.getMessage()));
        } catch (NullPointerException e) {
            log(String.format(res.getString(R.string.log_send_error), e.getMessage()));
        }
    }

    public void connectToService(String tgt) {
        NsdServiceInfo service = mServiceMap.get(tgt);
        log(String.format(res.getString(R.string.log_service_resolving), service.getServiceType()));
        mNsdManager.resolveService(service, new NsdManager.ResolveListener() {
            @Override
            public void onResolveFailed(NsdServiceInfo serviceInfo, int errorCode) {
                log(String.format(res.getString(R.string.log_service_resolve_error), errorCode));
                mSpinner.setSelection(mAdapter.getPosition(NOTHING));
            }

            @Override
            public void onServiceResolved(NsdServiceInfo serviceInfo) {
                // check name again (could have changed in the mean time)
                if (tgt.equals(serviceInfo.getServiceName())) {
                    log(res.getString(R.string.log_service_resolve_success) + serviceInfo);

                    mService = serviceInfo;
                    openSocket(mService.getHost().getHostName(), mService.getPort());

                }
            }
        });
    }

    public void connectToAddress(String tgt) {
        log(String.format(res.getString(R.string.log_directly_connecting), tgt));
        String[] addr = tgt.split(":");
        (new Thread(()-> openSocket(addr[0], Integer.parseInt(addr[1])))).start();
    }

    public void reconnect(View view) {
        String tgt = mSpinner.getSelectedItem().toString();
        if (tgt.equals(NOTHING)) {
            Toast.makeText(YokeActivity.this, res.getString(R.string.toast_cant_reconnect_if_not_connected), Toast.LENGTH_SHORT).show();
        } else {
            closeConnection();
            if (mServiceMap.containsKey(tgt)) {
                connectToService(tgt);
            } else {
                connectToAddress(tgt);
            }
        }
    }

    public void openSocket(String host, int port) {
        log(String.format(res.getString(R.string.log_opening_udp), host, port));

        try {
            mSocket = new DatagramSocket(0);
            mSocket.connect(InetAddress.getByName(host), port);

            log(res.getString(R.string.log_open_udp_success));
            YokeActivity.this.runOnUiThread(() -> {
                mTextView.setText(res.getString(R.string.toolbar_connected_to));

                String url = "http://" + host + ":" + port + "/main.html";
                wv.loadUrl(url);
                log(String.format(res.getString(R.string.log_loading_url), url));
            });

        } catch (SocketException | UnknownHostException e) {
            mSocket = null;
            YokeActivity.this.runOnUiThread(() -> {
                mSpinner.setSelection(mAdapter.getPosition(NOTHING));
                Toast.makeText(YokeActivity.this, String.format(res.getString(R.string.toast_could_not_connect), host, port), Toast.LENGTH_SHORT).show();
            });
            log(res.getString(R.string.log_open_udp_error));
            e.printStackTrace();
        }
    }

    public void onDiscoveryStarted(String regType) {
        log(res.getString(R.string.log_discovery_started));
    }

    @Override
    public void onServiceFound(NsdServiceInfo service) {
        log(res.getString(R.string.log_service_found) + service);
        mServiceMap.put(service.getServiceName(), service);
        mServiceNames.add(service.getServiceName());
        this.runOnUiThread(() -> {
            if (mSpinner.getSelectedItem().toString().equals(service.getServiceName()))
                return;
            mAdapter.add(service.getServiceName());
        });

    }



    @Override
    public void onServiceLost(NsdServiceInfo service) {
        log(res.getString(R.string.log_service_lost) + service);
        mServiceMap.remove(service.getServiceName());
        mServiceNames.remove(service.getServiceName());
        this.runOnUiThread(() -> {
            if (mSpinner.getSelectedItem().toString().equals(service.getServiceName()))
                return;

            mAdapter.remove(service.getServiceName());
        });

    }

    @Override
    public void onDiscoveryStopped(String serviceType) {
        log(String.format(res.getString(R.string.log_discovery_stopped), serviceType));
    }

    @Override
    public void onStartDiscoveryFailed(String serviceType, int errorCode) {
        log(String.format(res.getString(R.string.log_discovery_error), errorCode));
        mNsdManager.stopServiceDiscovery(this);
    }

    @Override
    public void onStopDiscoveryFailed(String serviceType, int errorCode) {
        log(String.format(res.getString(R.string.log_discovery_error), errorCode));
        mNsdManager.stopServiceDiscovery(this);
    }

    private void closeConnection() {
        mService = null;
        if (mSocket != null) {
            log(res.getString(R.string.log_udp_closed));
            mTextView.setText(res.getString(R.string.toolbar_connect_to));
            mSocket.close();
            mSocket = null;
        }
        wv.loadUrl("about:blank");
        vals_str = null;
    }
}
