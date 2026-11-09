package com.vexmail.app;

import android.app.Activity;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageInfo;
import android.content.pm.PackageInstaller;
import android.net.Uri;
import android.os.Build;
import android.provider.Settings;
import android.text.Html;
import android.webkit.JavascriptInterface;
import android.webkit.WebView;

import com.sun.mail.imap.IMAPFolder;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Properties;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import javax.mail.Address;
import javax.mail.AuthenticationFailedException;
import javax.mail.FetchProfile;
import javax.mail.Flags;
import javax.mail.Folder;
import javax.mail.Message;
import javax.mail.MessagingException;
import javax.mail.Multipart;
import javax.mail.Part;
import javax.mail.Session;
import javax.mail.Store;
import javax.mail.Transport;
import javax.mail.UIDFolder;
import javax.mail.internet.InternetAddress;
import javax.mail.internet.MimeMessage;

/** Lets the VEX app read and send real email over IMAP and SMTP. The password stays on this phone. */
public class MailBridge {
    private final Activity activity;
    private final WebView web;
    private final SharedPreferences prefs;
    private final ExecutorService worker = Executors.newSingleThreadExecutor();

    MailBridge(Activity activity, WebView web) {
        this.activity = activity;
        this.web = web;
        this.prefs = activity.getSharedPreferences("vex_mail", Context.MODE_PRIVATE);
    }

    private interface Job {
        JSONObject run() throws Exception;
    }

    private void runJob(final String id, final Job job) {
        worker.execute(() -> {
            JSONObject out;
            try {
                out = job.run();
                out.put("ok", true);
            } catch (AuthenticationFailedException e) {
                out = error("auth", "The email address or app password is wrong.");
            } catch (Exception e) {
                out = error("failed", e.getMessage() == null ? e.toString() : e.getMessage());
            }
            final String js = "window.vexNativeResult && window.vexNativeResult(" + JSONObject.quote(id) + "," + JSONObject.quote(out.toString()) + ")";
            activity.runOnUiThread(() -> web.evaluateJavascript(js, null));
        });
    }

    private static JSONObject error(String code, String message) {
        JSONObject o = new JSONObject();
        try {
            o.put("ok", false);
            o.put("code", code);
            o.put("message", message);
        } catch (Exception ignored) {
        }
        return o;
    }

    private synchronized JSONArray accounts() {
        try {
            JSONArray list = new JSONArray(prefs.getString("accounts", "[]"));
            String old = prefs.getString("config", null);
            if (old != null) {
                JSONObject c = new JSONObject(old);
                c.put("id", "a1");
                list.put(c);
                prefs.edit().putString("accounts", list.toString()).remove("config").apply();
            }
            return list;
        } catch (Exception e) {
            return new JSONArray();
        }
    }

    private synchronized void saveAccounts(JSONArray list) {
        prefs.edit().putString("accounts", list.toString()).apply();
    }

    /** The settings for one connected email address (the first one when no id is given). */
    private JSONObject account(String id) throws Exception {
        JSONArray list = accounts();
        for (int i = 0; i < list.length(); i++) {
            JSONObject c = list.getJSONObject(i);
            if (id == null || id.isEmpty() || id.equals(c.optString("id"))) return c;
        }
        throw new MessagingException("That email address isn't connected anymore.");
    }

    private Session session(JSONObject c) {
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
        return Session.getInstance(p);
    }

    private Store openStore(JSONObject c) throws MessagingException {
        if (!c.has("email")) throw new MessagingException("No email account is connected.");
        Store store = session(c).getStore("imaps");
        store.connect(c.optString("imapHost"), c.optInt("imapPort", 993), c.optString("email"), c.optString("password"));
        return store;
    }

    private static long[] uids(JSONArray a) throws Exception {
        long[] out = new long[a.length()];
        for (int i = 0; i < a.length(); i++) out[i] = a.getLong(i);
        return out;
    }

    private static Message[] existing(Message[] msgs) {
        List<Message> found = new ArrayList<>();
        if (msgs != null) for (Message m : msgs) if (m != null) found.add(m);
        return found.toArray(new Message[0]);
    }

    @JavascriptInterface
    public String status() {
        JSONObject o = new JSONObject();
        try {
            JSONArray list = accounts(), out = new JSONArray();
            for (int i = 0; i < list.length(); i++) {
                JSONObject c = list.getJSONObject(i), a = new JSONObject();
                a.put("id", c.optString("id"));
                a.put("email", c.optString("email"));
                out.put(a);
            }
            o.put("connected", list.length() > 0);
            o.put("email", list.length() > 0 ? list.getJSONObject(0).optString("email") : "");
            o.put("accounts", out);
        } catch (Exception ignored) {
        }
        return o.toString();
    }

