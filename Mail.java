package com.vexmail.app;

import android.app.AlarmManager;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.job.JobInfo;
import android.app.job.JobScheduler;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.text.Html;
import android.util.Base64;

import com.sun.mail.imap.IMAPFolder;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.util.Calendar;
import java.util.Date;
import java.util.List;
import java.util.Properties;

import javax.mail.Address;
import javax.mail.AuthenticationFailedException;
import javax.mail.Flags;
import javax.mail.Folder;
import javax.mail.Message;
import javax.mail.MessagingException;
import javax.mail.Multipart;
import javax.mail.Part;
import javax.mail.Session;
import javax.mail.Store;
import javax.mail.internet.InternetAddress;
import javax.mail.internet.MimeUtility;

/** Everything VEX needs to talk to mail servers, shared by the app and the background mail check. */
final class Mail {
    static final String MS_AUTH = "https://login.microsoftonline.com/common/oauth2/v2.0/";
    static final String MS_SCOPE = "offline_access https://outlook.office.com/IMAP.AccessAsUser.All https://outlook.office.com/SMTP.Send openid email";
    static final String SYNC_FOLDER = "VEX Sync";
    static final String[] SENT_NAMES = {"[Gmail]/Sent Mail", "[Google Mail]/Sent Mail", "Sent", "Sent Items", "Sent Messages", "Sent Mail", "Verzonden items", "Verzonden"};
    static final String[] TRASH_NAMES = {"[Gmail]/Trash", "[Gmail]/Bin", "[Google Mail]/Trash", "Trash", "Deleted", "Deleted Items", "Deleted Messages", "Bin", "Prullenbak"};
    static final String[] ARCHIVE_NAMES = {"Archive", "Archives", "Archief"};
    static final String[] ALL_NAMES = {"[Gmail]/All Mail", "[Google Mail]/All Mail"};

    private Mail() {
    }

    static SharedPreferences prefs(Context ctx) {
        return ctx.getSharedPreferences("vex_mail", Context.MODE_PRIVATE);
    }

    /* ---------- connected accounts ---------- */

    static synchronized JSONArray accounts(Context ctx) {
        SharedPreferences p = prefs(ctx);
        try {
            JSONArray list = new JSONArray(p.getString("accounts", "[]"));
            String old = p.getString("config", null);
            if (old != null) {
                JSONObject c = new JSONObject(old);
                c.put("id", "a1");
                list.put(c);
                p.edit().putString("accounts", list.toString()).remove("config").apply();
            }
            return list;
        } catch (Exception e) {
            return new JSONArray();
        }
    }

    static synchronized void saveAccounts(Context ctx, JSONArray list) {
        prefs(ctx).edit().putString("accounts", list.toString()).apply();
    }

    static JSONObject account(Context ctx, String id) throws Exception {
        JSONArray list = accounts(ctx);
        for (int i = 0; i < list.length(); i++) {
            JSONObject c = list.getJSONObject(i);
            if (id == null || id.isEmpty() || id.equals(c.optString("id"))) return c;
        }
        throw new MessagingException("That email address isn't connected anymore.");
    }

    /** Adds an account, or replaces the one with the same address. Returns its id. */
    static synchronized String addAccount(Context ctx, JSONObject c) throws Exception {
        JSONArray list = accounts(ctx), kept = new JSONArray();
        String accountId = "a" + System.currentTimeMillis();
        for (int i = 0; i < list.length(); i++) {
            JSONObject a = list.getJSONObject(i);
            if (a.optString("email").equalsIgnoreCase(c.optString("email"))) accountId = a.optString("id");
            else kept.put(a);
        }
        c.put("id", accountId);
        kept.put(c);
        saveAccounts(ctx, kept);
        return accountId;
    }

    static synchronized void updateAccount(Context ctx, JSONObject c) throws Exception {
        JSONArray list = accounts(ctx), out = new JSONArray();
        for (int i = 0; i < list.length(); i++) {
            JSONObject a = list.getJSONObject(i);
            out.put(a.optString("id").equals(c.optString("id")) ? c : a);
        }
        saveAccounts(ctx, out);
    }

    /* ---------- Microsoft sign-in (Hotmail, Outlook, Live) ---------- */

    static boolean isOAuth(JSONObject c) {
        return "oauth".equals(c.optString("auth"));
    }

    static String enc(String s) throws Exception {
        return URLEncoder.encode(s == null ? "" : s, "UTF-8");
    }

