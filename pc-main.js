// VEX for PC: the same VEX app in a desktop window, with real email over IMAP and SMTP.
const { app, BrowserWindow, ipcMain, shell, safeStorage, Menu } = require("electron");
const path = require("path");
const fs = require("fs");
const os = require("os");
const { spawn } = require("child_process");
const { ImapFlow } = require("imapflow");
const { simpleParser } = require("mailparser");
const nodemailer = require("nodemailer");

const BUILD = (() => { try { return Number(require("./build-number.json").build) || 0; } catch (e) { return 0; } })();
const storeFile = () => path.join(app.getPath("userData"), "accounts.json");

/* Connected accounts live in a file on this computer. Passwords are encrypted with Windows' own protection. */
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
function loadAccounts() {
  try { return JSON.parse(fs.readFileSync(storeFile(), "utf8")).map(a => ({ ...a, password: decrypt(a.password || "") })); } catch (e) { return []; }
}
function saveAccounts(list) {
  fs.mkdirSync(path.dirname(storeFile()), { recursive: true });
  fs.writeFileSync(storeFile(), JSON.stringify(list.map(a => ({ ...a, password: encrypt(a.password || "") }))));
}
function account(id) {
  const list = loadAccounts(), a = id ? list.find(x => x.id === id) : list[0];
  if (!a) throw new Error("That email address isn't connected anymore.");
  return a;
}

async function withImap(a, fn) {
  const client = new ImapFlow({ host: a.imapHost, port: a.imapPort || 993, secure: true, auth: { user: a.email, pass: a.password }, logger: false, connectionTimeout: 20000, greetingTimeout: 20000, socketTimeout: 60000 });
  await client.connect();
  try { return await fn(client); } finally { try { await client.logout(); } catch (e) {} }
}
async function inInbox(a, fn) {
  return withImap(a, async c => { const lock = await c.getMailboxLock("INBOX"); try { return await fn(c); } finally { lock.release(); } });
}
const isAuthError = e => !!(e && (e.authenticationFailed || /AUTHENTICATIONFAILED|Invalid credentials|authentication failed|Username and Password not accepted|\b535\b/i.test(`${e.responseText || ""} ${e.response || ""} ${e.message || ""}`)));
function handle(name, fn) {
  ipcMain.handle("vex:" + name, async (_event, json) => {
    try { return JSON.stringify({ ok: true, ...((await fn(JSON.parse(json || "{}"))) || {}) }); }
    catch (e) { return JSON.stringify(isAuthError(e) ? { ok: false, code: "auth", message: "The email address or app password is wrong." } : { ok: false, code: "failed", message: (e && e.message) || String(e) }); }
  });
}
function tidy(t) {
  t = String(t || "").replace(/\r\n/g, "\n").replace(/[ \t]+\n/g, "\n").replace(/\n{3,}/g, "\n\n").trim();
  return t.length > 6000 ? t.slice(0, 6000) + "…" : t;
}

ipcMain.on("vex:status", e => {
  const list = loadAccounts();
  e.returnValue = JSON.stringify({ connected: list.length > 0, email: list[0] ? list[0].email : "", accounts: list.map(a => ({ id: a.id, email: a.email })) });
});
ipcMain.on("vex:appBuild", e => { e.returnValue = String(BUILD); });
ipcMain.on("vex:removeAccount", (e, id) => { saveAccounts(loadAccounts().filter(a => a.id !== id)); e.returnValue = true; });
ipcMain.on("vex:disconnect", e => { saveAccounts([]); e.returnValue = true; });

handle("connect", async c => {
  await withImap(c, async () => {});
  const list = loadAccounts(), same = list.find(a => a.email.toLowerCase() === String(c.email).toLowerCase());
  const id = same ? same.id : "a" + Date.now();
  saveAccounts(list.filter(a => a !== same).concat([{ ...c, id }]));
  return { id, email: c.email };
});

handle("fetch", async o => {
  const a = account(o.account), limit = o.limit || 40;
  const mails = await inInbox(a, async c => {
    const total = c.mailbox && c.mailbox.exists;
    if (!total) return [];
    const out = [];
    for await (const msg of c.fetch(`${Math.max(1, total - limit + 1)}:*`, { uid: true, flags: true, envelope: true, internalDate: true, source: true })) {
      let parsed = null;
      try { parsed = await simpleParser(msg.source); } catch (e) {}
      const env = msg.envelope || {}, from = (env.from && env.from[0]) || {};
      out.push({
        uid: msg.uid, from: from.name || from.address || "", email: from.address || "", subject: env.subject || "(no subject)",
        time: new Date(msg.internalDate || env.date || Date.now()).getTime(),
        seen: !!(msg.flags && msg.flags.has("\\Seen")), flagged: !!(msg.flags && msg.flags.has("\\Flagged")),
        messageId: env.messageId || "", body: tidy(parsed && parsed.text), clip: !!(parsed && parsed.attachments && parsed.attachments.length)
      });
    }
    return out.reverse();
  });
  return { mails };
});

