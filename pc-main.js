// VEX for PC: the same VEX app in a desktop window, with real email over IMAP and SMTP.
const { app, BrowserWindow, ipcMain, shell, safeStorage, Menu, Tray, nativeImage } = require("electron");
const path = require("path");
const fs = require("fs");
const os = require("os");
const http = require("http");
const crypto = require("crypto");
const { spawn } = require("child_process");
const { ImapFlow } = require("imapflow");
const { simpleParser } = require("mailparser");
const nodemailer = require("nodemailer");
const MailComposer = require("nodemailer/lib/mail-composer");

const BUILD = (() => { try { return Number(require("./build-number.json").build) || 0; } catch (e) { return 0; } })();
const MS_AUTH = "https://login.microsoftonline.com/common/oauth2/v2.0/";
const MS_SCOPE = "offline_access https://outlook.office.com/IMAP.AccessAsUser.All https://outlook.office.com/SMTP.Send openid email";
const SYNC_FOLDER = "VEX Sync";
const SENT_NAMES = ["[Gmail]/Sent Mail", "[Google Mail]/Sent Mail", "Sent", "Sent Items", "Sent Messages", "Sent Mail", "Verzonden items", "Verzonden"];
const TRASH_NAMES = ["[Gmail]/Trash", "[Gmail]/Bin", "Trash", "Deleted", "Deleted Items", "Deleted Messages", "Bin", "Prullenbak"];
const ARCHIVE_NAMES = ["Archive", "Archives", "Archief"];
const storeFile = () => path.join(app.getPath("userData"), "accounts.json");
let win = null, tray = null, keepInTray = true, quitting = false;

/* Connected accounts live in a file on this computer. Passwords and sign-ins are encrypted with Windows' own protection. */
function encrypt(s) {
  try { if (safeStorage.isEncryptionAvailable()) return "enc:" + safeStorage.encryptString(s).toString("base64"); } catch (e) {}
  return "raw:" + Buffer.from(s, "utf8").toString("base64");
}
function decrypt(s) {
  try {
    if (s.startsWith("enc:")) return safeStorage.decryptString(Buffer.from(s.slice(4), "base64"));
    if (s.startsWith("raw:")) return Buffer.from(s.slice(4), "base64").toString("utf8");
  } catch (e) {}
  return "";
}
const SECRET_KEYS = ["password", "refresh", "access"];
function loadAccounts() {
  try {
    return JSON.parse(fs.readFileSync(storeFile(), "utf8")).map(a => { const o = { ...a }; for (const k of SECRET_KEYS) if (o[k]) o[k] = decrypt(o[k]); return o; });
  } catch (e) { return []; }
}
function saveAccounts(list) {
  fs.mkdirSync(path.dirname(storeFile()), { recursive: true });
  fs.writeFileSync(storeFile(), JSON.stringify(list.map(a => { const o = { ...a }; for (const k of SECRET_KEYS) if (o[k]) o[k] = encrypt(o[k]); return o; })));
}
function account(id) {
  const list = loadAccounts(), a = id ? list.find(x => x.id === id) : list[0];
  if (!a) throw new Error("That email address isn't connected anymore.");
  return a;
}
function addAccount(c) {
  const list = loadAccounts(), same = list.find(a => a.email.toLowerCase() === String(c.email).toLowerCase());
  const id = same ? same.id : "a" + Date.now().toString(36) + Math.random().toString(36).slice(2, 6);
  saveAccounts(list.filter(a => a !== same).concat([{ ...c, id }]));
  return id;
}
function updateAccount(c) { saveAccounts(loadAccounts().map(a => a.id === c.id ? c : a)); }

