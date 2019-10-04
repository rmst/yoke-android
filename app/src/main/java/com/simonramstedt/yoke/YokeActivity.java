package com.simonramstedt.yoke;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Context;
import android.content.SharedPreferences;
import android.content.res.Resources;
import android.net.nsd.NsdManager;
import android.net.nsd.NsdServiceInfo;
import android.os.AsyncTask;
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

import java.io.BufferedInputStream;
import java.io.BufferedReader;
import java.io.File;
import java.io.FileOutputStream;
import java.io.FileReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.lang.SecurityException;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.SocketException;
import java.net.URL;
import java.net.URLConnection;
import java.net.UnknownHostException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.json.JSONArray;
import org.json.JSONObject;
import org.json.JSONException;


public class YokeActivity extends Activity implements NsdManager.DiscoveryListener {
    private final String SERVICE_TYPE = "_yoke._udp.";
    private String NOTHING;
    private String ENTER_IP;
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
    protected WebView wv;
    protected Resources res;
    protected File currentJoypadPath;
    protected File currentMainPath;
    protected File currentManifestPath;
    protected File futureJoypadPath;
    protected File futureMainPath;
    protected File futureManifestPath;

    private void log(String m) {
        if (BuildConfig.DEBUG)
            Log.d("Yoke", m);
    }

    private void logToast(String m) {
        Log.e("Yoke", m);
        YokeActivity.this.runOnUiThread(() -> {
            Toast.makeText(YokeActivity.this, m, Toast.LENGTH_LONG).show();
        });
    }

    protected void deleteRecursively(File joypadPath) throws IOException {
        ArrayList<File> erasables = new ArrayList<>();
        erasables.add(joypadPath);
        for (int i = 0; i < erasables.size(); i++) {
            // don't optimize the line above. The ArrayList will be modified at every iteration.
            File folder = erasables.get(i);
            if (folder.isDirectory()) {
                for (String entry : folder.list()) {
                    erasables.add(new File(folder, entry));
                }
            }
        }
        // Files should already be ordered by depth, so we iterate backwards:
        for (int i = erasables.size() - 1; i >= 0; i--) {
            File currentFile = erasables.get(i);
            if (!currentFile.delete()) {
                throw new IOException(String.format(res.getString(R.string.error_could_not_delete), currentFile));
            }
        }
    }

    class WebAppInterface {
        Context mContext;

        // Instantiate the interface and set the context
        WebAppInterface(Context c) {
            mContext = c;
        }

        // Webpage uses this method to update joypad state:
        @JavascriptInterface
        public void update_vals(String vals) {
            vals_str = vals;
            update();
        }
    }

    // https://stackoverflow.com/questions/15758856/android-how-to-download-file-from-webserver/
    class DownloadFilesFromURL extends AsyncTask<String, String, String> {
        public String errorMessage = null;

        @Override
        protected void onPreExecute() {
            super.onPreExecute();
            mTextView.setText(res.getString(R.string.toolbar_downloading));
            try {
                if (futureJoypadPath.exists()) {
                    deleteRecursively(futureJoypadPath);
                }
                log(String.format(res.getString(R.string.log_creating_folder), futureJoypadPath.getAbsolutePath()));
                if (!futureJoypadPath.mkdirs()) {
                    throw new IOException(String.format(res.getString(R.string.error_could_not_create), futureJoypadPath.getAbsolutePath()));
                }
            } catch (SecurityException e) {
                logToast(String.format(res.getString(R.string.error_security_exception), e.getMessage()));
                cancel(true);
            } catch (IOException e) {
                logToast(String.format(res.getString(R.string.error_io_exception), e.getMessage()));
                cancel(true);
            }
        }

