package com.vexmail.app;

import android.app.job.JobParameters;
import android.app.job.JobService;
import android.content.Context;
import android.content.SharedPreferences;

import com.sun.mail.imap.IMAPFolder;

import org.json.JSONArray;
import org.json.JSONObject;

import javax.mail.Flags;
import javax.mail.Folder;
import javax.mail.Message;
import javax.mail.Store;
import javax.mail.UIDFolder;

/** Runs about every 15 minutes, even when VEX is closed: checks for new mail and keeps reminders set. */
public class MailCheckJob extends JobService {
    @Override
    public boolean onStartJob(final JobParameters params) {
        new Thread(() -> {
            try {
                check(getApplicationContext());
            } catch (Exception ignored) {
            }
            jobFinished(params, false);
        }).start();
        return true;
    }

    @Override
    public boolean onStopJob(JobParameters params) {
        return true;
    }

    static void check(Context ctx) throws Exception {
        SharedPreferences p = Mail.prefs(ctx);
        JSONObject cfg = new JSONObject(p.getString("notify", "{}"));
        try {
            Mail.scheduleReminders(ctx, new JSONArray(p.getString("reminders", "[]")));
        } catch (Exception ignored) {
        }
        long now = System.currentTimeMillis();
        boolean quiet = Mail.inQuietHours(cfg, now), focus = Mail.inFocus(cfg, now);
        boolean onlyImportant = cfg.optBoolean("onlyImportant") || focus;
        JSONArray important = cfg.optJSONArray("important");
        JSONArray list = Mail.accounts(ctx);
        int unread = 0;
        for (int i = 0; i < list.length(); i++) {
            JSONObject c = list.getJSONObject(i);
            Store store = null;
            try {
                store = Mail.openStore(ctx, c);
                IMAPFolder inbox = (IMAPFolder) store.getFolder("INBOX");
                inbox.open(Folder.READ_ONLY);
                unread += Math.max(0, inbox.getUnreadMessageCount());
                long next = inbox.getUIDNext();
                String key = "lastUid_" + c.optString("id");
                long last = p.getLong(key, -1);
                if (last >= 0 && next - 1 > last && cfg.optBoolean("on") && !quiet && !MainActivity.foreground) {
                    Message[] msgs = inbox.getMessagesByUID(last + 1, UIDFolder.LASTUID);
                    int shown = 0;
                    for (Message m : msgs) {
                        if (m == null || m.isSet(Flags.Flag.SEEN)) continue;
                        String[] who = Mail.person(m.getFrom());
                        if (onlyImportant && !Mail.listed(important, who[1])) continue;
                        if (shown++ >= 5) break;
                        Mail.notify(ctx, (int) (inbox.getUID(m) % 100000) + 200000 * (i + 1), who[0], m.getSubject() == null ? "(no subject)" : m.getSubject());
                    }
                }
                if (next > 0) p.edit().putLong(key, next - 1).apply();
                inbox.close(false);
            } catch (Exception ignored) {
            } finally {
                if (store != null) {
                    try {
                        store.close();
                    } catch (Exception ignored) {
                    }
                }
            }
        }
        VexWidget.setUnread(ctx, unread);
    }
}
