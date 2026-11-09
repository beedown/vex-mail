package com.vexmail.app;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

/** Shows a reminder that VEX set for an event or a planned session. */
public class ReminderReceiver extends BroadcastReceiver {
    @Override
    public void onReceive(Context ctx, Intent intent) {
        String title = intent.getStringExtra("title"), text = intent.getStringExtra("text");
        Mail.notify(ctx, (int) (System.currentTimeMillis() % 100000), title == null ? "VEX" : title, text == null ? "" : text);
    }
}
