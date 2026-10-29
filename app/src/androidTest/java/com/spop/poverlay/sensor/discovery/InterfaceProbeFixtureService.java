package com.spop.poverlay.sensor.discovery;

import android.app.Service;
import android.content.Intent;
import android.os.Binder;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.Parcel;
import android.os.RemoteException;
import java.util.concurrent.atomic.AtomicInteger;

/** Separate test APK process. The service/action names intentionally differ from Peloton's. */
public class InterfaceProbeFixtureService extends Service {
    private static final String V1 = "com.onepeloton.affernetservice.IV1Interface";
    private static final String PLUS = "com.onepeloton.affernetservice.IBikeInterface";
    private static final AtomicInteger unknownTransactions = new AtomicInteger();

    private static void writeSample(Parcel parcel) {
        parcel.writeInt(1);
        parcel.writeLong(0); // rpm
        parcel.writeLong(123450); // hundredths of a watt
        parcel.writeLong(0);
        parcel.writeLong(0);
        parcel.writeInt(31);
        parcel.writeInt(31);
    }

    @Override public IBinder onBind(Intent intent) {
        String action = intent.getAction();
        String descriptor = ("vendor.cycle.V1".equals(action) || "vendor.cycle.SILENT".equals(action)) ? V1 :
            "vendor.cycle.PLUS".equals(action) ? PLUS :
            "vendor.cycle.MESSENGER".equals(action) ? "android.os.IMessenger" : "vendor.cycle.UnknownInterface";
        return new Binder() {
            { attachInterface(null, descriptor); }
            @Override protected boolean onTransact(int code, Parcel data, Parcel reply, int flags) throws RemoteException {
                if (code == INTERFACE_TRANSACTION) return super.onTransact(code, data, reply, flags);
                if (code == 999) { reply.writeNoException(); reply.writeInt(unknownTransactions.get()); return true; }
                if (V1.equals(descriptor) && (code == 1 || code == 2)) {
                    data.enforceInterface(V1);
                    IBinder callback = data.readStrongBinder();
                    data.readString();
                    reply.writeNoException();
                    if (code == 1 && !"vendor.cycle.SILENT".equals(action)) new Handler(Looper.getMainLooper()).postDelayed(() -> {
                        Parcel event = Parcel.obtain();
                        try {
                            event.writeInterfaceToken("com.onepeloton.affernetservice.IV1Callback");
                            writeSample(event);
                            callback.transact(1, event, null, IBinder.FLAG_ONEWAY);
                        } catch (RemoteException ignored) { }
                        finally { event.recycle(); }
                    }, 100);
                    return true;
                }
                if (PLUS.equals(descriptor) && code == 14) {
                    data.enforceInterface(PLUS);
                    reply.writeNoException();
                    writeSample(reply);
                    return true;
                }
                unknownTransactions.incrementAndGet();
                return false;
            }
        };
    }
}
