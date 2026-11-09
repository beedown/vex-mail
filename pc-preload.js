// Gives the VEX page the same "VexNative" helper the Android app has.
const { contextBridge, ipcRenderer } = require("electron");
const call = name => (id, json) => ipcRenderer.invoke("vex:" + name, json);
contextBridge.exposeInMainWorld("VexNative", {
  platform: "pc",
  status: () => ipcRenderer.sendSync("vex:status"),
  appBuild: () => ipcRenderer.sendSync("vex:appBuild"),
  removeAccount: id => ipcRenderer.sendSync("vex:removeAccount", id),
  disconnect: () => ipcRenderer.sendSync("vex:disconnect"),
  setTray: on => ipcRenderer.sendSync("vex:setTray", on),
  connect: call("connect"), oauth: call("oauth"), fetch: call("fetch"), message: call("message"), folders: call("folders"), search: call("search"),
  attachment: call("attachment"), setFlags: call("setFlags"), move: call("move"), send: call("send"),
  httpGet: call("httpGet"), httpPost: call("httpPost"), syncPut: call("syncPut"), syncGet: call("syncGet"),
  checkUpdate: call("checkUpdate"), installUpdate: call("installUpdate")
});
