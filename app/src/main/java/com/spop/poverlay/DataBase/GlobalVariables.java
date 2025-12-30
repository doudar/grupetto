package com.spop.poverlay.DataBase;

import android.content.Context;
import android.content.SharedPreferences;
import android.preference.PreferenceManager;
import com.spop.poverlay.DataBase.DBHelper;


public class GlobalVariables {

    Context context;

    SharedPreferences sharedPreferences;


    public GlobalVariables(Context c) {

        context = c;
        sharedPreferences = PreferenceManager.getDefaultSharedPreferences(context);


    }


    public String HRDeviceNameGet() {
        DBHelper dbHelper = new DBHelper(context);
        DBHelper.UserData  data = dbHelper.getUser(UserIDGet());

        String ret = data.getBleName();
        return ret;




    }

    public String CurrentUserNameGet() {
        DBHelper dbHelper = new DBHelper(context);
        DBHelper.UserData  data = dbHelper.getUser(UserIDGet());

        String ret = data.getUsername();
        return ret;
    }


    public String HRDeviceAddressGet() {

        DBHelper dbHelper = new DBHelper(context);
        DBHelper.UserData  data = dbHelper.getUser(UserIDGet());

        String ret = data.getBleId();
        return ret;
    }

    public int UserIDGet() {

        int ret = sharedPreferences.getInt("UserID", 0);
        return ret;
    }

    public void UserIDSet(int value) {

        SharedPreferences.Editor editor = sharedPreferences.edit();
        editor.putInt("UserID", value);
        // Use apply() for asynchronous saving or commit() for synchronous saving
        editor.commit();
    }



    public void HRDeviceAddressSet(String value) {

        DBHelper dbHelper = new DBHelper(context);
        dbHelper.updateHeartRateDeviceID( UserIDGet(),  value);


    }

    public void HRDeviceNameSet(String value) {

        DBHelper dbHelper = new DBHelper(context);
        dbHelper.updateHeartRateDeviceName( UserIDGet(),  value);
    }



}
