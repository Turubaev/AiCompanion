/**
 * Обёртки над ADB и эмулятором для управления Android-устройством на VPS.
 * Все команды выполняются только для localhost (эмулятор).
 */

import { spawn } from "child_process";
import { mkdir } from "fs/promises";
import path from "path";
import { fileURLToPath } from "url";

const __filename = fileURLToPath(import.meta.url);
const __dirname = path.dirname(__filename);

const isWin = process.platform === "win32";
const exe = (name) => (isWin ? name + ".exe" : name);
const bat = (name) => (isWin ? name + ".bat" : name);

const DEFAULT_ADB = process.env.ANDROID_HOME
  ? path.join(process.env.ANDROID_HOME, "platform-tools", exe("adb"))
  : "adb";
const DEFAULT_EMULATOR = process.env.ANDROID_HOME
  ? path.join(process.env.ANDROID_HOME, "emulator", exe("emulator"))
  : "emulator";
const DEFAULT_AVDMANAGER = process.env.ANDROID_HOME
  ? path.join(process.env.ANDROID_HOME, "cmdline-tools", "latest", "bin", bat("avdmanager"))
  : "avdmanager";

const RECORDINGS_DIR = path.join(__dirname, "recordings");
const SCREENRECORD_PATH = "/sdcard/demo_recording.mp4";
const SCENARIO_TIMEOUT_MS = 5 * 60 * 1000; // 5 минут на весь сценарий
const RECORDING_MAX_AGE_DAYS = 7;

/**
 * Выполняет shell-команду через spawn, возвращает Promise<{ stdout, stderr, code }>.
 * @param {string} command - исполняемый файл
 * @param {string[]} args - аргументы
 * @param {object} options - { timeout, cwd, env }
 */
export function runCommand(command, args = [], options = {}) {
  const { timeout = 30_000, cwd, env = process.env } = options;
  return new Promise((resolve, reject) => {
    const proc = spawn(command, args, {
      cwd: cwd || undefined,
      env: { ...env },
      shell: false,
    });
    let stdout = "";
    let stderr = "";
    proc.stdout?.on("data", (chunk) => { stdout += chunk.toString(); });
    proc.stderr?.on("data", (chunk) => { stderr += chunk.toString(); });
    let settled = false;
    const timer = timeout > 0 ? setTimeout(() => {
      if (settled) return;
      settled = true;
      proc.kill("SIGKILL");
      reject(new Error(`Timeout after ${timeout}ms. stderr: ${stderr}`));
    }, timeout) : undefined;
    proc.on("error", (err) => {
      if (settled) return;
      settled = true;
      clearTimeout(timer);
      reject(err);
    });
    proc.on("close", (code, signal) => {
      if (settled) return;
      settled = true;
      clearTimeout(timer);
      resolve({ stdout: stdout.trim(), stderr: stderr.trim(), code, signal });
    });
  });
}

/**
 * Выполняет adb <args>. При нескольких устройствах автоматически выбирает эмулятор (-s emulator-5554).
 */
export async function adb(...args) {
  const cmdArgs = [...args];
  if (args[0] !== "devices" && args[0] !== "kill-server" && args[0] !== "start-server") {
    const deviceId = await getTargetDeviceId();
    if (deviceId) {
      cmdArgs.unshift("-s", deviceId);
    }
  }
  const { stdout, stderr, code } = await runCommand(DEFAULT_ADB, cmdArgs, { timeout: 60_000 });
  if (code !== 0) {
    throw new Error(`adb failed (${code}): ${stderr || stdout}`);
  }
  return { stdout, stderr };
}

/**
 * adb devices - список устройств (без -s, работает при нескольких устройствах)
 */
export async function adbDevices() {
  const { stdout, stderr, code } = await runCommand(DEFAULT_ADB, ["devices"], { timeout: 10_000 });
  if (code !== 0) throw new Error(`adb devices failed: ${stderr || stdout}`);
  const lines = stdout.split("\n").slice(1).filter(Boolean);
  return lines.map((line) => {
    const [id, state] = line.trim().split(/\s+/);
    return { id, state };
  });
}

/**
 * Возвращает ID устройства для adb -s. При нескольких устройствах выбирает эмулятор.
 * Можно задать ANDROID_SERIAL или ANDROID_EMULATOR_DEVICE в .env.
 */
async function getTargetDeviceId() {
  if (process.env.ANDROID_SERIAL || process.env.ANDROID_EMULATOR_DEVICE) {
    return process.env.ANDROID_SERIAL || process.env.ANDROID_EMULATOR_DEVICE;
  }
  const devices = (await adbDevices()).filter((d) => d.state === "device");
  if (devices.length === 0) return null;
  if (devices.length === 1) return devices[0].id;
  const emulator = devices.find((d) => d.id.startsWith("emulator-"));
  return emulator ? emulator.id : devices[0].id;
}

/**
 * Проверяет, есть ли хотя бы одно подключённое устройство (эмулятор)
 */
export async function isDeviceConnected() {
  const devices = await adbDevices();
  return devices.some((d) => d.state === "device");
}

/**
 * adb wait-for-device с таймаутом
 */
export async function waitForDevice(timeoutMs = 120_000) {
  const { code } = await runCommand(DEFAULT_ADB, ["wait-for-device"], { timeout: timeoutMs });
  if (code !== 0) throw new Error("wait-for-device failed or timeout");
}

/**
 * adb shell pm list packages | grep <pattern>
 */
export async function isPackageInstalled(packageName) {
  const { stdout } = await adb("shell", "pm", "list", "packages", packageName);
  return stdout.includes("package:" + packageName);
}

/**
 * adb install -r <apk>
 */