    @JavascriptInterface
    public void connect(final String id, final String json) {
        runJob(id, () -> {
            JSONObject c = new JSONObject(json);
            Store store = openStore(c);
            store.close();
            JSONArray list = accounts(), kept = new JSONArray();
            String accountId = "a" + System.currentTimeMillis();
            for (int i = 0; i < list.length(); i++) {
                JSONObject a = list.getJSONObject(i);
                if (a.optString("email").equalsIgnoreCase(c.optString("email"))) accountId = a.optString("id");
                else kept.put(a);
            }
            c.put("id", accountId);
            kept.put(c);
            saveAccounts(kept);
            JSONObject o = new JSONObject();
            o.put("id", accountId);
            o.put("email", c.optString("email"));
            return o;
        });
    }

    @JavascriptInterface
    public void disconnect() {
        prefs.edit().remove("config").remove("accounts").apply();
    }

    @JavascriptInterface
    public void removeAccount(String id) {
        JSONArray list = accounts(), kept = new JSONArray();
        try {
            for (int i = 0; i < list.length(); i++) {
                JSONObject a = list.getJSONObject(i);
                if (!id.equals(a.optString("id"))) kept.put(a);
            }
        } catch (Exception ignored) {
        }
        saveAccounts(kept);
    }

    @JavascriptInterface
    public void fetch(final String id, final String json) {
        runJob(id, () -> {
            JSONObject opts = new JSONObject(json);
            int limit = opts.optInt("limit", 40);
            Store store = openStore(account(opts.optString("account")));
            try {
                IMAPFolder inbox = (IMAPFolder) store.getFolder("INBOX");
                inbox.open(Folder.READ_ONLY);
                JSONArray list = new JSONArray();
                int total = inbox.getMessageCount();
                if (total > 0) {
                    Message[] msgs = inbox.getMessages(Math.max(1, total - limit + 1), total);
                    FetchProfile fp = new FetchProfile();
                    fp.add(FetchProfile.Item.ENVELOPE);
                    fp.add(FetchProfile.Item.FLAGS);
                    fp.add(UIDFolder.FetchProfileItem.UID);
                    inbox.fetch(msgs, fp);
                    for (int i = msgs.length - 1; i >= 0; i--) list.put(describe(inbox, msgs[i]));
                }
                inbox.close(false);
                JSONObject out = new JSONObject();
                out.put("mails", list);
                return out;
            } finally {
                store.close();
            }
        });
    }

    private static JSONObject describe(IMAPFolder inbox, Message m) throws Exception {
        JSONObject o = new JSONObject();
        o.put("uid", inbox.getUID(m));
        String name = "", email = "";
        Address[] from = m.getFrom();
        if (from != null && from.length > 0) {
            if (from[0] instanceof InternetAddress) {
                InternetAddress a = (InternetAddress) from[0];
                email = a.getAddress() == null ? "" : a.getAddress();
                name = a.getPersonal() == null ? "" : a.getPersonal();
            } else {
                email = from[0].toString();
            }
        }
        o.put("from", name.isEmpty() ? email : name);
        o.put("email", email);
        o.put("subject", m.getSubject() == null ? "(no subject)" : m.getSubject());
        Date d = m.getReceivedDate() != null ? m.getReceivedDate() : m.getSentDate();
        o.put("time", d == null ? System.currentTimeMillis() : d.getTime());
        o.put("seen", m.isSet(Flags.Flag.SEEN));
        o.put("flagged", m.isSet(Flags.Flag.FLAGGED));
        String[] mid = m.getHeader("Message-ID");
        o.put("messageId", mid != null && mid.length > 0 ? mid[0] : "");
        Body b = new Body();
        try {
            read(m, b, 0);
        } catch (Exception ignored) {
        }
        o.put("body", b.text());
        o.put("clip", b.attachment);
        return o;
    }

    private static class Body {
        String plain = null, html = null;
        boolean attachment = false;

        String text() {
            String t = plain != null ? plain : html != null ? htmlToText(html) : "";
            t = t.replace("\r\n", "\n").replaceAll("[ \\t]+\n", "\n").replaceAll("\n{3,}", "\n\n").trim();
            return t.length() > 6000 ? t.substring(0, 6000) + "…" : t;
        }
    }

