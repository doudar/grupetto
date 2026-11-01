package com.spop.poverlay.sensor.discovery;

import android.app.Service;
import android.content.Intent;
import android.os.Bundle;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.Message;
import android.os.Messenger;
import android.os.RemoteException;

/** Test APK only. Java avoids depending on the target APK's Kotlin runtime in this process. */
public class DiscoveryFixtureService extends Service {
    private final Messenger messenger = new Messenger(new Handler(Looper.getMainLooper(), request -> {
        if (request.what == 12) {
            String command = request.getData().getString("command");
            String hex = "TIME_OUT";
            if ("F5,41,36,F6".equals(command)) hex = "F1 41 03 30 30 30 C5 F6";
            if ("F5,44,39,F6".equals(command)) hex = "F1 44 05 35 30 35 32 30 36 F6";
            if ("F5,49,3E,F6".equals(command)) hex = "F1 49 03 31 33 30 D1 F6";
            Message response = Message.obtain(null, 14);
            Bundle data = new Bundle();
            data.putString("requestId", request.getData().getString("requestId"));
            data.putString("responseHexString", hex);
            response.setData(data);
            try { request.replyTo.send(response); } catch (RemoteException ignored) { }
        }
        return true;
    }));
    @Override public IBinder onBind(Intent intent) {
        if (!intent.hasCategory("com.peloton.sensor.category.BIKE") ||
            intent.hasCategory("com.peloton.sensor.category.FAKE_DATA") ||
            intent.hasCategory("com.peloton.sensor.category.AURORA")) return null;
        return messenger.getBinder();
    }
}