export async function installApk(apkPath) {
  await adb("install", "-r", apkPath);
}

/**
 * adb shell am start -n <component>
 */
export async function startActivity(component) {
  await adb("shell", "am", "start", "-n", component);
}

/**
 * Отправить текст в приложение через am start с extra (Base64 — пробелы в аргументах обрезаются).
 */
export async function startActivityWithText(component, text) {
  if (typeof text !== "string" || !text.trim()) return;
  const b64 = Buffer.from(text.trim(), "utf8").toString("base64");
  const args = [
    "shell", "am", "start",
    "-n", component,
    "-a", "dev.catandbunny.ai_companion.DEMO_SEND",
    "-f", "0x20000000", // FLAG_ACTIVITY_SINGLE_TOP
    "-e", "dev.catandbunny.ai_companion.DEMO_MESSAGE_B64", b64,
  ];
  await adb(...args);
}

/**
 * Запуск эмулятора в фоне. Возвращает child process (для последующего kill при необходимости).
 * @param {string} avdName - имя AVD
 */
export function startEmulatorProcess(avdName) {
  const proc = spawn(DEFAULT_EMULATOR, ["-avd", avdName], {
    detached: true,
    stdio: "ignore",
    env: { ...process.env },
  });
  proc.unref();
  return proc;
}

/**
 * Запуск screenrecord в фоне. При нескольких устройствах добавляет -s для выбора эмулятора.
 */
export async function startScreenRecord(durationSeconds = 180) {
  const baseArgs = ["shell", "screenrecord", "--time-limit", String(durationSeconds), SCREENRECORD_PATH];
  const deviceId = await getTargetDeviceId();
  const args = deviceId ? ["-s", deviceId, ...baseArgs] : baseArgs;

  return new Promise((resolve, reject) => {
    const proc = spawn(DEFAULT_ADB, args, {
      stdio: ["ignore", "pipe", "pipe"],
      env: process.env,
    });
    let stderr = "";
    proc.stderr?.on("data", (chunk) => { stderr += chunk.toString(); });
    proc.on("error", reject);
    setTimeout(() => resolve({ process: proc, pid: proc.pid }), 1500);
  });
}

/**
 * Остановить процесс записи (убить наш adb screenrecord процесс)
 */
export function stopScreenRecord(recordProcess) {
  if (recordProcess && recordProcess.kill) {
    recordProcess.kill("SIGINT");
  }
}

/**
 * adb pull remote local
 */
export async function pullFile(remotePath, localPath) {
  await adb("pull", remotePath, localPath);
}

/**
 * adb shell rm -f <path>
 */
export async function removeRemoteFile(remotePath) {
  await adb("shell", "rm", "-f", remotePath);
}

/**
 * Экранирование строки для adb shell input text.
 * Пробел заменяется на %s; кавычки и спецсимволы экранируются.
 */
export function escapeInputText(str) {
  if (typeof str !== "string") return "";
  return str
    .replace(/\\/g, "\\\\")
    .replace(/ /g, "%s")
    .replace(/'/g, "\\'")
    .replace(/"/g, '\\"')
    .replace(/&/g, "\\&")
    .replace(/</g, "\\<")
    .replace(/>/g, "\\>")
    .replace(/\|/g, "\\|")
    .replace(/;/g, "\\;")
    .replace(/\(/g, "\\(")
    .replace(/\)/g, "\\)")
    .replace(/`/g, "\\`")
    .replace(/\$/g, "\\$");
}

/**
 * Ввод текста через adb shell input text. Для длинного текста разбиваем на части.
 */
export async function inputText(text) {
  const escaped = escapeInputText(text);
  if (!escaped) return;
  const maxChunk = 200;
  for (let i = 0; i < escaped.length; i += maxChunk) {
    const chunk = escaped.slice(i, i + maxChunk);
    await adb("shell", "input", "text", chunk);
  }
}

/**
 * Отправка keyevent (например 66 = Enter)
 */
export async function inputKeyevent(keyCode) {
  await adb("shell", "input", "keyevent", String(keyCode));
}

/**
 * Тап по координатам (x, y)
 */
export async function inputTap(x, y) {
  await adb("shell", "input", "tap", String(x), String(y));
}

/**
 * Создание AVD через avdmanager (no) create avd -n <name> -k "system-images;..."
 */
export async function createAvd(avdName, systemImage) {
  const sdkPath = process.env.ANDROID_HOME;
  if (!sdkPath) throw new Error("ANDROID_HOME не задан");
  const args = ["-s", "create", "avd", "-n", avdName, "-k", systemImage, "--force"];
  const { code, stderr, stdout } = await runCommand(DEFAULT_AVDMANAGER, args, { timeout: 120_000 });
  if (code !== 0) throw new Error(`avdmanager failed: ${stderr || stdout}`);
  return { stdout, stderr };
}

/**
 * Гарантировать наличие директории для записей
 */
export async function ensureRecordingsDir() {
  await mkdir(RECORDINGS_DIR, { recursive: true });
  return RECORDINGS_DIR;
}

/**
 * Путь к файлу записи на устройстве
 */
export function getScreenRecordRemotePath() {
  return SCREENRECORD_PATH;
}

/**
 * Локальная директория записей
 */
export function getRecordingsDir() {
  return RECORDINGS_DIR;
}

/**
 * Таймаут сценария (мс)
 */
export function getScenarioTimeoutMs() {
  return SCENARIO_TIMEOUT_MS;
}

/**
 * Максимальный возраст записей (дни) для очистки
 */
export function getRecordingMaxAgeDays() {
  return RECORDING_MAX_AGE_DAYS;
}