/* Microsoft sign-in tokens for Hotmail and Outlook accounts */
async function msToken(form) {
  const r = await fetch(MS_AUTH + "token", { method: "POST", headers: { "Content-Type": "application/x-www-form-urlencoded" }, body: new URLSearchParams(form).toString() });
  return r.json();
}
async function accessToken(a) {
  if (a.auth !== "oauth") return null;
  if (a.access && a.expires - 120000 > Date.now()) return a.access;
  const t = await msToken({ client_id: a.clientId, grant_type: "refresh_token", refresh_token: a.refresh, scope: MS_SCOPE });
  if (!t.access_token) throw Object.assign(new Error("Your Microsoft sign-in ran out. Remove the account and sign in again."), { authenticationFailed: true, msExpired: true });
  Object.assign(a, { access: t.access_token, expires: Date.now() + (t.expires_in || 3600) * 1000, refresh: t.refresh_token || a.refresh });
  if (a.id) updateAccount(a);
  return a.access;
}
const emailFromIdToken = t => { try { const c = JSON.parse(Buffer.from(String(t).split(".")[1], "base64url").toString("utf8")); return c.email || c.preferred_username || ""; } catch (e) { return ""; } };

async function withImap(a, fn) {
  const token = await accessToken(a);
  const client = new ImapFlow({ host: a.imapHost, port: a.imapPort || 993, secure: true, auth: token ? { user: a.email, accessToken: token } : { user: a.email, pass: a.password }, logger: false, connectionTimeout: 20000, greetingTimeout: 20000, socketTimeout: 60000 });
  await client.connect();
  try { return await fn(client); } finally { try { await client.logout(); } catch (e) {} }
}
async function folderPath(c, which) {
  if (!which || which === "inbox") return "INBOX";
  const names = { sent: ["\\Sent", SENT_NAMES], trash: ["\\Trash", TRASH_NAMES], archive: ["\\Archive", ARCHIVE_NAMES], all: ["\\All", ["[Gmail]/All Mail", "[Google Mail]/All Mail"]] }[which];
  const folders = await c.list();
  if (names) {
    const f = folders.find(x => x.specialUse === names[0]) || folders.find(x => names[1].some(n => n.toLowerCase() === String(x.path).toLowerCase()));
    return f ? f.path : null;
  }
  return folders.some(x => x.path === which) ? which : null;
}
async function inFolder(a, which, fn) {
  return withImap(a, async c => {
    const p = await folderPath(c, which); if (!p) return fn(c, null);
    const lock = await c.getMailboxLock(p);
    try { return await fn(c, p); } finally { lock.release(); }
  });
}
const isAuthError = e => !!(e && (e.authenticationFailed || /AUTHENTICATIONFAILED|Invalid credentials|authentication failed|Username and Password not accepted|\b535\b/i.test(`${e.responseText || ""} ${e.response || ""} ${e.message || ""}`)));
function handle(name, fn) {
  ipcMain.handle("vex:" + name, async (_event, json) => {
    try { return JSON.stringify({ ok: true, ...((await fn(JSON.parse(json || "{}"))) || {}) }); }
    catch (e) { return JSON.stringify(isAuthError(e) ? { ok: false, code: "auth", message: e.msExpired ? e.message : "The email address or app password is wrong." } : { ok: false, code: "failed", message: (e && e.message) || String(e) }); }
  });
}
function tidy(t) {
  t = String(t || "").replace(/\r\n/g, "\n").replace(/[ \t]+\n/g, "\n").replace(/\n{3,}/g, "\n\n").trim();
  return t.length > 6000 ? t.slice(0, 6000) + "…" : t;
}
// raw header lines are the most reliable way to read List-Unsubscribe across mail libraries
const rawHeader = (parsed, name) => { const l = ((parsed && parsed.headerLines) || []).find(h => h.key === name); return l ? l.line.replace(/^[^:]+:\s*/, "").replace(/\r?\n\s+/g, " ").trim() : ""; };
function describe(msg, parsed, folder) {
  const env = msg.envelope || {}, from = (env.from && env.from[0]) || {}, to = (env.to && env.to[0]) || {};
  const atts = ((parsed && parsed.attachments) || []).filter(x => !(x.contentDisposition === "inline" && x.cid)).map((x, idx) => ({ name: x.filename || "attachment", size: x.size || 0, type: x.contentType || "", idx }));
  return {
    uid: msg.uid, folder, from: from.name || from.address || "", email: from.address || "", to: to.name || to.address || "", toEmail: to.address || "",
    subject: env.subject || "(no subject)", time: new Date(msg.internalDate || env.date || Date.now()).getTime(),
    seen: !!(msg.flags && msg.flags.has("\\Seen")), flagged: !!(msg.flags && msg.flags.has("\\Flagged")),
    messageId: env.messageId || "", body: tidy(parsed && parsed.text), atts, clip: atts.length > 0, hasHtml: !!(parsed && parsed.html),
    unsub: rawHeader(parsed, "list-unsubscribe"), unsubPost: rawHeader(parsed, "list-unsubscribe-post")
  };
}
async function readRange(c, range, folder, byUid) {
  const out = [];
  for await (const msg of c.fetch(range, { uid: true, flags: true, envelope: true, internalDate: true, source: true }, byUid ? { uid: true } : undefined)) {
    let parsed = null;
    try { parsed = await simpleParser(msg.source); } catch (e) {}
    out.push(describe(msg, parsed, folder));
  }
  return out.reverse();
}

