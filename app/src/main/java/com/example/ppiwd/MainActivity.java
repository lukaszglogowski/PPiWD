package com.example.ppiwd;


import android.app.Activity;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothManager;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.ServiceConnection;
import android.os.Bundle;
import android.os.IBinder;
import android.util.Log;
import android.view.View;
import android.widget.RadioButton;
import android.widget.RadioGroup;
import android.widget.Toast;


import com.mbientlab.metawear.MetaWearBoard;
import com.mbientlab.metawear.Route;
import com.mbientlab.metawear.Subscriber;
import com.mbientlab.metawear.android.BtleService;
import com.mbientlab.metawear.data.Acceleration;
import com.mbientlab.metawear.data.AngularVelocity;
import com.mbientlab.metawear.module.Accelerometer;
import com.mbientlab.metawear.module.Gyro;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.Arrays;

import bolts.Continuation;



/*   !!!!!!!!!!! WAŻNE !!!!!!!!!!!!!
* 1. Jeśli apka odmawia połączenia się z czujnikiem, trzeba wejść do ustawień
* Bluetooth w telefonie i sparować telefon z czujnikiem, problemy powinny ustąpić.
* 2. Po odkomentowaniu kodu w "writeToJson" apka będzie krzyczeć o braku pozoleń,
* trzeba wejść w ustawienia apki w ustawieniach telefonu i dać jej dostęp do pamięci. */



public class MainActivity extends Activity implements ServiceConnection {

    private BtleService.LocalBinder serviceBinder;
    //< Adres MAC urządzenia, żeby korzystać z własnego trzeba go zmienić
    private final String MW_MAC_ADDRESS= "E1:15:73:BF:22:D1";
    //< Deklaracje akcelerometru i żyroskopu
    private MetaWearBoard board;
    private Accelerometer accelerometer;
    private Gyro gyroscope;
    //< Deklaracje obiektów JSONa
    private JSONObject deviceData;
    private JSONArray dataArray;
    private JSONObject dataArray2;
    //< Przechowuje info czy któraś z opcji ćwiczeń jest wybrana
    private boolean checked = false;
    //< Deklaracje obiektów radioButton'ów
    private RadioGroup radioGroup;
    private RadioButton radioButton;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        View startButton = findViewById(R.id.start);
        View stopButton = findViewById(R.id.stop);
        View saveButton = findViewById(R.id.save);
        startButton.setEnabled(true);
        stopButton.setEnabled(false);
        saveButton.setEnabled(false);

        ///< Bind the service when the activity is created
        getApplicationContext().bindService(new Intent(this, BtleService.class),
                this, Context.BIND_AUTO_CREATE);


