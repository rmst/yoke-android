package com.simonramstedt.yoke;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
// import android.content.SharedPreferences;
import android.content.res.Resources;
import android.os.Bundle;
import android.os.Environment;
import android.util.Log;
import android.view.View;
import android.widget.TextView;

import java.io.File;


public class IntroActivity extends Activity {
    // private SharedPreferences sharedPref;
    private TextView introWelcome;
    private TextView introNotice;
    private String storageState;
    private Resources res;
    private Intent mIntent;
    private String externalPath;
    private File layoutMain;

    @Override
    public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.intro);
        // TODO: Only display welcome message the first time the application is run
        // sharedPref = getPreferences(Context.MODE_PRIVATE);

        res = getResources();
        mIntent = new Intent(getApplicationContext(), YokeActivity.class);
        externalPath = getExternalFilesDir(null).toString();
        layoutMain = new File(getExternalFilesDir(null), "joypad/main.html");
        introWelcome = (TextView) findViewById(R.id.introWelcome);
        introNotice = (TextView) findViewById(R.id.introNotice);
        introWelcome.setText(String.format(res.getString(R.string.intro_welcome), res.getString(R.string.app_name)));
        // The button is autoclicked the first time.
        // This avoids repeating code and also saves the user from the greeter screen if all checks are passed.
        onward(null);
    }

    public void onward(View view) {
        // Checking for availability of external storage:
        storageState = Environment.getExternalStorageState();
        if (storageState.equals(Environment.MEDIA_MOUNTED) ||
            storageState.equals(Environment.MEDIA_MOUNTED_READ_ONLY)) {
            if (layoutMain.exists()) {
                startActivity(mIntent);
            } else {
                if (storageState.equals(Environment.MEDIA_MOUNTED_READ_ONLY)) {
                    introNotice.setText(String.format(res.getString(R.string.intro_readonly_storage), externalPath));
                } else {
                    introNotice.setText(String.format(res.getString(R.string.intro_create_files), externalPath));
                }
            }
        } else {
            introNotice.setText(res.getString(R.string.intro_unavailable_storage));
        }
    }
}