    /** A Microsoft access token, renewed with the saved sign-in when the old one runs out. */
    static String accessToken(Context ctx, JSONObject c) throws Exception {
        if (c.optLong("expires") - 120000 > System.currentTimeMillis() && !c.optString("access").isEmpty()) return c.optString("access");
        String form = "client_id=" + enc(c.optString("clientId")) + "&grant_type=refresh_token&refresh_token=" + enc(c.optString("refresh")) + "&scope=" + enc(MS_SCOPE);
        String[] res = http("POST", MS_AUTH + "token", form, "application/x-www-form-urlencoded");
        JSONObject t = new JSONObject(res[1]);
        if (!t.has("access_token")) throw new AuthenticationFailedException("Your Microsoft sign-in ran out. Remove the account and sign in again.");
        c.put("access", t.getString("access_token"));
        c.put("expires", System.currentTimeMillis() + t.optLong("expires_in", 3600) * 1000);
        if (t.has("refresh_token")) c.put("refresh", t.getString("refresh_token"));
        updateAccount(ctx, c);
        return c.getString("access");
    }

    static String emailFromIdToken(String idToken) {
        try {
            String[] parts = idToken.split("\\.");
            JSONObject claims = new JSONObject(new String(Base64.decode(parts[1], Base64.URL_SAFE | Base64.NO_PADDING | Base64.NO_WRAP), "UTF-8"));
            String email = claims.optString("email", "");
            return email.isEmpty() ? claims.optString("preferred_username", "") : email;
        } catch (Exception e) {
            return "";
        }
    }

    /* ---------- sessions ---------- */

    static Session session(JSONObject c) {
        boolean smtpSsl = !"starttls".equals(c.optString("smtpSecurity", "ssl"));
        Properties p = new Properties();
        p.put("mail.store.protocol", "imaps");
        p.put("mail.imaps.ssl.enable", "true");
        p.put("mail.imaps.peek", "true");
        p.put("mail.imaps.connectiontimeout", "20000");
        p.put("mail.imaps.timeout", "40000");
        p.put("mail.smtp.auth", "true");
        p.put("mail.smtp.ssl.enable", smtpSsl ? "true" : "false");
        p.put("mail.smtp.starttls.enable", smtpSsl ? "false" : "true");
        p.put("mail.smtp.connectiontimeout", "20000");
        p.put("mail.smtp.timeout", "40000");
        if (isOAuth(c)) {
            p.put("mail.imaps.auth.mechanisms", "XOAUTH2");
            p.put("mail.imaps.auth.login.disable", "true");
            p.put("mail.imaps.auth.plain.disable", "true");
            p.put("mail.smtp.auth.mechanisms", "XOAUTH2");
            p.put("mail.smtp.auth.login.disable", "true");
            p.put("mail.smtp.auth.plain.disable", "true");
        }
        return Session.getInstance(p);
    }

    /** The password, or for Microsoft accounts a fresh sign-in token. */
    static String secret(Context ctx, JSONObject c) throws Exception {
        return isOAuth(c) ? accessToken(ctx, c) : c.optString("password");
    }

    static Store openStore(Context ctx, JSONObject c) throws Exception {
        if (!c.has("email")) throw new MessagingException("No email account is connected.");
        Store store = session(c).getStore("imaps");
        store.connect(c.optString("imapHost"), c.optInt("imapPort", 993), c.optString("email"), secret(ctx, c));
        return store;
    }

    static Folder special(Store store, String attribute, String[] names) throws MessagingException {
        for (Folder f : store.getDefaultFolder().list("*")) {
            if (f instanceof IMAPFolder) {
                String[] attrs = ((IMAPFolder) f).getAttributes();
                if (attrs != null) for (String a : attrs) if (attribute.equalsIgnoreCase(a)) return f;
            }
        }
        for (String n : names) {
            Folder f = store.getFolder(n);
            if (f.exists()) return f;
        }
        return null;
    }

    /** "inbox", "sent", "trash", "archive" or a folder's full name. */
    static Folder folder(Store store, String which) throws MessagingException {
        if (which == null || which.isEmpty() || "inbox".equalsIgnoreCase(which)) return store.getFolder("INBOX");
        if ("sent".equals(which)) return special(store, "\\Sent", SENT_NAMES);
        if ("trash".equals(which)) return special(store, "\\Trash", TRASH_NAMES);
        if ("archive".equals(which)) return special(store, "\\Archive", ARCHIVE_NAMES);
        Folder f = store.getFolder(which);
        return f.exists() ? f : null;
    }

    /* ---------- reading emails ---------- */

    static String[] person(Address[] list) {
        String name = "", email = "";
        if (list != null && list.length > 0) {
            if (list[0] instanceof InternetAddress) {
                InternetAddress a = (InternetAddress) list[0];
                email = a.getAddress() == null ? "" : a.getAddress();
                name = a.getPersonal() == null ? "" : a.getPersonal();
            } else {
                email = list[0].toString();
            }
        }
        return new String[]{name.isEmpty() ? email : name, email};
    }

