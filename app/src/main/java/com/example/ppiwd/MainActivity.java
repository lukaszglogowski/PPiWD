package com.example.ppiwd;


import android.Manifest;
import android.app.Activity;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothManager;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.ServiceConnection;
import android.os.Bundle;
import android.os.Environment;
import android.os.IBinder;
import android.util.Log;
import android.view.View;



import com.mbientlab.metawear.Data;
import com.mbientlab.metawear.MetaWearBoard;
import com.mbientlab.metawear.Route;
import com.mbientlab.metawear.Subscriber;
import com.mbientlab.metawear.android.BtleService;
import com.mbientlab.metawear.builder.RouteBuilder;
import com.mbientlab.metawear.builder.RouteComponent;
import com.mbientlab.metawear.data.Acceleration;
import com.mbientlab.metawear.data.AngularVelocity;
import com.mbientlab.metawear.module.Accelerometer;
import com.mbientlab.metawear.module.GyroBmi160;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;

import bolts.Continuation;
import bolts.Task;


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
    private GyroBmi160 gyroBmi160;
    //< Deklaracje obiektów JSONa
    private JSONObject deviceData;
    private JSONArray dataArray;
    private JSONObject dataArray2;



    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        ///< Bind the service when the activity is created
        getApplicationContext().bindService(new Intent(this, BtleService.class),
                this, Context.BIND_AUTO_CREATE);

        //< Ten przycisk rozpoczyna pomiary
        findViewById(R.id.start).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View view) {
                accelerometer.acceleration().start();
                gyroBmi160.angularVelocity().start();
                accelerometer.start();
                gyroBmi160.start();
            }
        });
        //< Ten przycisk kończy pomiary
        findViewById(R.id.stop).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View view) {
                gyroBmi160.stop();
                accelerometer.stop();
                gyroBmi160.angularVelocity().stop();
                accelerometer.acceleration().stop();
            }
        });
        //< Ten przycisk zapisuje pomiary do JSONa
        findViewById(R.id.save).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View view) {
                writeToJson();
            }
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

        board.connectAsync().onSuccessTask(new Continuation<Void, Task<Route>>() {
            @Override
            public Task<Route> then(Task<Void> task) throws Exception {
                //< Sprawdzanie czy telefon i czujnik się połączyły
                if (task.isFaulted()) {
                    Log.i("ppiwd", "Failed to connect");
                } else {
                    Log.i("ppiwd", "Connected to " + macAdress);
                }

                //< Zostawiłem 25Hz bo to dane i tak szybko lecą, można zmienić
                accelerometer = board.getModule(Accelerometer.class);
                accelerometer.configure()
                        .odr(25f)       // Set sampling frequency to 25Hz, or closest valid ODR
                        .commit();
                return accelerometer.acceleration().addRouteAsync(new RouteBuilder() {
                    @Override
                    public void configure(RouteComponent source) {
                        source.stream(new Subscriber() {
                            @Override
                            public void apply(Data data, Object... env) {
                                //< Odkomentować jeśli chce się zobaczyć dane Accel w Logcat
                                //Log.i("ppiwd", "Accel: " + data.value(Acceleration.class).toString());

                                try {
                                    JSONArray accel = new JSONArray();
                                    accel.put(data.value(Acceleration.class).toString());

                                    //< Dane z Accel do JSONa
                                    // Accel trochę się tnie i nie ma go w pierwszych 2-3 obiektach, potem ok
                                    dataArray2.put("accel", accel);
                                } catch (Exception e) {
                                    e.printStackTrace();
                                }
                            }
                        });
                    }
                });
            }
        }).continueWith(new Continuation<Route, Void>() {
            @Override
            public Void then(Task<Route> task) throws Exception {
                if (task.isFaulted()) {
                    Log.w("ppiwd", "Failed to configure app", task.getError());
                } else {
                    Log.i("ppiwd", "App configured");
                }

                return null;
            }
        }).continueWith(new Continuation<Void, Task<Route>>() {
            @Override
            public Task<Route> then(Task<Void> task) throws Exception {
                //< Tutaj też zostawiłem 25Hz, zmienić na ODR_50_HZ albo coś innego
                gyroBmi160 = board.getModule(GyroBmi160.class);
                gyroBmi160.configure()
                        .odr(GyroBmi160.OutputDataRate.ODR_25_HZ)
                        .commit();
                return gyroBmi160.angularVelocity().addRouteAsync(new RouteBuilder() {
                    @Override
                    public void configure(RouteComponent routeComponent) {
                        routeComponent.stream(new Subscriber() {
                            @Override
                            public void apply(Data data, Object... env) {
                                //< Odkomentować jeśli chce się zobaczyć dane Gyro w Logcat
                                //Log.i("ppiwd", "Gyro:" + data.value(AngularVelocity.class).toString());

                                try {
                                    Long tsLong = System.currentTimeMillis();
                                    String ts = tsLong.toString();
                                    JSONArray gyro = new JSONArray();

                                    //< Dane z timestamp i Gyro do JSONa
                                    gyro.put(data.value(AngularVelocity.class).toString());
                                    dataArray2.put("timestamp", ts);
                                    dataArray2.put("gyro", gyro);
                                    dataArray.put(dataArray2);
                                    deviceData.put("data", dataArray);
                                } catch (Exception e) {
                                    e.printStackTrace();
                                }

                                //< Skończony JSON w pełnej glorii i chwale, w Logcat
                                Log.i("ppiwd", deviceData.toString());
                            }
                        });
                    }
                });
            }
        });
    }

    /*< Zapisywanie JSONa do pamięci telefonu, niestety z automatu idzie do wewnętrznej
    pamięci aplikacji i nie da się go tam podejrzeć ani z poziomu telefonu dostać do niego.
    Zakomentowany kod kopiuje plik do pamięci zewnętrzej telefonu ale coś jest z nim nie
    tak, bo ciągle powtaża się pierwszy obiekt. */
    public void writeToJson() {
        final String filename = "data.json";
        String fileContents = deviceData.toString();
        FileOutputStream outputStream;

        /*File sourceLocation = new File("/data/data/com.example.ppiwd/files/data.json");
        String sdCard = Environment.getExternalStorageDirectory().getAbsolutePath();
        File targetLocation = new File(sdCard, "data.json");

        Log.v("ppiwd", "sourceLocation: " + sourceLocation);
        Log.v("ppiwd", "targetLocation: " + targetLocation);*/

        //< Zapis JSONa do pamięci wewnętrznej apki
        try {
            outputStream = openFileOutput(filename, Context.MODE_PRIVATE);
            outputStream.write(fileContents.getBytes());
            outputStream.close();
            Log.i("ppiwd", "File saved: " + filename);

            /*InputStream in = new FileInputStream(sourceLocation);
            OutputStream out = new FileOutputStream(targetLocation);

            // Copy the bits from instream to outstream
            byte[] buf = new byte[1024];
            int len;

            while ((len = in.read(buf)) > 0) {
                out.write(buf, 0, len);
            }

            in.close();
            out.close();*/
        } catch (IOException e) {
            e.printStackTrace();
        }
    }
}

