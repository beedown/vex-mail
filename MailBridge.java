package com.vexmail.app;

import android.app.Activity;
import android.app.PendingIntent;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageInfo;
import android.content.pm.PackageInstaller;
import android.hardware.biometrics.BiometricManager;
import android.hardware.biometrics.BiometricPrompt;
import android.net.Uri;
import android.os.Build;
import android.os.CancellationSignal;
import android.provider.Settings;
import android.util.Base64;
import android.webkit.JavascriptInterface;
import android.webkit.WebView;

import com.sun.mail.imap.IMAPFolder;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import javax.activation.DataHandler;
import javax.mail.AuthenticationFailedException;
import javax.mail.FetchProfile;
import javax.mail.Flags;
import javax.mail.Folder;
import javax.mail.Message;
import javax.mail.Part;
import javax.mail.Session;
import javax.mail.Store;
import javax.mail.Transport;
import javax.mail.UIDFolder;
import javax.mail.internet.InternetAddress;
import javax.mail.internet.MimeBodyPart;
import javax.mail.internet.MimeMessage;
import javax.mail.internet.MimeMultipart;
import javax.mail.internet.MimeUtility;
import javax.mail.search.BodyTerm;
import javax.mail.search.FromStringTerm;
import javax.mail.search.OrTerm;
import javax.mail.search.SearchTerm;
import javax.mail.search.SubjectTerm;
import javax.mail.util.ByteArrayDataSource;

/** Lets the VEX app read and send real email. Passwords and sign-ins stay on this phone. */
public class MailBridge {
    static final String REDIRECT = "vexmail://auth";
    private final Activity activity;
    private final WebView web;
    private final SharedPreferences prefs;
    private final ExecutorService worker = Executors.newFixedThreadPool(3);
    private String oauthId, oauthVerifier, oauthState, oauthClient;

