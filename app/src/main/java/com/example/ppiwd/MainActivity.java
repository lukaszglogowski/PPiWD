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
import com.mbientlab.metawear.module.Gyro;
import com.mbientlab.metawear.module.GyroBmi160;

import bolts.Continuation;
import bolts.Task;

public class MainActivity extends Activity implements ServiceConnection {

    private BtleService.LocalBinder serviceBinder;
    ///< Address MAC of your device
    private final String MW_MAC_ADDRESS= "E1:15:73:BF:22:D1";
    private MetaWearBoard board;
    private Accelerometer accelerometer;
    private GyroBmi160 gyroBmi160;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        ///< Bind the service when the activity is created
        getApplicationContext().bindService(new Intent(this, BtleService.class),
                this, Context.BIND_AUTO_CREATE);

        findViewById(R.id.start).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View view) {
                accelerometer.acceleration().start();
                gyroBmi160.angularVelocity().start();
                accelerometer.start();
                gyroBmi160.start();
            }
        });
        findViewById(R.id.stop).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View view) {
                gyroBmi160.stop();
                accelerometer.stop();
                gyroBmi160.angularVelocity().stop();
                accelerometer.acceleration().stop();
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

    public void retrieveBoard(String macAdress) {
        final BluetoothManager btManager=
                (BluetoothManager) getSystemService(Context.BLUETOOTH_SERVICE);
        final BluetoothDevice remoteDevice=
                btManager.getAdapter().getRemoteDevice(macAdress);

        // Create a MetaWear board object for the Bluetooth Device
        board = serviceBinder.getMetaWearBoard(remoteDevice);

        board.connectAsync().onSuccessTask(new Continuation<Void, Task<Route>>() {
            @Override
            public Task<Route> then(Task<Void> task) throws Exception {
                if (task.isFaulted()) {
                    Log.i("ppiwd", "Failed to connect");
                } else {
                    Log.i("ppiwd", "Connected to " + macAdress);
                }

                accelerometer = board.getModule(Accelerometer.class);
                accelerometer.configure()
                        .odr(50f)       // Set sampling frequency to 25Hz, or closest valid ODR
                        .commit();
                return accelerometer.acceleration().addRouteAsync(new RouteBuilder() {
                    @Override
                    public void configure(RouteComponent source) {
                        source.stream(new Subscriber() {
                            @Override
                            public void apply(Data data, Object... env) {
                                Log.i("ppiwd", "Acce: " + data.value(Acceleration.class).toString());
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
                gyroBmi160 = board.getModule(GyroBmi160.class);
                gyroBmi160.configure()
                        .odr(GyroBmi160.OutputDataRate.ODR_50_HZ)
                        .commit();
                return gyroBmi160.angularVelocity().addRouteAsync(new RouteBuilder() {
                    @Override
                    public void configure(RouteComponent routeComponent) {
                        routeComponent.stream(new Subscriber() {
                            @Override
                            public void apply(Data data, Object... env) {
                                Log.i("ppiwd", "Gyro:" + data.value(AngularVelocity.class).toString());
                            }
                        });
                    }
                });
            }
        });

        // THIS SHOULD ALWAYS STAY COMMENTED
        /*board.disconnectAsync().continueWith(new Continuation<Void, Void>() {
            @Override
            public Void then(Task<Void> task) throws Exception {
                Log.i("ppiwd", "Disconnected");
                return null;
            }
        });*/
    }
}