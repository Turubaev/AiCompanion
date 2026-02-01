#!/usr/bin/env node
/**
 * Локальный запуск MCP-сервера по TCP.
 * Отдаёт все инструменты: control_android_emulator (локально) + Tinkoff/Telegram/GitHub (нужны токены в .env или env).
 *
 * Использование: node run-tcp.js [PORT]
 * По умолчанию порт 8080. В приложении укажите хост 10.0.2.2 и этот порт.
 */

import net from "net";
import { spawn } from "child_process";
import path from "path";
import { fileURLToPath } from "url";
import fs from "fs";

const __dirname = path.dirname(fileURLToPath(import.meta.url));
const PORT = parseInt(process.env.MCP_TCP_PORT || process.argv[2] || "8080", 10);

// Подгрузка .env из mcp-unified/.env (токены Tinkoff, Telegram, GitHub — те же, что на VPS)
const envPath = path.join(__dirname, ".env");
if (fs.existsSync(envPath)) {
  const content = fs.readFileSync(envPath, "utf8");
  for (const line of content.split("\n")) {
    const m = line.match(/^\s*([A-Za-z_][A-Za-z0-9_]*)\s*=\s*(.*)$/);
    if (m) process.env[m[1]] = m[2].replace(/^["']|["']$/g, "").trim();
  }
}
// Fallback ANDROID_HOME на Windows (для control_android_emulator)
if (!process.env.ANDROID_HOME && process.platform === "win32" && process.env.LOCALAPPDATA) {
  const sdk = path.join(process.env.LOCALAPPDATA, "Android", "Sdk");
  if (fs.existsSync(path.join(sdk, "platform-tools", "adb.exe"))) {
    process.env.ANDROID_HOME = sdk;
  }
}

const server = net.createServer((socket) => {
  const child = spawn("node", ["index.js"], {
    cwd: __dirname,
    stdio: ["pipe", "pipe", "inherit"],
    env: { ...process.env },
  });

  socket.pipe(child.stdin);
  child.stdout.pipe(socket);

  socket.on("error", () => { try { child.kill(); } catch (_) {} });
  child.on("error", () => { try { socket.destroy(); } catch (_) {} });
  socket.on("close", () => { try { child.kill(); } catch (_) {} });
  child.on("exit", () => { try { socket.destroy(); } catch (_) {} });
});

server.listen(PORT, "0.0.0.0", () => {
  console.error(`MCP server listening on 0.0.0.0:${PORT}`);
  console.error(`For emulator: in app set MCP host to 10.0.2.2, port ${PORT}`);
  console.error(`Recordings: ${path.join(__dirname, "emulator", "recordings")}`);
});

server.on("error", (err) => {
  console.error("Server error:", err.message);
  process.exit(1);
});