    static String header(Message m, String name) {
        try {
            String[] h = m.getHeader(name);
            return h != null && h.length > 0 ? h[0] : "";
        } catch (Exception e) {
            return "";
        }
    }

    /** One email as VEX shows it in a list. */
    static JSONObject describe(IMAPFolder folder, Message m) throws Exception {
        JSONObject o = new JSONObject();
        o.put("uid", folder.getUID(m));
        String[] from = person(m.getFrom()), to = person(m.getRecipients(Message.RecipientType.TO));
        o.put("from", from[0]);
        o.put("email", from[1]);
        o.put("to", to[0]);
        o.put("toEmail", to[1]);
        o.put("subject", m.getSubject() == null ? "(no subject)" : m.getSubject());
        Date d = m.getReceivedDate() != null ? m.getReceivedDate() : m.getSentDate();
        o.put("time", d == null ? System.currentTimeMillis() : d.getTime());
        o.put("seen", m.isSet(Flags.Flag.SEEN));
        o.put("flagged", m.isSet(Flags.Flag.FLAGGED));
        o.put("messageId", header(m, "Message-ID"));
        o.put("unsub", header(m, "List-Unsubscribe"));
        o.put("unsubPost", header(m, "List-Unsubscribe-Post"));
        Body b = new Body();
        try {
            read(m, b, 0, null);
        } catch (Exception ignored) {
        }
        o.put("body", b.text());
        o.put("atts", b.atts);
        o.put("clip", b.atts.length() > 0);
        o.put("hasHtml", b.html != null);
        return o;
    }

    static class Body {
        String plain = null, html = null;
        JSONArray atts = new JSONArray();

        String text() {
            String t = plain != null ? plain : html != null ? htmlToText(html) : "";
            t = t.replace("\r\n", "\n").replaceAll("[ \\t]+\n", "\n").replaceAll("\n{3,}", "\n\n").trim();
            return t.length() > 6000 ? t.substring(0, 6000) + "…" : t;
        }
    }

    /** Walks an email's parts. Attachments are numbered in the order they're found, so the same walk finds them again. */
    static void read(Part p, Body b, int depth, List<Part> found) throws Exception {
        if (depth > 8) return;
        String fileName = null;
        try {
            fileName = p.getFileName();
        } catch (Exception ignored) {
        }
        boolean named = fileName != null && !fileName.isEmpty();
        boolean embedded = Part.INLINE.equalsIgnoreCase(p.getDisposition()) && p.getHeader("Content-ID") != null;
        if (!p.isMimeType("multipart/*") && !embedded && (Part.ATTACHMENT.equalsIgnoreCase(p.getDisposition()) || named || p.isMimeType("message/rfc822"))) {
            if (found != null) found.add(p);
            JSONObject a = new JSONObject();
            String name = named ? MimeUtility.decodeText(fileName) : "attached email.eml";
            String type = p.getContentType() == null ? "" : p.getContentType().split(";")[0].trim().toLowerCase();
            a.put("name", name);
            a.put("size", Math.max(0, p.getSize()));
            a.put("type", type);
            a.put("idx", b.atts.length());
            b.atts.put(a);
            return;
        }
        if (p.isMimeType("text/plain")) {
            Object c = p.getContent();
            if (b.plain == null && c instanceof String) b.plain = (String) c;
        } else if (p.isMimeType("text/html")) {
            Object c = p.getContent();
            if (b.html == null && c instanceof String) b.html = (String) c;
        } else if (p.isMimeType("multipart/*")) {
            Object c = p.getContent();
            if (c instanceof Multipart) {
                Multipart mp = (Multipart) c;
                for (int i = 0; i < mp.getCount(); i++) read(mp.getBodyPart(i), b, depth + 1, found);
            }
        }
    }

    static String htmlToText(String html) {
        String cleaned = html.replaceAll("(?is)<(script|style|head)[^>]*>.*?</\\1>", " ");
        return Html.fromHtml(cleaned, Html.FROM_HTML_MODE_LEGACY).toString().replace('\uFFFC', ' ');
    }

    /* ---------- the web ---------- */

    static HttpURLConnection open(String url) throws Exception {
        HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
        c.setInstanceFollowRedirects(true);
        c.setConnectTimeout(20000);
        c.setReadTimeout(60000);
        c.setRequestProperty("User-Agent", "VEX-Mail");
        return c;
    }

    static String readAll(InputStream in, int max) throws Exception {
        if (in == null) return "";
        ByteArrayOutputStream buf = new ByteArrayOutputStream();
        try {
            byte[] b = new byte[16384];
            int n;
            while ((n = in.read(b)) > 0 && buf.size() < max) buf.write(b, 0, n);
        } finally {
            in.close();
        }
        return buf.toString("UTF-8");
    }

