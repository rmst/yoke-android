package com.simonramstedt.yoke;

import android.app.Activity;
import android.content.Context;
import android.content.SharedPreferences;
import android.hardware.Sensor;
import android.hardware.SensorEvent;
import android.hardware.SensorEventListener;
import android.hardware.SensorManager;
import android.net.nsd.NsdManager;
import android.os.Bundle;
import android.os.Handler;
import android.os.PowerManager;
import android.os.PowerManager.WakeLock;
import android.util.Log;
import android.view.Display;
import android.view.View;
import android.view.WindowManager;
import android.widget.AdapterView;
import android.widget.ArrayAdapter;
import android.net.nsd.NsdServiceInfo;
import android.widget.Spinner;
import android.widget.Switch;
import android.widget.TextView;

import java.io.IOException;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.SocketException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Timer;
import java.util.concurrent.locks.ReentrantLock;


public class YokeActivity extends Activity implements SensorEventListener, NsdManager.DiscoveryListener {

    private static final String SERVICE_TYPE = "_yoke._udp.";
    private SensorManager mSensorManager;
    private PowerManager mPowerManager;
    private WindowManager mWindowManager;
    private Display mDisplay;
    private WakeLock mWakeLock;
    private Sensor mAccelerometer;
    private ServerSocket mServerSocket;
    private NsdManager mNsdManager;
    private NsdServiceInfo mNsdServiceInfo;
    private NsdServiceInfo mService;
    private final ReentrantLock resolving = new ReentrantLock();
    private DatagramSocket mSocket;
    private float[] vals = {0, 0, 0, 0, 0, 0, 0};
    private Timer mTimer;
    private Map<String, NsdServiceInfo> mServiceMap = new HashMap<>();
    private List<String> mServiceNames = new ArrayList<>();
    private SharedPreferences sharedPref;
    private TextView mTextView;
    private Spinner mSpinner;
    private ArrayAdapter<String> mAdapter;
    private String mTarget = "";
    private JoystickView joystick1;
    private Handler handler;
    private JoystickView joystick2;

    private void log(String m) {
        if(BuildConfig.DEBUG)
            Log.d("Yoke", m);
    }