ipcMain.on("vex:status", e => {
  const list = loadAccounts();
  e.returnValue = JSON.stringify({ connected: list.length > 0, email: list[0] ? list[0].email : "", accounts: list.map(a => ({ id: a.id, email: a.email, microsoft: a.auth === "oauth" })) });
});
ipcMain.on("vex:appBuild", e => { e.returnValue = String(BUILD); });
ipcMain.on("vex:removeAccount", (e, id) => { saveAccounts(loadAccounts().filter(a => a.id !== id)); e.returnValue = true; });
ipcMain.on("vex:disconnect", e => { saveAccounts([]); e.returnValue = true; });
ipcMain.on("vex:setTray", (e, on) => { keepInTray = !!on; e.returnValue = true; });

handle("connect", async c => {
  await withImap(c, async () => {});
  return { id: addAccount(c), email: c.email };
});

/* Hotmail and Outlook: sign in on Microsoft's own page in your browser */
handle("oauth", async o => {
  const verifier = crypto.randomBytes(48).toString("base64url"), state = crypto.randomBytes(16).toString("base64url");
  const challenge = crypto.createHash("sha256").update(verifier).digest("base64url");
  const { code, redirect } = await new Promise((resolve, reject) => {
    const server = http.createServer((req, res) => {
      const url = new URL(req.url, "http://localhost");
      if (!url.searchParams.has("code") && !url.searchParams.has("error")) { res.writeHead(404); res.end(); return; }
      res.writeHead(200, { "Content-Type": "text/html; charset=utf-8" });
      res.end(`<body style="background:#000;color:#e9e1d3;font:18px sans-serif;display:grid;place-items:center;height:90vh"><p>${url.searchParams.has("code") ? "Signed in. You can close this tab and go back to VEX." : "Sign-in was cancelled. You can close this tab."}</p></body>`);
      clearTimeout(timer); server.close();
      if (url.searchParams.get("state") !== state) return reject(new Error("That sign-in didn't match. Try again."));
      if (url.searchParams.has("error")) return reject(new Error(url.searchParams.get("error_description") || "Microsoft didn't sign you in."));
      resolve({ code: url.searchParams.get("code"), redirect: `http://localhost:${server.address().port}` });
    });
    const timer = setTimeout(() => { server.close(); reject(new Error("Sign-in timed out. Try again.")); }, 5 * 60 * 1000);
    server.listen(0, "127.0.0.1", () => {
      const redirect = `http://localhost:${server.address().port}`;
      const q = new URLSearchParams({ client_id: o.clientId, response_type: "code", response_mode: "query", prompt: "select_account", redirect_uri: redirect, scope: MS_SCOPE, code_challenge: challenge, code_challenge_method: "S256", state });
      shell.openExternal(MS_AUTH + "authorize?" + q.toString());
    });
  });
  const t = await msToken({ client_id: o.clientId, grant_type: "authorization_code", code, redirect_uri: redirect, code_verifier: verifier, scope: MS_SCOPE });
  if (!t.access_token) throw new Error(t.error_description || "Microsoft didn't sign you in.");
  const email = emailFromIdToken(t.id_token);
  if (!email) throw new Error("Microsoft didn't share your email address. Try again.");
  const c = { auth: "oauth", provider: "outlook", clientId: o.clientId, email, access: t.access_token, refresh: t.refresh_token, expires: Date.now() + (t.expires_in || 3600) * 1000,
    imapHost: "outlook.office365.com", imapPort: 993, smtpHost: "smtp-mail.outlook.com", smtpPort: 587, smtpSecurity: "starttls" };
  await withImap(c, async () => {});
  return { id: addAccount(c), email };
});