handle("setFlags", async o => {
  const a = account(o.account), range = (o.uids || []).join(",");
  if (!range) return {};
  await inInbox(a, async c => {
    for (const [key, flag] of [["seen", "\\Seen"], ["flagged", "\\Flagged"]]) {
      if (!(key in o)) continue;
      if (o[key]) await c.messageFlagsAdd(range, [flag], { uid: true }); else await c.messageFlagsRemove(range, [flag], { uid: true });
    }
  });
  return {};
});

handle("move", async o => {
  const a = account(o.account), range = (o.uids || []).join(","), trash = o.to === "trash";
  if (!range) return {};
  await withImap(a, async c => {
    const folders = await c.list();
    const special = use => (folders.find(f => f.specialUse === use) || {}).path;
    let dest = trash ? special("\\Trash") : special("\\Archive") || (/gmail/i.test(a.imapHost) ? special("\\All") : null);
    if (!dest) { dest = trash ? "Trash" : "Archive"; if (!folders.some(f => f.path === dest)) await c.mailboxCreate(dest); }
    const lock = await c.getMailboxLock("INBOX");
    try { await c.messageMove(range, dest, { uid: true }); } finally { lock.release(); }
  });
  return {};
});

handle("send", async o => {
  const a = account(o.account), starttls = a.smtpSecurity === "starttls";
  const transport = nodemailer.createTransport({ host: a.smtpHost, port: a.smtpPort || 465, secure: !starttls, requireTLS: starttls, auth: { user: a.email, pass: a.password } });
  await transport.sendMail({ from: a.email, to: o.to, subject: o.subject || "", text: o.body || "", inReplyTo: o.inReplyTo || undefined, references: o.inReplyTo || undefined });
  return {};
});

/* Updates: the newest VEX-Setup.exe from GitHub Releases */
handle("checkUpdate", async o => {
  const r = await fetch(`https://api.github.com/repos/${o.repo}/releases/latest`, { headers: { "User-Agent": "VEX-Mail", Accept: "application/vnd.github+json" } });
  if (!r.ok) throw new Error(`GitHub answered ${r.status}`);
  const rel = await r.json(), asset = (rel.assets || []).find(x => /\.exe$/i.test(x.name));
  return { current: BUILD, latest: Number(String(rel.tag_name || "").replace(/\D/g, "")) || 0, url: asset ? asset.browser_download_url : "" };
});
handle("installUpdate", async o => {
  const r = await fetch(o.url, { headers: { "User-Agent": "VEX-Mail" } });
  if (!r.ok) throw new Error(`Download failed (${r.status})`);
  const file = path.join(os.tmpdir(), "VEX-Setup.exe");
  fs.writeFileSync(file, Buffer.from(await r.arrayBuffer()));
  spawn(file, [], { detached: true, stdio: "ignore" }).unref();
  setTimeout(() => app.quit(), 1500);
  return { status: "started" };
});

function createWindow() {
  Menu.setApplicationMenu(null);
  const win = new BrowserWindow({
    width: 1280, height: 860, minWidth: 380, minHeight: 600, backgroundColor: "#000000", title: "VEX", icon: path.join(__dirname, "icon.png"),
    webPreferences: { preload: path.join(__dirname, "preload.js"), contextIsolation: true, nodeIntegration: false }
  });
  win.loadFile(path.join(__dirname, "index.html"));
  const external = url => /^(https?|mailto):/i.test(url);
  win.webContents.setWindowOpenHandler(({ url }) => { if (external(url)) shell.openExternal(url); return { action: "deny" }; });
  win.webContents.on("will-navigate", (e, url) => { if (external(url)) { e.preventDefault(); shell.openExternal(url); } });
}
if (!app.requestSingleInstanceLock()) app.quit();
else {
  app.on("second-instance", () => { const w = BrowserWindow.getAllWindows()[0]; if (w) { if (w.isMinimized()) w.restore(); w.focus(); } });
  app.whenReady().then(createWindow);
  app.on("window-all-closed", () => app.quit());
}
