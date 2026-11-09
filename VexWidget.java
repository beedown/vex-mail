package com.vexmail.app;

import android.app.PendingIntent;
import android.appwidget.AppWidgetManager;
import android.appwidget.AppWidgetProvider;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.widget.RemoteViews;

import org.json.JSONObject;

/** The home screen widget: unread count, who's waiting on you, and what's next. */
public class VexWidget extends AppWidgetProvider {
    @Override
    public void onUpdate(Context ctx, AppWidgetManager mgr, int[] ids) {
        render(ctx, mgr, ids);
    }

    static void refresh(Context ctx) {
        AppWidgetManager mgr = AppWidgetManager.getInstance(ctx);
        int[] ids = mgr.getAppWidgetIds(new ComponentName(ctx, VexWidget.class));
        if (ids != null && ids.length > 0) render(ctx, mgr, ids);
    }

    static void setUnread(Context ctx, int unread) {
        try {
            SharedPreferences p = Mail.prefs(ctx);
            JSONObject w = new JSONObject(p.getString("widget", "{}"));
            w.put("unread", unread);
            p.edit().putString("widget", w.toString()).apply();
            refresh(ctx);
        } catch (Exception ignored) {
        }
    }

    static void render(Context ctx, AppWidgetManager mgr, int[] ids) {
        JSONObject w;
        try {
            w = new JSONObject(Mail.prefs(ctx).getString("widget", "{}"));
        } catch (Exception e) {
            w = new JSONObject();
        }
        int unread = w.optInt("unread", 0);
        RemoteViews v = new RemoteViews(ctx.getPackageName(), R.layout.vex_widget);
        v.setTextViewText(R.id.w_count, String.valueOf(unread));
        v.setTextViewText(R.id.w_label, unread == 1 ? "unread email" : "unread emails");
        v.setTextViewText(R.id.w_line1, w.optString("needs", "Nobody is waiting on you"));
        v.setTextViewText(R.id.w_line2, w.optString("next", "Nothing else planned today"));
        Intent open = new Intent(ctx, MainActivity.class);
        v.setOnClickPendingIntent(R.id.w_root, PendingIntent.getActivity(ctx, 9, open, PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE));
        mgr.updateAppWidget(ids, v);
    }
}