handle("fetch", async o => {
  const a = account(o.account), limit = o.limit || 40, which = o.folder || "inbox";
  const mails = await inFolder(a, which, async (c, p) => {
    const total = p && c.mailbox && c.mailbox.exists;
    if (!total) return [];
    return readRange(c, `${Math.max(1, total - limit + 1)}:*`, which, false);
  });
  return { mails };
});

handle("message", async o => {
  const a = account(o.account);
  return inFolder(a, o.folder || "inbox", async (c, p) => {
    if (!p) throw new Error("That folder is gone.");
    const msg = await c.fetchOne(String(o.uid), { source: true }, { uid: true });
    if (!msg) throw new Error("That email isn't there anymore.");
    const parsed = await simpleParser(msg.source);
    const html = parsed.html || "";
    return { html: html.length > 400000 ? html.slice(0, 400000) : html, text: tidy(parsed.text) };
  });
});

handle("folders", async o => {
  const a = account(o.account);
  const folders = await withImap(a, c => c.list());
  return { folders: folders.filter(f => f.path !== "INBOX" && f.path !== SYNC_FOLDER && !(f.flags && f.flags.has("\\Noselect"))).map(f => ({ path: f.path, name: f.name, kind: (f.specialUse || "").replace("\\", "").toLowerCase() })) };
});

handle("search", async o => {
  const a = account(o.account), q = String(o.query || "").trim();
  const mails = await inFolder(a, /gmail/i.test(a.imapHost) ? "all" : "inbox", async (c, p) => {
    if (!p) return [];
    const uids = (await c.search({ or: [{ subject: q }, { from: q }, { body: q }] }, { uid: true })) || [];
    if (!uids.length) return [];
    return readRange(c, uids.slice(-25).join(","), p, true);
  });
  return { mails };
});

handle("attachment", async o => {
  const a = account(o.account);
  const file = await inFolder(a, o.folder || "inbox", async (c, p) => {
    if (!p) throw new Error("That folder is gone.");
    const msg = await c.fetchOne(String(o.uid), { source: true }, { uid: true });
    if (!msg) throw new Error("That email isn't there anymore.");
    const parsed = await simpleParser(msg.source);
    const list = (parsed.attachments || []).filter(x => !(x.contentDisposition === "inline" && x.cid));
    const att = list[o.idx || 0];
    if (!att) throw new Error("That attachment isn't there anymore.");
    const dir = path.join(os.tmpdir(), "VEX attachments");
    fs.mkdirSync(dir, { recursive: true });
    const out = path.join(dir, `${Date.now()}_${String(att.filename || "attachment").replace(/[\\/:*?"<>|]/g, "_")}`);
    fs.writeFileSync(out, att.content);
    return out;
  });
  const err = await shell.openPath(file);
  if (err) throw new Error(err);
  return {};
});

handle("setFlags", async o => {
  const a = account(o.account), range = (o.uids || []).join(",");
  if (!range) return {};
  await inFolder(a, o.folder || "inbox", async (c, p) => {
    if (!p) return;
    for (const [key, flag] of [["seen", "\\Seen"], ["flagged", "\\Flagged"]]) {
      if (!(key in o)) continue;
      if (o[key]) await c.messageFlagsAdd(range, [flag], { uid: true }); else await c.messageFlagsRemove(range, [flag], { uid: true });
    }
  });
  return {};
});