    @Override
    public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.main);

        // Get an instance of the SensorManager
        mSensorManager = (SensorManager) getSystemService(SENSOR_SERVICE);

        // Get an instance of the PowerManager
        mPowerManager = (PowerManager) getSystemService(POWER_SERVICE);

        // Get an instance of the WindowManager
        mWindowManager = (WindowManager) getSystemService(WINDOW_SERVICE);
        mDisplay = mWindowManager.getDefaultDisplay();

        mNsdManager = (NsdManager) getSystemService(Context.NSD_SERVICE);

        mAccelerometer = mSensorManager.getDefaultSensor(Sensor.TYPE_GRAVITY);

        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);

        joystick1 = (JoystickView) findViewById(R.id.joystickView1);
        joystick2 = (JoystickView) findViewById(R.id.joystickView2);

        mTextView = (TextView) findViewById(R.id.textView);

        mSpinner = (Spinner) findViewById(R.id.spinner);
        mAdapter = new ArrayAdapter<>(getApplicationContext(), android.R.layout.simple_spinner_item);
        mAdapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        mAdapter.add("nothing");
        mSpinner.setAdapter(mAdapter);
        mSpinner.setPrompt("Connect to ...");
        mSpinner.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            @Override
            public void onItemSelected(AdapterView<?> parent, View view, int pos, long l) {
                String tgt = parent.getItemAtPosition(pos).toString();
                if(!mServiceNames.contains(mTarget) && !mTarget.equals("nothing")) {
                    mAdapter.remove(mTarget);
                    if(tgt.equals(mTarget))
                        tgt = "nothing";
                }
                mTarget = tgt;
                log("new target " + mTarget);
                SharedPreferences.Editor editor = sharedPref.edit();
                editor.putString("target", mTarget);
                editor.apply();

                closeConnection();

                onServiceNameChange();
            }

            @Override
            public void onNothingSelected(AdapterView<?> adapterView) {
                log("nothing selected");
            }
        });

        ((Switch) findViewById(R.id.switch1)).setOnCheckedChangeListener((compoundButton, b) -> joystick1.setFixed(b));

        ((Switch) findViewById(R.id.switch2)).setOnCheckedChangeListener((compoundButton, b) -> joystick2.setFixed(b));

        sharedPref = getPreferences(Context.MODE_PRIVATE);
        mTarget = sharedPref.getString("target", "");

    }

    @Override
    protected void onResume() {
        super.onResume();

        mSensorManager.registerListener(this, mAccelerometer, SensorManager.SENSOR_DELAY_UI);

        mNsdManager.discoverServices(SERVICE_TYPE, NsdManager.PROTOCOL_DNS_SD, this);

        handler = new Handler();
        handler.post(new Runnable() {

            @Override
            public void run() {
                float[] p1 = joystick1.getRelPos();
                float[] p2 = joystick2.getRelPos();
                vals[3] = p1[0];
                vals[4] = p1[1];
                vals[5] = p2[0];
                vals[6] = p2[1];
                update();

                if(handler != null)
                    handler.postDelayed(this, 20);

            }
        });

    }

    @Override
    protected void onPause() {
        super.onPause();

        mSensorManager.unregisterListener(this);

        mNsdManager.stopServiceDiscovery(this);

        closeConnection();

        handler = null;
    }

    @Override
    public void onSensorChanged(SensorEvent event) {
        if (event.sensor.getType() != Sensor.TYPE_GRAVITY)
            return;

        vals[0] = event.values[0];
        vals[1] = event.values[1];
        vals[2] = event.values[2];

        update();

    }


    @Override
    public void onAccuracyChanged(Sensor sensor, int i) {

    }

    private void update(){
        if(mSocket != null){
            StringBuilder s = new StringBuilder();
            for(float v : vals){
                s.append(" ");
                s.append(String.valueOf(v));
            }
            send(s.toString().getBytes());
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


    public void onServiceNameChange(){
        if(mService == null && mServiceMap.containsKey(mTarget)){
            NsdServiceInfo service = mServiceMap.get(mTarget);
            log("Resolving Service: " + service.getServiceType());
            mNsdManager.resolveService(service, new NsdManager.ResolveListener() {
                @Override
                public void onResolveFailed(NsdServiceInfo serviceInfo, int errorCode) {
                    log("Resolve failed: " + errorCode);
                }

                @Override
                public void onServiceResolved(NsdServiceInfo serviceInfo) {
                    // check name again (could have changed in the mean time)
                    if(mTarget.equals(serviceInfo.getServiceName())){
                        log("Resolve Succeeded. " + serviceInfo);

                        mService = serviceInfo;
                        int port = mService.getPort();
                        InetAddress host = mService.getHost();
                        log(host.getHostAddress());

                        try {
                            mSocket = new DatagramSocket(0);
                            mSocket.connect(host, port);

                            log("Connected");
                            YokeActivity.this.runOnUiThread(() -> mTextView.setText("Connected to"));

                        } catch (SocketException e) {
                            e.printStackTrace();
                        }
                    }
                }
            });
        } else if (mService != null && !mServiceMap.containsKey(mTarget)){
            if(!mServiceMap.containsKey(mService.getServiceName())) {
                log("Connection with " + mService.getServiceName() + " lost.");
            }

            closeConnection();
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
            if(mTarget.equals(service.getServiceName()))
                return;
            mAdapter.add(service.getServiceName());
            onServiceNameChange();
        });

    }



    @Override
    public void onServiceLost(NsdServiceInfo service) {
        log("Service lost " + service);
        mServiceMap.remove(service.getServiceName());
        mServiceNames.remove(service.getServiceName());
        this.runOnUiThread(() -> {
            if(mTarget.equals(service.getServiceName()))
                return;

            mAdapter.remove(service.getServiceName());
            onServiceNameChange();
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
        if(mSocket != null){
            log("Connection closed");
            mTextView.setText(" Connect to ");
            mSocket.close();
            mSocket = null;
        }
    }
}