    private static void read(Part p, Body b, int depth) throws Exception {
        if (depth > 8) return;
        String fileName = null;
        try {
            fileName = p.getFileName();
        } catch (Exception ignored) {
        }
        if (Part.ATTACHMENT.equalsIgnoreCase(p.getDisposition()) || (fileName != null && !fileName.isEmpty())) {
            b.attachment = true;
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
                for (int i = 0; i < mp.getCount(); i++) read(mp.getBodyPart(i), b, depth + 1);
            }
        } else if (p.isMimeType("message/rfc822")) {
            b.attachment = true;
        }
    }

    private static String htmlToText(String html) {
        String cleaned = html.replaceAll("(?is)<(script|style|head)[^>]*>.*?</\\1>", " ");
        return Html.fromHtml(cleaned, Html.FROM_HTML_MODE_LEGACY).toString().replace('\uFFFC', ' ');
    }

    @JavascriptInterface
    public void setFlags(final String id, final String json) {
        runJob(id, () -> {
            JSONObject o = new JSONObject(json);
            Store store = openStore(account(o.optString("account")));
            try {
                IMAPFolder inbox = (IMAPFolder) store.getFolder("INBOX");
                inbox.open(Folder.READ_WRITE);
                Message[] msgs = existing(inbox.getMessagesByUID(uids(o.getJSONArray("uids"))));
                if (msgs.length > 0 && o.has("seen")) inbox.setFlags(msgs, new Flags(Flags.Flag.SEEN), o.getBoolean("seen"));
                if (msgs.length > 0 && o.has("flagged")) inbox.setFlags(msgs, new Flags(Flags.Flag.FLAGGED), o.getBoolean("flagged"));
                inbox.close(false);
            } finally {
                store.close();
            }
            return new JSONObject();
        });
    }

    /** Archive or delete: moves the emails out of the inbox on the mail server. */
    @JavascriptInterface
    public void move(final String id, final String json) {
        runJob(id, () -> {
            JSONObject o = new JSONObject(json);
            boolean trash = "trash".equals(o.optString("to"));
            JSONObject c = account(o.optString("account"));
            Store store = openStore(c);
            try {
                IMAPFolder inbox = (IMAPFolder) store.getFolder("INBOX");
                inbox.open(Folder.READ_WRITE);
                Message[] msgs = existing(inbox.getMessagesByUID(uids(o.getJSONArray("uids"))));
                if (msgs.length > 0) {
                    boolean gmail = c.optString("imapHost").contains("gmail");
                    if (!(gmail && !trash)) {
                        Folder dest = trash
                                ? special(store, "\\Trash", new String[]{"[Gmail]/Trash", "Trash", "Deleted", "Deleted Items", "Bin"})
                                : special(store, "\\Archive", new String[]{"Archive", "Archives"});
                        if (dest == null) {
                            dest = store.getFolder(trash ? "Trash" : "Archive");
                            if (!dest.exists()) dest.create(Folder.HOLDS_MESSAGES);
                        }
                        inbox.copyMessages(msgs, dest);
                    }
                    inbox.setFlags(msgs, new Flags(Flags.Flag.DELETED), true);
                }
                inbox.close(true);
            } finally {
                store.close();
            }
            return new JSONObject();
        });
    }

    private static Folder special(Store store, String attribute, String[] names) throws MessagingException {
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

    @JavascriptInterface
    public void send(final String id, final String json) {
        runJob(id, () -> {
            JSONObject o = new JSONObject(json), c = account(o.optString("account"));
            Session s = session(c);
            MimeMessage msg = new MimeMessage(s);
            msg.setFrom(new InternetAddress(c.optString("email")));
            msg.setRecipients(Message.RecipientType.TO, InternetAddress.parse(o.getString("to")));
            msg.setSubject(o.optString("subject", ""), "UTF-8");
            msg.setText(o.optString("body", ""), "UTF-8");
            String inReplyTo = o.optString("inReplyTo", "");
            if (!inReplyTo.isEmpty()) {
                msg.setHeader("In-Reply-To", inReplyTo);
                msg.setHeader("References", inReplyTo);
            }
            msg.setSentDate(new Date());
            Transport t = s.getTransport("smtp");
            try {
                t.connect(c.optString("smtpHost"), c.optInt("smtpPort", 465), c.optString("email"), c.optString("password"));
                t.sendMessage(msg, msg.getAllRecipients());
            } finally {
                t.close();
            }
            return new JSONObject();
        });
    }

    /** Downloads a calendar (.ics) from a link for the calendar's subscriptions. */
    @JavascriptInterface
    public void httpGet(final String id, final String json) {
        runJob(id, () -> {
            String url = new JSONObject(json).getString("url").replaceFirst("^webcals?://", "https://");
            HttpURLConnection c = open(url);
            int code = c.getResponseCode();
            if (code != 200) throw new Exception("The calendar link answered " + code);
            ByteArrayOutputStream buf = new ByteArrayOutputStream();
            try (InputStream in = c.getInputStream()) {
                byte[] b = new byte[16384];
                int n;
                while ((n = in.read(b)) > 0 && buf.size() < 4000000) buf.write(b, 0, n);
            }
            JSONObject o = new JSONObject();
            o.put("text", buf.toString("UTF-8"));
            return o;
        });
    }

    /* ================= app updates from GitHub Releases ================= */

    private long currentBuild() throws Exception {
        PackageInfo info = activity.getPackageManager().getPackageInfo(activity.getPackageName(), 0);
        return Build.VERSION.SDK_INT >= 28 ? info.getLongVersionCode() : info.versionCode;
    }

    @JavascriptInterface
    public String appBuild() {
        try {
            return String.valueOf(currentBuild());
        } catch (Exception e) {
            return "0";
        }
    }

    private static HttpURLConnection open(String url) throws Exception {
        HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
        c.setInstanceFollowRedirects(true);
        c.setConnectTimeout(20000);
        c.setReadTimeout(60000);
        c.setRequestProperty("User-Agent", "VEX-Mail");
        return c;
    }

    /** Asks GitHub which VEX build is the newest. */
    @JavascriptInterface
    public void checkUpdate(final String id, final String json) {
        runJob(id, () -> {
            String repo = new JSONObject(json).getString("repo");
            HttpURLConnection c = open("https://api.github.com/repos/" + repo + "/releases?per_page=20");
            c.setRequestProperty("Accept", "application/vnd.github+json");
            int code = c.getResponseCode();
            if (code != 200) throw new Exception("GitHub answered " + code);
            ByteArrayOutputStream buf = new ByteArrayOutputStream();
            try (InputStream in = c.getInputStream()) {
                byte[] b = new byte[16384];
                int n;
                while ((n = in.read(b)) > 0) buf.write(b, 0, n);
            }
            // newest release that has an Android app in it (PC releases are skipped)
            JSONArray releases = new JSONArray(buf.toString("UTF-8"));
            long newest = 0;
            String url = "";
            for (int i = 0; i < releases.length(); i++) {
                JSONObject rel = releases.getJSONObject(i);
                if (rel.optBoolean("draft") || rel.optBoolean("prerelease")) continue;
                String apk = "";
                JSONArray assets = rel.optJSONArray("assets");
                if (assets != null) {
                    for (int j = 0; j < assets.length(); j++) {
                        JSONObject a = assets.getJSONObject(j);
                        if (a.optString("name", "").toLowerCase().endsWith(".apk")) apk = a.optString("browser_download_url", "");
                    }
                }
                String digits = rel.optString("tag_name", "").replaceAll("[^0-9]", "");
                long number = digits.isEmpty() ? 0 : Long.parseLong(digits);
                if (!apk.isEmpty() && number > newest) {
                    newest = number;
                    url = apk;
                }
            }
            JSONObject o = new JSONObject();
            o.put("current", currentBuild());
            o.put("latest", newest);
            o.put("url", url);
            return o;
        });
    }

    /** Downloads the new VEX.apk and hands it to Android's installer. */
    @JavascriptInterface
    public void installUpdate(final String id, final String json) {
        runJob(id, () -> {
            String url = new JSONObject(json).getString("url");
            JSONObject o = new JSONObject();
            if (!activity.getPackageManager().canRequestPackageInstalls()) {
                activity.runOnUiThread(() -> {
                    try {
                        activity.startActivity(new Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:" + activity.getPackageName())));
                    } catch (Exception ignored) {
                    }
                });
                o.put("status", "allow");
                return o;
            }
            PackageInstaller installer = activity.getPackageManager().getPackageInstaller();
            PackageInstaller.SessionParams params = new PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL);
            if (Build.VERSION.SDK_INT >= 31) params.setRequireUserAction(PackageInstaller.SessionParams.USER_ACTION_NOT_REQUIRED);
            int sessionId = installer.createSession(params);
            PackageInstaller.Session session = installer.openSession(sessionId);
            try {
                HttpURLConnection c = open(url);
                if (c.getResponseCode() != 200) throw new Exception("Download failed (" + c.getResponseCode() + ")");
                try (InputStream in = c.getInputStream(); OutputStream out = session.openWrite("vex.apk", 0, -1)) {
                    byte[] b = new byte[65536];
                    int n;
                    while ((n = in.read(b)) > 0) out.write(b, 0, n);
                    session.fsync(out);
                }
                Intent done = new Intent(activity, MainActivity.class).setAction(MainActivity.INSTALL_ACTION);
                int flags = PendingIntent.FLAG_UPDATE_CURRENT | (Build.VERSION.SDK_INT >= 31 ? PendingIntent.FLAG_MUTABLE : 0);
                PendingIntent pending = PendingIntent.getActivity(activity, sessionId, done, flags);
                session.commit(pending.getIntentSender());
            } catch (Exception e) {
                session.abandon();
                throw e;
            } finally {
                session.close();
            }
            o.put("status", "started");
            return o;
        });
    }
}
