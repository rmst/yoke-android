package com.simonramstedt.yoke;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Context;
import android.content.SharedPreferences;
import android.net.nsd.NsdManager;
import android.os.Bundle;
import android.os.Handler;
import android.os.PowerManager;
import android.os.PowerManager.WakeLock;
import android.text.InputType;
import android.util.Log;
import android.view.Display;
import android.view.View;
import android.view.WindowManager;
import android.webkit.JavascriptInterface;
import android.webkit.WebView;
import android.widget.AdapterView;
import android.widget.ArrayAdapter;
import android.net.nsd.NsdServiceInfo;
import android.widget.EditText;
import android.widget.Spinner;
import android.widget.Switch;
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
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Timer;
import java.util.concurrent.locks.ReentrantLock;



public class YokeActivity extends Activity implements NsdManager.DiscoveryListener {
    private static final String SERVICE_TYPE = "_yoke._udp.";
    private static final String NOTHING = "> nothing ";
    private static final String ENTER_IP = "> new manual connection";
    private PowerManager mPowerManager;
    private WindowManager mWindowManager;
    private Display mDisplay;
    private WakeLock mWakeLock;
    private ServerSocket mServerSocket;
    private NsdManager mNsdManager;
    private NsdServiceInfo mNsdServiceInfo;
    private NsdServiceInfo mService;
    private final ReentrantLock resolving = new ReentrantLock();
    private DatagramSocket mSocket;
    private String vals_str = null;
    private Timer mTimer;
    private Map<String, NsdServiceInfo> mServiceMap = new HashMap<>();
    private List<String> mServiceNames = new ArrayList<>();
    private SharedPreferences sharedPref;
    private TextView mTextView;
    private Spinner mSpinner;
    private ArrayAdapter<String> mAdapter;
    private String mTarget = "";
    private Handler handler;
    private WebView wv;

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

        // Get an instance of the PowerManager
        mPowerManager = (PowerManager) getSystemService(POWER_SERVICE);

        // Get an instance of the WindowManager
        mWindowManager = (WindowManager) getSystemService(WINDOW_SERVICE);
        mDisplay = mWindowManager.getDefaultDisplay();

        mNsdManager = (NsdManager) getSystemService(Context.NSD_SERVICE);

        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);

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
                log("adding " + adr);
            }
        }
        mSpinner.setAdapter(mAdapter);
        mSpinner.setPrompt("Connect to ...");
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
                    builder.setTitle("Enter ip address and port");

                    final EditText input = new EditText(YokeActivity.this);
                    input.setInputType(InputType.TYPE_CLASS_TEXT);
                    input.setHint("e.g. 192.168.1.123:11111");

                    builder.setView(input);

                    builder.setPositiveButton("OK", (dialog, which) -> {
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
                            Toast.makeText(YokeActivity.this, "Invalid address", Toast.LENGTH_SHORT).show();

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
                    builder.setNegativeButton("Cancel", (dialog, which) -> {
                        mSpinner.setSelection(mAdapter.getPosition(NOTHING));
                        dialog.cancel();
                    });

                    builder.show();
                } else {
                    log("new target " + tgt);

                    if (mService != null)  // remove
                        log("SERVICE NOT NULL!!!");

                    if (mServiceMap.containsKey(tgt)) {
                        connectToService(tgt);
                    } else {
                        connectToAddress(tgt);
                    }
                }
            }

            @Override
            public void onNothingSelected(AdapterView<?> adapterView) {
                log("nothing selected");
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

    private void update() {
        if (mSocket != null && vals_str != null) {
            send(vals_str.getBytes());
        }
    }

    public void send(byte[] msg) {
        try {
            mSocket.send(new DatagramPacket(msg, msg.length));
        } catch (IOException e) {
            log("Send error: " + e.getMessage());
        } catch (NullPointerException e) {
            log("Send error " + e.getMessage());
        }
    }

    public void connectToService(String tgt) {
        NsdServiceInfo service = mServiceMap.get(tgt);
        log("Resolving Service: " + service.getServiceType());
        mNsdManager.resolveService(service, new NsdManager.ResolveListener() {
            @Override
            public void onResolveFailed(NsdServiceInfo serviceInfo, int errorCode) {
                log("Resolve failed: " + errorCode);
                mSpinner.setSelection(mAdapter.getPosition(NOTHING));
            }

            @Override
            public void onServiceResolved(NsdServiceInfo serviceInfo) {
                // check name again (could have changed in the mean time)
                if (tgt.equals(serviceInfo.getServiceName())) {
                    log("Resolve Succeeded. " + serviceInfo);

                    mService = serviceInfo;
                    openSocket(mService.getHost().getHostName(), mService.getPort());

                }
            }
        });
    }

    public void connectToAddress(String tgt) {
        log("Connecting directly ip address " + tgt);
        String[] addr = tgt.split(":");
        (new Thread(()-> openSocket(addr[0], Integer.parseInt(addr[1])))).start();
    }

    public void openSocket(String host, int port) {
        log("Trying to open UDP socket to " + host + " on port " + port);

        try {
            mSocket = new DatagramSocket(0);
            mSocket.connect(InetAddress.getByName(host), port);

            log("Connected");
            YokeActivity.this.runOnUiThread(() -> {
                mTextView.setText("Connected to");

                String url = "http://" + host + ":" + port + "/main.html";
                wv.loadUrl(url);
                log("Loading from " + url);
            });

        } catch (SocketException | UnknownHostException e) {
            mSocket = null;
            YokeActivity.this.runOnUiThread(() -> {
                mSpinner.setSelection(mAdapter.getPosition(NOTHING));
                Toast.makeText(YokeActivity.this, "Failed to connect to " + host + ':' + port, Toast.LENGTH_SHORT).show();
            });
            log("Failed to open UDP socket (error message following)");
            e.printStackTrace();
        }
    }

    public void onDiscoveryStarted(String regType) {
        log("Service discovery started");
    }

    @Override
    public void onServiceFound(NsdServiceInfo service) {
        log("Service found " + service);
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
        log("Service lost " + service);
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
        log("Discovery stopped: " + serviceType);
    }

    @Override
    public void onStartDiscoveryFailed(String serviceType, int errorCode) {
        log("Discovery failed: Error code:" + errorCode);
        mNsdManager.stopServiceDiscovery(this);
    }

    @Override
    public void onStopDiscoveryFailed(String serviceType, int errorCode) {
        log("Discovery failed: Error code:" + errorCode);
        mNsdManager.stopServiceDiscovery(this);
    }

    private void closeConnection() {
        mService = null;
        if (mSocket != null) {
            log("Connection closed");
            mTextView.setText(" Connect to ");
            mSocket.close();
            mSocket = null;
        }
    }
}
