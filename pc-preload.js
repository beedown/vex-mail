// Gives the VEX page the same "VexNative" helper the Android app has.
const { contextBridge, ipcRenderer } = require("electron");
const call = name => (id, json) => ipcRenderer.invoke("vex:" + name, json);
contextBridge.exposeInMainWorld("VexNative", {
  platform: "pc",
  status: () => ipcRenderer.sendSync("vex:status"),
  appBuild: () => ipcRenderer.sendSync("vex:appBuild"),
  removeAccount: id => ipcRenderer.sendSync("vex:removeAccount", id),
  disconnect: () => ipcRenderer.sendSync("vex:disconnect"),
  connect: call("connect"), fetch: call("fetch"), setFlags: call("setFlags"), move: call("move"), send: call("send"),
  checkUpdate: call("checkUpdate"), installUpdate: call("installUpdate"), httpGet: call("httpGet")
});