/* Archive, delete, or put back in the inbox ("from" is where they are now) */
handle("move", async o => {
  const a = account(o.account), range = (o.uids || []).join(","), from = o.from || "inbox", to = o.to;
  if (!range) return {};
  await withImap(a, async c => {
    const src = await folderPath(c, from); if (!src) throw new Error("That folder is gone.");
    let dest = await folderPath(c, to === "archive" && /gmail/i.test(a.imapHost) && src === "INBOX" ? "all" : to);
    if (!dest) { dest = to === "trash" ? "Trash" : "Archive"; await c.mailboxCreate(dest).catch(() => {}); }
    const lock = await c.getMailboxLock(src);
    try { await c.messageMove(range, dest, { uid: true }); } finally { lock.release(); }
  });
  return {};
});

handle("send", async o => {
  const a = account(o.account), starttls = a.smtpSecurity === "starttls", token = await accessToken(a);
  const transport = nodemailer.createTransport({ host: a.smtpHost, port: a.smtpPort || 465, secure: !starttls, requireTLS: starttls, auth: token ? { type: "OAuth2", user: a.email, accessToken: token } : { user: a.email, pass: a.password } });
  const attachments = (o.attachments || []).map(x => ({ filename: x.name, content: Buffer.from(x.data, "base64"), contentType: x.type || undefined }));
  const mail = { from: a.email, to: o.to, subject: o.subject || "", text: o.body || "", attachments, inReplyTo: o.inReplyTo || undefined, references: o.inReplyTo || undefined, date: new Date() };
  await transport.sendMail(mail);
  // Gmail and Outlook file sent mail by themselves; other providers need a copy in their Sent folder
  if (!/gmail|office365|outlook/i.test(a.imapHost || "")) {
    try {
      const raw = await new MailComposer(mail).compile().build();
      await withImap(a, async c => { const p = await folderPath(c, "sent"); if (p) await c.append(p, raw, ["\\Seen"]); });
    } catch (e) { /* the email was sent; only the copy in Sent is missing */ }
  }
  return {};
});