    MailBridge(Activity activity, WebView web) {
        this.activity = activity;
        this.web = web;
        this.prefs = Mail.prefs(activity);
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
                String m = e.getMessage();
                out = error("auth", m != null && m.startsWith("Your Microsoft") ? m : "The email address or app password is wrong.");
            } catch (Exception e) {
                out = error("failed", e.getMessage() == null ? e.toString() : e.getMessage());
            }
            resolve(id, out);
        });
    }

    private void resolve(String id, JSONObject out) {
        final String js = "window.vexNativeResult && window.vexNativeResult(" + JSONObject.quote(id) + "," + JSONObject.quote(out.toString()) + ")";
        activity.runOnUiThread(() -> web.evaluateJavascript(js, null));
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

    private static JSONObject okResult() {
        JSONObject o = new JSONObject();
        try {
            o.put("ok", true);
        } catch (Exception ignored) {
        }
        return o;
    }

    private JSONObject account(String id) throws Exception {
        return Mail.account(activity, id);
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

    private static JSONArray describeAll(IMAPFolder f, Message[] msgs, String folderName) throws Exception {
        FetchProfile fp = new FetchProfile();
        fp.add(FetchProfile.Item.ENVELOPE);
        fp.add(FetchProfile.Item.FLAGS);
        fp.add(UIDFolder.FetchProfileItem.UID);
        f.fetch(msgs, fp);
        JSONArray list = new JSONArray();
        for (int i = msgs.length - 1; i >= 0; i--) {
            JSONObject o = Mail.describe(f, msgs[i]);
            o.put("folder", folderName);
            list.put(o);
        }
        return list;
    }

    /* ================= accounts ================= */

    @JavascriptInterface
    public String status() {
        JSONObject o = new JSONObject();
        try {
            JSONArray list = Mail.accounts(activity), out = new JSONArray();
            for (int i = 0; i < list.length(); i++) {
                JSONObject c = list.getJSONObject(i), a = new JSONObject();
                a.put("id", c.optString("id"));
                a.put("email", c.optString("email"));
                a.put("microsoft", Mail.isOAuth(c));
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
            Store store = Mail.openStore(activity, c);
            store.close();
            String accountId = Mail.addAccount(activity, c);
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
        JSONArray list = Mail.accounts(activity), kept = new JSONArray();
        try {
            for (int i = 0; i < list.length(); i++) {
                JSONObject a = list.getJSONObject(i);
                if (!id.equals(a.optString("id"))) kept.put(a);
            }
        } catch (Exception ignored) {
        }
        Mail.saveAccounts(activity, kept);
    }

    /* ================= Microsoft sign-in ================= */

    private static String b64url(byte[] b) {
        return Base64.encodeToString(b, Base64.URL_SAFE | Base64.NO_PADDING | Base64.NO_WRAP);
    }

    private static String random(int bytes) {
        byte[] b = new byte[bytes];
        new SecureRandom().nextBytes(b);
        return b64url(b);
    }

    /** Opens Microsoft's sign-in page in the browser. The answer comes back through finishOAuth. */
    @JavascriptInterface
    public void oauth(final String id, final String json) {
        try {
            oauthClient = new JSONObject(json).getString("clientId");
            oauthVerifier = random(48);
            oauthState = random(16);
            oauthId = id;
            String challenge = b64url(MessageDigest.getInstance("SHA-256").digest(oauthVerifier.getBytes("US-ASCII")));
            final String url = Mail.MS_AUTH + "authorize?client_id=" + Mail.enc(oauthClient) + "&response_type=code&response_mode=query&prompt=select_account"
                    + "&redirect_uri=" + Mail.enc(REDIRECT) + "&scope=" + Mail.enc(Mail.MS_SCOPE)
                    + "&code_challenge=" + challenge + "&code_challenge_method=S256&state=" + oauthState;
            activity.runOnUiThread(() -> activity.startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(url))));
        } catch (Exception e) {
            resolve(id, error("failed", "Couldn't open Microsoft's sign-in page."));
        }
    }

    void finishOAuth(final Uri uri) {
        final String id = oauthId;
        if (id == null) return;
        oauthId = null;
        runJob(id, () -> {
            if (uri.getQueryParameter("error") != null) {
                String why = uri.getQueryParameter("error_description");
                throw new Exception(why == null ? "Microsoft didn't sign you in." : why);
            }
            if (oauthState == null || !oauthState.equals(uri.getQueryParameter("state"))) throw new Exception("That sign-in didn't match. Try again.");
            String form = "client_id=" + Mail.enc(oauthClient) + "&grant_type=authorization_code&code=" + Mail.enc(uri.getQueryParameter("code"))
                    + "&redirect_uri=" + Mail.enc(REDIRECT) + "&code_verifier=" + Mail.enc(oauthVerifier) + "&scope=" + Mail.enc(Mail.MS_SCOPE);
            JSONObject t = new JSONObject(Mail.http("POST", Mail.MS_AUTH + "token", form, "application/x-www-form-urlencoded")[1]);
            if (!t.has("access_token")) throw new Exception(t.optString("error_description", "Microsoft didn't sign you in."));
            JSONObject c = new JSONObject();
            c.put("auth", "oauth");
            c.put("provider", "outlook");
            c.put("clientId", oauthClient);
            c.put("email", Mail.emailFromIdToken(t.optString("id_token")));
            c.put("access", t.getString("access_token"));
            c.put("refresh", t.optString("refresh_token"));
            c.put("expires", System.currentTimeMillis() + t.optLong("expires_in", 3600) * 1000);
            c.put("imapHost", "outlook.office365.com");
            c.put("imapPort", 993);
            c.put("smtpHost", "smtp-mail.outlook.com");
            c.put("smtpPort", 587);
            c.put("smtpSecurity", "starttls");
            if (c.optString("email").isEmpty()) throw new Exception("Microsoft didn't share your email address. Try again.");
            Store s = Mail.openStore(activity, c);
            s.close();
            String accountId = Mail.addAccount(activity, c);
            JSONObject out = new JSONObject();
            out.put("id", accountId);
            out.put("email", c.optString("email"));
            return out;
        });
    }

    /* ================= reading ================= */

    /** The newest emails in a folder: "inbox", "sent", "trash", "archive" or a folder's full name. */
    @JavascriptInterface
    public void fetch(final String id, final String json) {
        runJob(id, () -> {
            JSONObject opts = new JSONObject(json);
            int limit = opts.optInt("limit", 40);
            String which = opts.optString("folder", "inbox");
            Store store = Mail.openStore(activity, account(opts.optString("account")));
            try {
                JSONArray list = new JSONArray();
                IMAPFolder f = (IMAPFolder) Mail.folder(store, which);
                if (f != null) {
                    f.open(Folder.READ_ONLY);
                    int total = f.getMessageCount();
                    if (total > 0) list = describeAll(f, f.getMessages(Math.max(1, total - limit + 1), total), which);
                    f.close(false);
                }
                JSONObject out = new JSONObject();
                out.put("mails", list);
                return out;
            } finally {
                store.close();
            }
        });
    }

    /** One email's full content, with its designed (HTML) version when it has one. */
    @JavascriptInterface
    public void message(final String id, final String json) {
        runJob(id, () -> {
            JSONObject o = new JSONObject(json);
            Store store = Mail.openStore(activity, account(o.optString("account")));
            try {
                IMAPFolder f = (IMAPFolder) Mail.folder(store, o.optString("folder", "inbox"));
                if (f == null) throw new Exception("That folder is gone.");
                f.open(Folder.READ_ONLY);
                Message m = f.getMessageByUID(o.getLong("uid"));
                if (m == null) throw new Exception("That email isn't there anymore.");
                Mail.Body b = new Mail.Body();
                Mail.read(m, b, 0, null);
                JSONObject out = new JSONObject();
                String html = b.html == null ? "" : b.html;
                out.put("html", html.length() > 400000 ? html.substring(0, 400000) : html);
                out.put("text", b.text());
                f.close(false);
                return out;
            } finally {
                store.close();
            }
        });
    }

    @JavascriptInterface
    public void folders(final String id, final String json) {
        runJob(id, () -> {
            Store store = Mail.openStore(activity, account(new JSONObject(json).optString("account")));
            try {
                JSONArray list = new JSONArray();
                for (Folder f : store.getDefaultFolder().list("*")) {
                    if ((f.getType() & Folder.HOLDS_MESSAGES) == 0) continue;
                    String full = f.getFullName();
                    if ("INBOX".equalsIgnoreCase(full) || Mail.SYNC_FOLDER.equals(full)) continue;
                    String kind = "";
                    if (f instanceof IMAPFolder) {
                        String[] attrs = ((IMAPFolder) f).getAttributes();
                        if (attrs != null) for (String a : attrs) {
                            String x = a.toLowerCase();
                            if (x.equals("\\noselect")) kind = "skip";
                            else if (kind.isEmpty() && (x.equals("\\sent") || x.equals("\\trash") || x.equals("\\archive") || x.equals("\\junk") || x.equals("\\drafts") || x.equals("\\all") || x.equals("\\flagged"))) kind = x.substring(1);
                        }
                    }
                    if ("skip".equals(kind)) continue;
                    JSONObject o = new JSONObject();
                    o.put("path", full);
                    o.put("name", f.getName());
                    o.put("kind", kind);
                    list.put(o);
                }
                JSONObject out = new JSONObject();
                out.put("folders", list);
                return out;
            } finally {
                store.close();
            }
        });
    }

    /** Searches all of an account's mail on the server, not just what's on the phone. */
    @JavascriptInterface
    public void search(final String id, final String json) {
        runJob(id, () -> {
            JSONObject o = new JSONObject(json);
            String q = o.optString("query").trim();
            JSONObject c = account(o.optString("account"));
            Store store = Mail.openStore(activity, c);
            try {
                boolean gmail = c.optString("imapHost").contains("gmail");
                Folder all = gmail ? Mail.special(store, "\\All", Mail.ALL_NAMES) : null;
                IMAPFolder f = (IMAPFolder) (all != null ? all : store.getFolder("INBOX"));
                f.open(Folder.READ_ONLY);
                SearchTerm term = new OrTerm(new SearchTerm[]{new SubjectTerm(q), new FromStringTerm(q), new BodyTerm(q)});
                Message[] hits = f.search(term);
                int from = Math.max(0, hits.length - 25);
                Message[] newest = new Message[hits.length - from];
                System.arraycopy(hits, from, newest, 0, newest.length);
                JSONArray list = newest.length > 0 ? describeAll(f, newest, f.getFullName()) : new JSONArray();
                f.close(false);
                JSONObject out = new JSONObject();
                out.put("mails", list);
                return out;
            } finally {
                store.close();
            }
        });
    }

    /** Saves an attachment and opens it in the app the phone uses for that kind of file. */
    @JavascriptInterface
    public void attachment(final String id, final String json) {
        runJob(id, () -> {
            JSONObject o = new JSONObject(json);
            Store store = Mail.openStore(activity, account(o.optString("account")));
            File file;
            String type;
            try {
                IMAPFolder f = (IMAPFolder) Mail.folder(store, o.optString("folder", "inbox"));
                if (f == null) throw new Exception("That folder is gone.");
                f.open(Folder.READ_ONLY);
                Message m = f.getMessageByUID(o.getLong("uid"));
                if (m == null) throw new Exception("That email isn't there anymore.");
                List<Part> parts = new ArrayList<>();
                Mail.Body b = new Mail.Body();
                Mail.read(m, b, 0, parts);
                int idx = o.optInt("idx");
                if (idx < 0 || idx >= parts.size()) throw new Exception("That attachment isn't there anymore.");
                JSONObject meta = b.atts.getJSONObject(idx);
                type = meta.optString("type", "application/octet-stream");
                File dir = new File(activity.getCacheDir(), "att");
                if (!dir.exists()) dir.mkdirs();
                File[] old = dir.listFiles();
                if (old != null) for (File x : old) if (System.currentTimeMillis() - x.lastModified() > 86400000L) x.delete();
                String safe = meta.optString("name", "file").replaceAll("[\\\\/:*?\"<>|]", "_");
                file = new File(dir, System.currentTimeMillis() + "_" + safe);
                try (InputStream in = parts.get(idx).getInputStream(); OutputStream out = new FileOutputStream(file)) {
                    byte[] buf = new byte[65536];
                    int n;
                    while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
                }
                f.close(false);
            } finally {
                store.close();
            }
            Uri uri = Uri.parse("content://" + AttachmentProvider.AUTHORITY + "/" + Uri.encode(file.getName()));
            Intent view = new Intent(Intent.ACTION_VIEW);
            view.setDataAndType(uri, type);
            view.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
            final Intent chooser = Intent.createChooser(view, "Open with");
            chooser.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_GRANT_READ_URI_PERMISSION);
            activity.runOnUiThread(() -> {
                try {
                    activity.startActivity(chooser);
                } catch (Exception ignored) {
                }
            });
            return new JSONObject();
        });
    }

    /* ================= changing ================= */

    @JavascriptInterface
    public void setFlags(final String id, final String json) {
        runJob(id, () -> {
            JSONObject o = new JSONObject(json);
            Store store = Mail.openStore(activity, account(o.optString("account")));
            try {
                IMAPFolder f = (IMAPFolder) Mail.folder(store, o.optString("folder", "inbox"));
                if (f == null) return new JSONObject();
                f.open(Folder.READ_WRITE);
                Message[] msgs = existing(f.getMessagesByUID(uids(o.getJSONArray("uids"))));
                if (msgs.length > 0 && o.has("seen")) f.setFlags(msgs, new Flags(Flags.Flag.SEEN), o.getBoolean("seen"));
                if (msgs.length > 0 && o.has("flagged")) f.setFlags(msgs, new Flags(Flags.Flag.FLAGGED), o.getBoolean("flagged"));
                f.close(false);
            } finally {
                store.close();
            }
            return new JSONObject();
        });
    }

    /** Archive, delete, or put back in the inbox. "from" is the folder they're in now (the inbox by default). */
    @JavascriptInterface
    public void move(final String id, final String json) {
        runJob(id, () -> {
            JSONObject o = new JSONObject(json);
            String to = o.optString("to"), from = o.optString("from", "inbox");
            JSONObject c = account(o.optString("account"));
            boolean gmail = c.optString("imapHost").contains("gmail");
            Store store = Mail.openStore(activity, c);
            try {
                IMAPFolder src = (IMAPFolder) Mail.folder(store, from);
                if (src == null) throw new Exception("That folder is gone.");
                src.open(Folder.READ_WRITE);
                Message[] msgs = existing(src.getMessagesByUID(uids(o.getJSONArray("uids"))));
                if (msgs.length > 0) {
                    boolean gmailArchive = gmail && "archive".equals(to) && "inbox".equalsIgnoreCase(from);
                    if (!gmailArchive) {
                        Folder dest = Mail.folder(store, to);
                        if (dest == null) {
                            dest = store.getFolder("trash".equals(to) ? "Trash" : "Archive");
                            if (!dest.exists()) dest.create(Folder.HOLDS_MESSAGES);
                        }
                        src.copyMessages(msgs, dest);
                    }
                    src.setFlags(msgs, new Flags(Flags.Flag.DELETED), true);
                }
                src.close(true);
            } finally {
                store.close();
            }
            return new JSONObject();
        });
    }

    @JavascriptInterface
    public void send(final String id, final String json) {
        runJob(id, () -> {
            JSONObject o = new JSONObject(json), c = account(o.optString("account"));
            Session s = Mail.session(c);
            MimeMessage msg = new MimeMessage(s);
            msg.setFrom(new InternetAddress(c.optString("email")));
            msg.setRecipients(Message.RecipientType.TO, InternetAddress.parse(o.getString("to")));
            msg.setSubject(o.optString("subject", ""), "UTF-8");
            String body = o.optString("body", "");
            JSONArray atts = o.optJSONArray("attachments");
            if (atts != null && atts.length() > 0) {
                MimeMultipart mp = new MimeMultipart();
                MimeBodyPart text = new MimeBodyPart();
                text.setText(body, "UTF-8");
                mp.addBodyPart(text);
                for (int i = 0; i < atts.length(); i++) {
                    JSONObject a = atts.getJSONObject(i);
                    MimeBodyPart part = new MimeBodyPart();
                    byte[] data = Base64.decode(a.getString("data"), Base64.DEFAULT);
                    part.setDataHandler(new DataHandler(new ByteArrayDataSource(data, a.optString("type", "application/octet-stream"))));
                    part.setFileName(MimeUtility.encodeText(a.optString("name", "file"), "UTF-8", null));
                    part.setDisposition(Part.ATTACHMENT);
                    mp.addBodyPart(part);
                }
                msg.setContent(mp);
            } else {
                msg.setText(body, "UTF-8");
            }
            String inReplyTo = o.optString("inReplyTo", "");
            if (!inReplyTo.isEmpty()) {
                msg.setHeader("In-Reply-To", inReplyTo);
                msg.setHeader("References", inReplyTo);
            }
            msg.setSentDate(new Date());
            msg.saveChanges();
            Transport t = s.getTransport("smtp");
            try {
                t.connect(c.optString("smtpHost"), c.optInt("smtpPort", 465), c.optString("email"), Mail.secret(activity, c));
                t.sendMessage(msg, msg.getAllRecipients());
            } finally {
                t.close();
            }
            // Gmail and Outlook file sent mail by themselves; other providers need a copy in their Sent folder
            String host = c.optString("imapHost");
            if (!host.contains("gmail") && !host.contains("office365") && !host.contains("outlook")) {
                try {
                    Store st = Mail.openStore(activity, c);
                    try {
                        Folder sent = Mail.special(st, "\\Sent", Mail.SENT_NAMES);
                        if (sent != null) {
                            msg.setFlag(Flags.Flag.SEEN, true);
                            sent.appendMessages(new Message[]{msg});
                        }
                    } finally {
                        st.close();
                    }
                } catch (Exception ignored) {
                    // the email was sent; only the copy in Sent is missing
                }
            }
            return new JSONObject();
        });
    }

    /* ================= web requests (calendar links, unsubscribe) ================= */

    @JavascriptInterface
    public void httpGet(final String id, final String json) {
        runJob(id, () -> {
            String url = new JSONObject(json).getString("url").replaceFirst("^webcals?://", "https://");
            String[] res = Mail.http("GET", url, null, null);
            if (!"200".equals(res[0])) throw new Exception("The link answered " + res[0]);
            JSONObject o = new JSONObject();
            o.put("text", res[1]);
            return o;
        });
    }

    @JavascriptInterface
    public void httpPost(final String id, final String json) {
        runJob(id, () -> {
            JSONObject o = new JSONObject(json);
            String[] res = Mail.http("POST", o.getString("url"), o.optString("body", ""), o.optString("type", "application/x-www-form-urlencoded"));
            int code = Integer.parseInt(res[0]);
            if (code >= 400) throw new Exception("The website answered " + code);
            JSONObject out = new JSONObject();
            out.put("status", code);
            return out;
        });
    }

    /* ================= phone and PC sync through a hidden folder in your mailbox ================= */

    @JavascriptInterface
    public void syncPut(final String id, final String json) {
        runJob(id, () -> {
            JSONObject o = new JSONObject(json), c = account(o.optString("account"));
            Store store = Mail.openStore(activity, c);
            try {
                Folder f = store.getFolder(Mail.SYNC_FOLDER);
                if (!f.exists()) f.create(Folder.HOLDS_MESSAGES);
                f.open(Folder.READ_WRITE);
                Message[] old = f.getMessages();
                if (old.length > 0) {
                    if (c.optString("imapHost").contains("gmail")) {
                        Folder trash = Mail.special(store, "\\Trash", Mail.TRASH_NAMES);
                        if (trash != null) f.copyMessages(old, trash);
                    }
                    f.setFlags(old, new Flags(Flags.Flag.DELETED), true);
                }
                f.close(true);
                MimeMessage msg = new MimeMessage(Mail.session(c));
                msg.setFrom(new InternetAddress(c.optString("email")));
                msg.setRecipients(Message.RecipientType.TO, InternetAddress.parse(c.optString("email")));
                msg.setSubject("VEX sync data (VEX uses this, please keep it)", "UTF-8");
                msg.setText(o.optString("data", "{}"), "UTF-8");
                msg.setSentDate(new Date());
                msg.setFlag(Flags.Flag.SEEN, true);
                msg.saveChanges();
                f.appendMessages(new Message[]{msg});
            } finally {
                store.close();
            }
            return new JSONObject();
        });
    }

    @JavascriptInterface
    public void syncGet(final String id, final String json) {
        runJob(id, () -> {
            Store store = Mail.openStore(activity, account(new JSONObject(json).optString("account")));
            JSONObject out = new JSONObject();
            out.put("data", "");
            try {
                Folder f = store.getFolder(Mail.SYNC_FOLDER);
                if (f.exists()) {
                    f.open(Folder.READ_ONLY);
                    int n = f.getMessageCount();
                    if (n > 0) {
                        Mail.Body b = new Mail.Body();
                        Mail.read(f.getMessage(n), b, 0, null);
                        out.put("data", b.plain == null ? "" : b.plain);
                    }
                    f.close(false);
                }
            } finally {
                store.close();
            }
            return out;
        });
    }

    /* ================= notifications, reminders, widget ================= */

    /** Notification settings from the app: on, quiet hours, important senders and focus times. */
    @JavascriptInterface
    public void setNotify(String json) {
        try {
            JSONObject cfg = new JSONObject(json);
            prefs.edit().putString("notify", cfg.toString()).apply();
            Mail.scheduleChecks(activity, cfg.optBoolean("on") || cfg.optBoolean("widget"));
        } catch (Exception ignored) {
        }
    }

    @JavascriptInterface
    public void askNotify() {
        if (Build.VERSION.SDK_INT >= 33) {
            activity.runOnUiThread(() -> activity.requestPermissions(new String[]{"android.permission.POST_NOTIFICATIONS"}, 7));
        }
    }

    @JavascriptInterface
    public void setReminders(String json) {
        try {
            Mail.scheduleReminders(activity, new JSONArray(json));
        } catch (Exception ignored) {
        }
    }

    @JavascriptInterface
    public void updateWidget(String json) {
        try {
            prefs.edit().putString("widget", new JSONObject(json).toString()).apply();
            VexWidget.refresh(activity);
        } catch (Exception ignored) {
        }
    }

    /* ================= fingerprint unlock ================= */

    @JavascriptInterface
    public boolean canBiometric() {
        if (Build.VERSION.SDK_INT < 29) return false;
        try {
            BiometricManager bm = activity.getSystemService(BiometricManager.class);
            return bm != null && bm.canAuthenticate() == BiometricManager.BIOMETRIC_SUCCESS;
        } catch (Exception e) {
            return false;
        }
    }

    @JavascriptInterface
    public void biometric(final String id, final String json) {
        if (Build.VERSION.SDK_INT < 29) {
            resolve(id, error("none", "Fingerprint unlock isn't available on this phone."));
            return;
        }
        activity.runOnUiThread(() -> {
            try {
                BiometricPrompt prompt = new BiometricPrompt.Builder(activity)
                        .setTitle("Unlock VEX")
                        .setNegativeButton("Use PIN", activity.getMainExecutor(), (dialog, which) -> resolve(id, error("cancel", "Use your PIN")))
                        .build();
                prompt.authenticate(new CancellationSignal(), activity.getMainExecutor(), new BiometricPrompt.AuthenticationCallback() {
                    @Override
                    public void onAuthenticationSucceeded(BiometricPrompt.AuthenticationResult result) {
                        resolve(id, okResult());
                    }

                    @Override
                    public void onAuthenticationError(int code, CharSequence message) {
                        resolve(id, error("cancel", String.valueOf(message)));
                    }
                });
            } catch (Exception e) {
                resolve(id, error("none", "Fingerprint unlock isn't available."));
            }
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

    @JavascriptInterface
    public void checkUpdate(final String id, final String json) {
        runJob(id, () -> {
            String repo = new JSONObject(json).getString("repo");
            String[] res = Mail.http("GET", "https://api.github.com/repos/" + repo + "/releases?per_page=20", null, null);
            if (!"200".equals(res[0])) throw new Exception("GitHub answered " + res[0]);
            // newest release that has an Android app in it (PC releases are skipped)
            JSONArray releases = new JSONArray(res[1]);
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
                HttpURLConnection c = Mail.open(url);
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
