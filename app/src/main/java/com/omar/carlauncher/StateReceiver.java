package com.omar.carlauncher;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

public class StateReceiver extends BroadcastReceiver {
    @Override
    public void onReceive(Context context, Intent intent) {
        if (MainActivity.instance == null) return;
        String theme = intent.getStringExtra("theme");
        if (theme != null) MainActivity.instance.setTheme(theme);
        String hd = intent.getStringExtra("heading");
        if (hd != null) {
            try { MainActivity.instance.setHeadingTest(Float.parseFloat(hd)); } catch (Exception ignored) {}
        }
        String st = intent.getStringExtra("state");
        if (st != null) MainActivity.instance.forceState(st);
        if (intent.hasExtra("pack")) MainActivity.instance.startPack();
        if (intent.hasExtra("adhan")) MainActivity.instance.testAdhan();
        if (intent.hasExtra("iqama")) MainActivity.instance.testIqama();
    }
}