        //< Ten przycisk rozpoczyna pomiary
        startButton.setOnClickListener(view -> {
            startButton.setEnabled(false);
            stopButton.setEnabled(true);
            accelerometer.acceleration().start();
            gyroscope.angularVelocity().start();
            accelerometer.start();
            gyroscope.start();
        });
        //< Ten przycisk kończy pomiary
        stopButton.setOnClickListener(view -> {
            startButton.setEnabled(true);
            stopButton.setEnabled(false);
            saveButton.setEnabled(true);
            gyroscope.stop();
            accelerometer.stop();
            gyroscope.angularVelocity().stop();
            accelerometer.acceleration().stop();
        });
        //< Ten przycisk zapisuje pomiary do JSONa
        saveButton.setOnClickListener(view -> {
            // Próbowałem kombinować z prośbą o uprawnienia w trakcie działania aplikacji, MOŻE do tego wrócę
            // Context context = getApplicationContext();
            // if(ContextCompat.checkSelfPermission(context,
            //        Manifest.permission.MANAGE_EXTERNAL_STORAGE) == PackageManager.PERMISSION_GRANTED) {

            // Tutaj uzyskuje się dostep do wyboru ćwiczeń
            radioGroup = findViewById(R.id.radioGroup);
            int id = radioGroup.getCheckedRadioButtonId();
            radioButton = findViewById(id);

            /* Tutaj korzystamy z "checked" żeby sprawdzić czy użytkownik wybrał ćwicznie, jeśli
            * tak to dajemy jego nazwę do JSONa, jako "type", jeśli nie dajemy "Unspecified" */
            try {
                if (checked) {
                    deviceData.put("type", radioButton.getText());
                } else {
                    deviceData.put("type", "Unspecified");
                }
                writeToJson();
            } catch (JSONException e) {
                throw new RuntimeException(e);
            }

            // } else {
            //    ActivityCompat.requestPermissions(MainActivity.this,
            //            new String[]{Manifest.permission.MANAGE_EXTERNAL_STORAGE}, 1);
            // }
        });
    }

    @Override
    public void onDestroy() {
        super.onDestroy();

        ///< Unbind the service when the activity is destroyed
        getApplicationContext().unbindService(this);
    }

    @Override
    public void onServiceConnected(ComponentName componentName, IBinder service) {
        ///< Typecast the binder to the service's LocalBinder class
        serviceBinder = (BtleService.LocalBinder) service;

        Log.i("ppiwd", "Service Connected");

        retrieveBoard(MW_MAC_ADDRESS);
    }

    @Override
    public void onServiceDisconnected(ComponentName componentName) {

    }

    //< Główna część apki, pobiera dane z czujnika i zapisuje do JSONa
    public void retrieveBoard(String macAdress) {
        final BluetoothManager btManager=
                (BluetoothManager) getSystemService(Context.BLUETOOTH_SERVICE);
        final BluetoothDevice remoteDevice=
                btManager.getAdapter().getRemoteDevice(macAdress);

        //< Inicjalizacja obiektów do JSONa
        deviceData = new JSONObject();
        dataArray = new JSONArray();
        dataArray2 = new JSONObject();

        //< Adres MAC czujnika do JSONa
        try {
            deviceData.put("device", MW_MAC_ADDRESS);
        } catch (JSONException e) {
            e.printStackTrace();
        }

        // Create a MetaWear board object for the Bluetooth Device
        board = serviceBinder.getMetaWearBoard(remoteDevice);

        board.connectAsync().onSuccessTask(task -> {
            //< Sprawdzanie czy telefon i czujnik się połączyły
            if (task.isFaulted()) {
                Log.i("ppiwd", "Failed to connect");
            } else {
                Log.i("ppiwd", "Connected to " + macAdress);
            }

            //< Zbieramy dane z akcelerometru
            accelerometer = board.getModule(Accelerometer.class);
            accelerometer.configure()
                    .odr(50f)       // Set sampling frequency to 50Hz, or closest valid ODR
                    .commit();
            return accelerometer.acceleration().addRouteAsync(source -> source.stream((Subscriber) (data, env) -> {
                //< Odkomentować jeśli chce się zobaczyć dane Accel w Logcat
                //Log.i("ppiwd", "Accel: " + data.value(Acceleration.class).toString());

                try {
                    Acceleration accel = data.value(Acceleration.class);
                    float[] accelData = new float[]{accel.x(), accel.y(), accel.z()};
                    dataArray2 = new JSONObject();

                    long tsLong = System.currentTimeMillis();
                    StringBuilder tsb = new StringBuilder(Long.toString(tsLong));
                    tsb = tsb.insert(tsb.length() - 3, '.');
                    String ts = tsb.toString();

                    //< Dane z timestamp i Accel do JSONa
                    // Accel trochę się tnie i nie ma go w pierwszych 2-3 obiektach, potem ok
                    dataArray2.put("timestamp", ts);
                    dataArray2.put("accel", Arrays.toString(accelData));
                } catch (Exception e) {
                    e.printStackTrace();
                }
            }));
        }).continueWith((Continuation<Route, Void>) task -> {
            //< Sprawdzenie czy apka jest skonfigurowana, wykonuje się przed akceleratorem
            if (task.isFaulted()) {
                Log.w("ppiwd", "Failed to configure app", task.getError());
                Toast.makeText(MainActivity.this,
                        "Error while configuring MetaWear device", Toast.LENGTH_LONG).show();
            } else {
                Log.i("ppiwd", "App configured");
                findViewById(R.id.start).setEnabled(true);
            }

            return null;
        }).continueWith(task -> {
            //< Zbieramy dane z żyroskopu
            gyroscope = board.getModule(Gyro.class);
            gyroscope.configure()
                    .odr(Gyro.OutputDataRate.ODR_50_HZ)
                    .commit();
            return gyroscope.angularVelocity().addRouteAsync(routeComponent -> routeComponent.stream((Subscriber) (data, env) -> {
                //< Odkomentować jeśli chce się zobaczyć dane Gyro w Logcat
                //Log.i("ppiwd", "Gyro:" + data.value(AngularVelocity.class).toString());

                try {
                    //< Dane z Gyro do JSONa
                    AngularVelocity gyro = data.value(AngularVelocity.class);
                    float[] gyroData = {gyro.x(), gyro.y(), gyro.z()};

                    dataArray2.put("gyro", Arrays.toString(gyroData));
                    dataArray.put(dataArray2);
                    deviceData.put("data", dataArray);
                } catch (Exception e) {
                    e.printStackTrace();
                }

                //< Skończony JSON w pełnej glorii i chwale, można sprawdzić w Logcat
                //Log.i("ppiwd", deviceData.toString());
            }));
        });
    }

    /*< Zapisywanie JSONa do pamięci telefonu, pliki można znaleźć w pamięci
    * wenętrznej, Android -> data -> com.example.ppiwd -> files -> MetaWearData */
    public void writeToJson() throws JSONException {
        //< default'owa nazwa JSONa
        String filename = "data.json";

        //< Sprawdzamy czy użytkownik zaznaczył jakieś ćwiczenie
        if (checked) filename = uniqueTimestamp() + ".json";

        //< Przesył JSONa z pamięci wewnętrznej apki do pamięci zewnętrznej telefonu
        String fileContents;
        try {
            fileContents = deviceData.toString(4);
        } catch (JSONException ignored) {
            fileContents = deviceData.toString();
        }

        dataArray = new JSONArray();
        FileOutputStream outputStream;

        File sourceLocation = new File(MainActivity.this.getFilesDir(), filename);
        File sdCard = getExternalFilesDir("MetaWearData");
        File targetLocation = new File(sdCard, filename);

        Log.v("ppiwd", "sourceLocation: " + sourceLocation);
        Log.v("ppiwd", "targetLocation: " + targetLocation);

        //< Zapis JSONa do pamięci wewnętrznej apki
        try {
            outputStream = openFileOutput(filename, Context.MODE_PRIVATE);
            outputStream.write(fileContents.getBytes());
            outputStream.close();
            Log.i("ppiwd", "File saved: " + filename);

            InputStream in = new FileInputStream(sourceLocation);
            OutputStream out = new FileOutputStream(targetLocation);

            // Copy the bits from instream to outstream
            byte[] buf = new byte[1024];
            int len;

            while ((len = in.read(buf)) > 0) {
                out.write(buf, 0, len);
            }

            in.close();
            out.close();
        } catch (IOException e) {
            e.printStackTrace();
        }
    }

    //< Wyciągamy 4 timestamp z JSONa na potrzeby jego unikalnej nazwy
    public String uniqueTimestamp() throws JSONException {
        JSONArray data = deviceData.getJSONArray("data");

        JSONObject object = data.getJSONObject(3);

        String timestamp = object.getString("timestamp");

        StringBuilder stringBuilder = new StringBuilder(timestamp);

        stringBuilder.setCharAt(timestamp.length() - 4, '_');

        return String.valueOf(stringBuilder);
    }

    //< Funkcja sprawdzająca czy użytkownik zaznaczył jakąś aktywość
    public void onRadioButtonClicked(View view) {
        checked = ((RadioButton) view).isChecked();
    }
}