/* Web requests: calendar links and one-tap unsubscribe */
handle("httpGet", async o => {
  const url = String(o.url || "").replace(/^webcals?:\/\//i, "https://");
  const r = await fetch(url, { headers: { "User-Agent": "VEX-Mail" } });
  if (!r.ok) throw new Error(`The link answered ${r.status}`);
  return { text: (await r.text()).slice(0, 4000000) };
});
handle("httpPost", async o => {
  const r = await fetch(o.url, { method: "POST", headers: { "User-Agent": "VEX-Mail", "Content-Type": o.type || "application/x-www-form-urlencoded" }, body: o.body || "" });
  if (r.status >= 400) throw new Error(`The website answered ${r.status}`);
  return { status: r.status };
});

/* Phone and PC sync: VEX keeps one hidden email in a "VEX Sync" folder of your own mailbox */
handle("syncPut", async o => {
  const a = account(o.account);
  await withImap(a, async c => {
    const exists = (await c.list()).some(f => f.path === SYNC_FOLDER);
    if (!exists) await c.mailboxCreate(SYNC_FOLDER);
    const lock = await c.getMailboxLock(SYNC_FOLDER);
    try {
      if (c.mailbox && c.mailbox.exists) {
        if (/gmail/i.test(a.imapHost)) { const t = await folderPath(c, "trash"); if (t) await c.messageMove("1:*", t); }
        else await c.messageDelete("1:*");
      }
    } finally { lock.release(); }
    const raw = await new MailComposer({ from: a.email, to: a.email, subject: "VEX sync data (VEX uses this, please keep it)", text: o.data || "{}", date: new Date() }).compile().build();
    await c.append(SYNC_FOLDER, raw, ["\\Seen"]);
  });
  return {};
});
handle("syncGet", async o => {
  const a = account(o.account);
  const data = await withImap(a, async c => {
    if (!(await c.list()).some(f => f.path === SYNC_FOLDER)) return "";
    const lock = await c.getMailboxLock(SYNC_FOLDER);
    try {
      if (!c.mailbox || !c.mailbox.exists) return "";
      const msg = await c.fetchOne(String(c.mailbox.exists), { source: true });
      const parsed = await simpleParser(msg.source);
      return parsed.text || "";
    } finally { lock.release(); }
  });
  return { data };
});

/* Updates: the newest VEX-Setup.exe from GitHub Releases */
handle("checkUpdate", async o => {
  const r = await fetch(`https://api.github.com/repos/${o.repo}/releases?per_page=20`, { headers: { "User-Agent": "VEX-Mail", Accept: "application/vnd.github+json" } });
  if (!r.ok) throw new Error(`GitHub answered ${r.status}`);
  let best = null;  // newest release that has the PC installer in it (Android releases are skipped)
  for (const rel of await r.json()) {
    if (rel.draft || rel.prerelease) continue;
    const exe = (rel.assets || []).find(x => /\.exe$/i.test(x.name)); if (!exe) continue;
    const n = Number(String(rel.tag_name || "").replace(/\D/g, "")) || 0;
    if (!best || n > best.n) best = { n, url: exe.browser_download_url };
  }
  return { current: BUILD, latest: best ? best.n : 0, url: best ? best.url : "" };
});
handle("installUpdate", async o => {
  const r = await fetch(o.url, { headers: { "User-Agent": "VEX-Mail" } });
  if (!r.ok) throw new Error(`Download failed (${r.status})`);
  const file = path.join(os.tmpdir(), "VEX-Setup.exe");
  fs.writeFileSync(file, Buffer.from(await r.arrayBuffer()));
  spawn(file, [], { detached: true, stdio: "ignore" }).unref();
  setTimeout(() => { quitting = true; app.quit(); }, 1500);
  return { status: "started" };
});

function showWindow() { if (!win) return; if (win.isMinimized()) win.restore(); win.show(); win.focus(); }
function createWindow() {
  Menu.setApplicationMenu(null);
  win = new BrowserWindow({
    width: 1280, height: 860, minWidth: 380, minHeight: 600, backgroundColor: "#000000", title: "VEX", icon: path.join(__dirname, "icon.png"),
    webPreferences: { preload: path.join(__dirname, "preload.js"), contextIsolation: true, nodeIntegration: false, backgroundThrottling: false }
  });
  win.loadFile(path.join(__dirname, "index.html"));
  const external = url => /^(https?|mailto):/i.test(url);
  win.webContents.setWindowOpenHandler(({ url }) => { if (external(url)) shell.openExternal(url); return { action: "deny" }; });
  win.webContents.on("will-navigate", (e, url) => { if (external(url)) { e.preventDefault(); shell.openExternal(url); } });
  // closing the window keeps VEX running in the tray, so new-mail alerts and reminders still arrive
  win.on("close", e => { if (keepInTray && !quitting) { e.preventDefault(); win.hide(); } });
  try {
    tray = new Tray(nativeImage.createFromPath(path.join(__dirname, "icon.png")).resize({ width: 16, height: 16 }));
    tray.setToolTip("VEX");
    tray.setContextMenu(Menu.buildFromTemplate([{ label: "Open VEX", click: showWindow }, { label: "Quit VEX", click: () => { quitting = true; app.quit(); } }]));
    tray.on("click", showWindow);
  } catch (e) { keepInTray = false; }
}
if (!app.requestSingleInstanceLock()) app.quit();
else {
  app.setAppUserModelId("com.vexmail.pc");
  app.on("second-instance", showWindow);
  app.on("before-quit", () => { quitting = true; });
  app.whenReady().then(createWindow);
  app.on("window-all-closed", () => { if (!keepInTray) app.quit(); });
}