    /** Returns {status code, response text}; error responses are returned too, not thrown. */
    static String[] http(String method, String url, String body, String type) throws Exception {
        HttpURLConnection c = open(url);
        c.setRequestMethod(method);
        if (body != null) {
            c.setDoOutput(true);
            c.setRequestProperty("Content-Type", type);
            try (OutputStream out = c.getOutputStream()) {
                out.write(body.getBytes("UTF-8"));
            }
        }
        int code = c.getResponseCode();
        String text = readAll(code >= 400 ? c.getErrorStream() : c.getInputStream(), 4000000);
        return new String[]{String.valueOf(code), text};
    }

    /* ---------- notifications, reminders, background checks ---------- */

    static void notify(Context ctx, int id, String title, String text) {
        NotificationManager nm = (NotificationManager) ctx.getSystemService(Context.NOTIFICATION_SERVICE);
        if (nm == null) return;
        nm.createNotificationChannel(new NotificationChannel("vex", "VEX", NotificationManager.IMPORTANCE_DEFAULT));
        Intent open = new Intent(ctx, MainActivity.class);
        open.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);
        PendingIntent pi = PendingIntent.getActivity(ctx, id, open, PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        Notification n = new Notification.Builder(ctx, "vex")
                .setSmallIcon(android.R.drawable.ic_dialog_email)
                .setContentTitle(title)
                .setContentText(text)
                .setStyle(new Notification.BigTextStyle().bigText(text))
                .setColor(0xFFD6182B)
                .setContentIntent(pi)
                .setAutoCancel(true)
                .build();
        nm.notify(id, n);
    }

    static boolean inQuietHours(JSONObject cfg, long now) {
        int from = cfg.optInt("quietFrom", -1), to = cfg.optInt("quietTo", -1);
        if (from < 0 || to < 0 || from == to) return false;
        Calendar c = Calendar.getInstance();
        c.setTimeInMillis(now);
        int m = c.get(Calendar.HOUR_OF_DAY) * 60 + c.get(Calendar.MINUTE);
        return from < to ? (m >= from && m < to) : (m >= from || m < to);
    }

    static boolean inFocus(JSONObject cfg, long now) {
        JSONArray f = cfg.optJSONArray("focus");
        if (f == null) return false;
        for (int i = 0; i < f.length(); i++) {
            JSONArray w = f.optJSONArray(i);
            if (w != null && w.optLong(0) <= now && now < w.optLong(1)) return true;
        }
        return false;
    }

    static boolean listed(JSONArray list, String email) {
        if (list == null || email == null) return false;
        for (int i = 0; i < list.length(); i++) if (email.equalsIgnoreCase(list.optString(i))) return true;
        return false;
    }

    static void scheduleChecks(Context ctx, boolean on) {
        JobScheduler js = (JobScheduler) ctx.getSystemService(Context.JOB_SCHEDULER_SERVICE);
        if (js == null) return;
        if (!on) {
            js.cancel(1);
            return;
        }
        JobInfo job = new JobInfo.Builder(1, new ComponentName(ctx, MailCheckJob.class))
                .setPeriodic(15 * 60 * 1000L)
                .setRequiredNetworkType(JobInfo.NETWORK_TYPE_ANY)
                .setPersisted(true)
                .build();
        js.schedule(job);
    }

    static PendingIntent reminderIntent(Context ctx, JSONObject r, int i) {
        Intent it = new Intent(ctx, ReminderReceiver.class);
        it.putExtra("title", r.optString("title"));
        it.putExtra("text", r.optString("text"));
        return PendingIntent.getBroadcast(ctx, 1000 + i, it, PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
    }

    /** Sets an alarm for each upcoming reminder, replacing the old ones. */
    static void scheduleReminders(Context ctx, JSONArray list) {
        AlarmManager am = (AlarmManager) ctx.getSystemService(Context.ALARM_SERVICE);
        if (am == null) return;
        SharedPreferences p = prefs(ctx);
        try {
            JSONArray old = new JSONArray(p.getString("reminders", "[]"));
            for (int i = 0; i < old.length(); i++) am.cancel(reminderIntent(ctx, old.getJSONObject(i), i));
        } catch (Exception ignored) {
        }
        long now = System.currentTimeMillis();
        try {
            for (int i = 0; i < Math.min(list.length(), 60); i++) {
                JSONObject r = list.getJSONObject(i);
                if (r.optLong("at") > now) am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, r.optLong("at"), reminderIntent(ctx, r, i));
            }
        } catch (Exception ignored) {
        }
        p.edit().putString("reminders", list.toString()).apply();
    }
}