        @Override
        protected String doInBackground(String... f_url) {
            StringBuilder manifestSB = new StringBuilder();
            try {
                byte data[] = new byte[1024];

                URL url = new URL(f_url[0] + "manifest");
                InputStream input = new BufferedInputStream(url.openStream(), 8192);
                OutputStream output = new FileOutputStream(futureManifestPath);
                int count;
                while ((count = input.read(data)) != -1) {
                    //TODO: warn that the manifest is downloading
                    output.write(data, 0, count);
                }
                BufferedReader inputBR = new BufferedReader(
                    new FileReader(futureManifestPath)
                );
                String line = "";
                while ((line = inputBR.readLine()) != null) {
                    manifestSB.append(line).append("\n");
                }
                JSONObject manifestJSON = new JSONObject(manifestSB.toString());

                long totalBytes = manifestJSON.optLong("size", -1);
                JSONArray entries = manifestJSON.getJSONArray("folders");
                for (int i = 0, length = entries.length(); i < length; i++) {
                    File newFolder = new File(futureJoypadPath, entries.getString(i));
                    log(String.format(res.getString(R.string.log_creating_folder), newFolder.getAbsolutePath()));
                    if (!newFolder.mkdirs()) {
                        errorMessage = String.format(res.getString(R.string.error_could_not_create), newFolder.getAbsolutePath());
                        cancel(true);
                    }
                }

                entries = manifestJSON.getJSONArray("files");
                long cumulativeBytes = 0;
                for (int i = 0, length=entries.length(); i < length; i++) {
                    File newFile = new File(futureJoypadPath, entries.getString(i));
                    log(String.format(res.getString(R.string.log_downloading_file), newFile.getAbsolutePath()));
                    url = new URL(f_url[0] + entries.getString(i));
                    input = new BufferedInputStream(url.openStream(), 8192);
                    output = new FileOutputStream(newFile);

                    while ((count = input.read(data)) != -1) {
                        cumulativeBytes += count;
                        //publishProgress("" + (int) ((total * 100) / lengthOfFile));
                        output.write(data, 0, count);
                    }

                    output.flush();
                    output.close();
                    input.close();
                }
            } catch (IOException e) {
                logToast(String.format(res.getString(R.string.error_io_exception), e.getMessage()));
                cancel(true);
            } catch (JSONException e) {
                logToast(String.format(res.getString(R.string.error_json_exception), e.getMessage()));
                log(manifestSB.toString());
                cancel(true);
            } catch (SecurityException e) {
                logToast(String.format(res.getString(R.string.error_security_exception), e.getMessage()));
                cancel(true);
            }
            return null;
        }

        protected void onProgressUpdate(String... progress) {
            //pd.setProgress(Integer.parseInt(progress[0]));
        }

        @Override
        protected void onPostExecute(String file_url) {
            try {
                if (currentJoypadPath.exists()) {
                    deleteRecursively(currentJoypadPath);
                }
                if (!futureJoypadPath.renameTo(currentJoypadPath)) {
                    logToast(String.format(res.getString(R.string.error_could_not_rename), futureJoypadPath.getAbsolutePath()));
                    cancel(true);
                }
            } catch (IOException e) {
                logToast(String.format(res.getString(R.string.error_io_exception), e.getMessage()));
                cancel(true);
            }
            String url = "file://" + new File(currentJoypadPath, "main.html").toString();
            mTextView.setText(res.getString(R.string.toolbar_connected_to));
            wv.loadUrl(url);
            log(String.format(res.getString(R.string.log_loading_url), url));
        }

        @Override
        protected void onCancelled() {
            mTextView.setText(res.getString(R.string.toolbar_connected_to)); 
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

        mNsdManager = (NsdManager) getSystemService(Context.NSD_SERVICE);

        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);

        // Localization. TODO: detect NOTHING and MANUAL CONNECTION buttons by id, not by content.
        res = getResources();
        NOTHING = res.getString(R.string.dropdown_nothing);
        ENTER_IP = res.getString(R.string.dropdown_enter_ip);

        // Paths for layout (can't define them until Android context is established)
        currentJoypadPath = new File(getFilesDir(), "joypad");
        currentMainPath = new File(currentJoypadPath, "main.html");
        currentManifestPath = new File(currentJoypadPath, "manifest");
        futureJoypadPath = new File(getFilesDir(), "future");
        futureMainPath = new File(futureJoypadPath, "main.html");
        futureManifestPath = new File(futureJoypadPath, "manifest");

        // Filling spinner with addresses to connect to:
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
                            Toast.makeText(YokeActivity.this, res.getString(R.string.toast_invalid_address), Toast.LENGTH_LONG).show();

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
            logToast(String.format(res.getString(R.string.error_io_exception_while_sending), e.getMessage()));
        } catch (NullPointerException e) {
            logToast(String.format(res.getString(R.string.error_null_pointer_exception_while_sending), e.getMessage()));
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
                    log(String.format(res.getString(R.string.log_service_resolve_success), serviceInfo));

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
            Toast.makeText(YokeActivity.this, res.getString(R.string.toast_cant_reconnect_if_not_connected), Toast.LENGTH_LONG).show();
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
                new DownloadFilesFromURL().execute(
                    "http://" + host + ":" + port + "/"
                );
            });

        } catch (SocketException | UnknownHostException e) {
            mSocket = null;
            YokeActivity.this.runOnUiThread(() -> {
                mSpinner.setSelection(mAdapter.getPosition(NOTHING));
            });
            logToast(String.format(res.getString(R.string.error_open_udp_error), host, port));
        }
    }

    public void onDiscoveryStarted(String serviceType) {
        log(String.format(res.getString(R.string.log_discovery_started), serviceType));
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
        logToast(String.format(res.getString(R.string.error_discovery_failed), errorCode));
        mNsdManager.stopServiceDiscovery(this);
    }

    @Override
    public void onStopDiscoveryFailed(String serviceType, int errorCode) {
        logToast(String.format(res.getString(R.string.error_discovery_failed), errorCode));
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

