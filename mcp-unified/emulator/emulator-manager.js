/**
 * Логика управления Android эмулятором: AVD, установка APK, запись экрана, сценарий демо.
 */

import path from "path";
import fs from "fs";
import https from "https";
import { fileURLToPath } from "url";
import {
  isDeviceConnected,
  waitForDevice,
  isPackageInstalled,
  installApk,
  startActivity,
  startActivityWithText,
  startEmulatorProcess,
  startScreenRecord,
  stopScreenRecord as stopScreenRecordCmd,
  pullFile,
  removeRemoteFile,
  ensureRecordingsDir,
  getScreenRecordRemotePath,
  getRecordingsDir,
  getRecordingMaxAgeDays,
  getScenarioTimeoutMs,
  createAvd as doCreateAvd,
} from "./adb-commands.js";

const __filename = fileURLToPath(import.meta.url);
const __dirname = path.dirname(__filename);

const DEFAULT_AVD_NAME = process.env.ANDROID_AVD_NAME || "Pixel_6_API_34";
const DEFAULT_PACKAGE = "dev.catandbunny.ai_companion";
const MAIN_ACTIVITY = "dev.catandbunny.ai_companion/.MainActivity";

let currentRecordingProcess = null;

function log(msg) {
  const ts = new Date().toISOString();
  console.error(`[emulator-manager] ${ts} ${msg}`);
}

/**
 * Скачать файл по URL во временный путь.
 */
function downloadFile(url) {
  return new Promise((resolve, reject) => {
    const tmpPath = path.join(__dirname, "recordings", "temp_" + Date.now() + ".apk");
    const file = fs.createWriteStream(tmpPath);
    https.get(url, (response) => {
      if (response.statusCode === 301 || response.statusCode === 302) {
        const redirect = response.headers.location;
        file.close();
        fs.unlinkSync(tmpPath);
        return downloadFile(redirect).then(resolve).catch(reject);
      }
      if (response.statusCode !== 200) {
        file.close();
        fs.unlinkSync(tmpPath);
        reject(new Error(`HTTP ${response.statusCode}`));
        return;
      }
      response.pipe(file);
      file.on("finish", () => {
        file.close();
        resolve(tmpPath);
      });
    }).on("error", (err) => {
      file.close();
      if (fs.existsSync(tmpPath)) fs.unlinkSync(tmpPath);
      reject(err);
    });
  });
}

/**
 * Получить локальный путь к APK (скачать по URL при необходимости).
 */
async function resolveApkPath(apkPath) {
  if (!apkPath) return null;
  const trimmed = String(apkPath).trim();
  if (trimmed.startsWith("http://") || trimmed.startsWith("https://")) {
    log("Downloading APK from URL: " + trimmed);
    const local = await downloadFile(trimmed);
    return local;
  }
  if (fs.existsSync(trimmed)) return trimmed;
  throw new Error("APK не найден: " + trimmed);
}

/**
 * Очистка записей старше N дней.
 */
export async function cleanupOldRecordings() {
  const dir = getRecordingsDir();
  if (!fs.existsSync(dir)) return;
  const maxAgeDays = getRecordingMaxAgeDays();
  const maxAgeMs = maxAgeDays * 24 * 60 * 60 * 1000;
  const now = Date.now();
  const files = fs.readdirSync(dir);
  for (const f of files) {
    if (f.startsWith("temp_") && f.endsWith(".apk")) continue;
    const full = path.join(dir, f);
    const stat = fs.statSync(full);
    if (stat.isFile() && now - stat.mtimeMs > maxAgeMs) {
      try {
        fs.unlinkSync(full);
        log("Removed old recording: " + f);
      } catch (e) {
        log("Failed to remove " + f + ": " + e.message);
      }
    }
  }
}

/**
 * create_avd
 */
export async function createAvd(avdName, systemImage) {
  if (!systemImage) {
    throw new Error("Для create_avd укажите system image (например system-images;android-34;google_apis;x86_64)");
  }
  await doCreateAvd(avdName, systemImage);
  return { success: true, steps: [{ step: "create_avd", status: "success", details: `AVD: ${avdName}` }] };
}

/**
 * start_emulator: проверить устройство, при необходимости запустить эмулятор и дождаться.
 */
export async function startEmulator(avdName = DEFAULT_AVD_NAME) {
  const steps = [];
  if (await isDeviceConnected()) {
    steps.push({ step: "start_emulator", status: "skipped", details: "Устройство уже подключено" });
    return { success: true, steps, avd_name: avdName };
  }
  log("Starting emulator: " + avdName);
  startEmulatorProcess(avdName);
  steps.push({ step: "start_emulator", status: "success", details: `Запущен AVD: ${avdName}` });
  await waitForDevice(120_000);
  steps.push({ step: "wait_for_device", status: "success", details: "adb wait-for-device" });
  return { success: true, steps, avd_name: avdName };
}

/**
 * install_apk: проверить пакет, при необходимости установить APK.
 */
export async function installApkAction(apkPath, packageName = DEFAULT_PACKAGE) {
  const steps = [];
  const installed = await isPackageInstalled(packageName);
  if (installed) {
    steps.push({ step: "install_app", status: "skipped", details: "Приложение уже установлено" });
    return { success: true, steps };
  }
  if (!apkPath) {
    throw new Error("Для установки укажите apk_path (локальный путь или URL)");
  }
  let localApk = await resolveApkPath(apkPath);
  try {
    await installApk(localApk);
    steps.push({ step: "install_app", status: "success", details: localApk });
  } finally {
    if (localApk && localApk.includes("temp_")) {
      try { fs.unlinkSync(localApk); } catch (_) {}
    }
  }
  return { success: true, steps };
}

/**
 * record_screen: начать запись на указанное время (в фоне).
 */
export async function recordScreen(recordingDurationSeconds = 180) {
  const steps = [];
  await ensureRecordingsDir();
  const { process: proc, pid } = await startScreenRecord(recordingDurationSeconds);
  currentRecordingProcess = proc;
  steps.push({
    step: "start_recording",
    status: "success",
    details: `PID: ${pid}, duration: ${recordingDurationSeconds}s`,
  });
  return { success: true, steps, recording_pid: pid };
}

/**
 * stop_recording: остановить текущую запись и при необходимости скачать файл.
 */
export async function stopRecording(pullToLocal = true) {
  const steps = [];
  if (!currentRecordingProcess) {
    steps.push({ step: "stop_recording", status: "skipped", details: "Нет активной записи" });
    return { success: true, steps, recording_path: null };
  }
  stopScreenRecordCmd(currentRecordingProcess);
  currentRecordingProcess = null;
  steps.push({ step: "stop_recording", status: "success", details: "Процесс записи остановлен" });
  let recordingPath = null;
  if (pullToLocal) {
    await ensureRecordingsDir();
    const name = "demo_" + new Date().toISOString().replace(/[:.]/g, "-").slice(0, 19) + ".mp4";
    const localPath = path.join(getRecordingsDir(), name);
    try {
      await pullFile(getScreenRecordRemotePath(), localPath);
      await removeRemoteFile(getScreenRecordRemotePath());
      recordingPath = localPath;
      steps.push({ step: "pull_recording", status: "success", details: recordingPath });
    } catch (e) {
      steps.push({ step: "pull_recording", status: "error", details: e.message });
    }
  }
  return { success: true, steps, recording_path: recordingPath };
}

/**
 * get_recording_path: путь к последней записи или список записей.
 */
export async function getRecordingPath() {
  await cleanupOldRecordings();
  const dir = getRecordingsDir();
  if (!fs.existsSync(dir)) {
    return { success: true, recording_path: null, recordings: [] };
  }
  const files = fs.readdirSync(dir)
    .filter((f) => f.endsWith(".mp4") && !f.startsWith("temp_"))
    .map((f) => ({ name: f, path: path.join(dir, f) }));
  files.sort((a, b) => {
    const statA = fs.statSync(a.path);
    const statB = fs.statSync(b.path);
    return statB.mtimeMs - statA.mtimeMs;
  });
  const last = files[0]?.path || null;
  return {
    success: true,
    recording_path: last,
    recordings: files.map((f) => f.path),
  };
}

/**
 * Генерация случайной суммы от 13000 до 30000 рублей.
 */
function randomAmount() {
  return Math.floor(13000 + Math.random() * (30000 - 13000 + 1));
}

/**
 * Оборачивает Promise в таймаут.
 */
function withTimeout(promise, ms, message = "Timeout") {
  return new Promise((resolve, reject) => {
    const timer = setTimeout(() => reject(new Error(message)), ms);
    promise.then(
      (v) => {
        clearTimeout(timer);
        resolve(v);
      },
      (e) => {
        clearTimeout(timer);
        reject(e);
      }
    );
  });
}

/**
 * simulate_user_flow: полный сценарий демо (идемпотентный).
 * Ограничен по времени SCENARIO_TIMEOUT_MS.
 */
export async function simulateUserFlow(options = {}) {
  const timeoutMs = getScenarioTimeoutMs();
  return withTimeout(simulateUserFlowInner(options), timeoutMs, `Сценарий прерван по таймауту (${timeoutMs} ms)`);
}

async function simulateUserFlowInner(options = {}) {
  const {
    avdName = DEFAULT_AVD_NAME,
    apkPath = null,
    packageName = DEFAULT_PACKAGE,
    testMessage = null,
    waitAfterStartMs = 5000,
    waitForResponseMs = 45000,
  } = options;

  const startTime = Date.now();
  const steps = [];
  const logs = [];

  function addLog(msg) {
    log(msg);
    logs.push(msg);
  }

  try {
    // 1. Проверить/запустить эмулятор
    if (!(await isDeviceConnected())) {
      addLog("Emulator not running, starting: " + avdName);
      startEmulatorProcess(avdName);
      await waitForDevice(120_000);
      steps.push({ step: "start_emulator", status: "success", details: `AVD: ${avdName}` });
    } else {
      steps.push({ step: "start_emulator", status: "skipped", details: "Устройство уже подключено" });
    }

    // 2. Установить приложение при необходимости
    const installed = await isPackageInstalled(packageName);
    if (!installed) {
      if (!apkPath) throw new Error("Приложение не установлено. Укажите apk_path для установки.");
      let localApk = await resolveApkPath(apkPath);
      try {
        await installApk(localApk);
        steps.push({ step: "install_app", status: "success", details: "APK установлен" });
      } finally {
        if (localApk && localApk.includes("temp_")) {
          try { fs.unlinkSync(localApk); } catch (_) {}
        }
      }
    } else {
      steps.push({ step: "install_app", status: "skipped", details: "App already installed" });
    }

    // 3. Запустить приложение
    await startActivity(MAIN_ACTIVITY);
    steps.push({ step: "start_app", status: "success", details: MAIN_ACTIVITY });
    addLog("App started");

    // 4. Начать запись экрана
    await ensureRecordingsDir();
    const recordDuration = 120;
    const { process: recProc } = await startScreenRecord(recordDuration);
    currentRecordingProcess = recProc;
    steps.push({ step: "start_recording", status: "success", details: `PID: ${recProc.pid}` });

    // 5. Подождать загрузки приложения (увеличено до 10 с для полной инициализации)
    await new Promise((r) => setTimeout(r, waitAfterStartMs));

    const amount = testMessage ? null : randomAmount();
    const message =
      testMessage ||
      `У меня есть ${amount} рублей, составь рекомендации по инвестициям и отправь информацию мне в телеграмм`;

    // 6. Отправить текст через ACTION_SEND (поддержка кириллицы, не зависит от input text)
    addLog("Sending message via ACTION_SEND: " + message.slice(0, 80) + "...");
    await startActivityWithText(MAIN_ACTIVITY, message);
    await new Promise((r) => setTimeout(r, 1000));
    steps.push({
      step: "simulate_input",
      status: "success",
      details: "Message sent via intent: '" + message.slice(0, 60) + "...'",
    });

    // 7. Ожидание ответа бота
    addLog("Waiting for bot response: " + waitForResponseMs + " ms");
    await new Promise((r) => setTimeout(r, waitForResponseMs));
    steps.push({
      step: "wait_for_response",
      status: "success",
      details: `Waited ${waitForResponseMs / 1000} seconds`,
    });

    // 8. Остановить запись и скачать видео
    stopScreenRecordCmd(currentRecordingProcess);
    currentRecordingProcess = null;
    await new Promise((r) => setTimeout(r, 2000));

    const recordingFileName = "demo_" + new Date().toISOString().replace(/[:.]/g, "-").slice(0, 19) + ".mp4";
    const recordingPath = path.join(getRecordingsDir(), recordingFileName);
    try {
      await pullFile(getScreenRecordRemotePath(), recordingPath);
      await removeRemoteFile(getScreenRecordRemotePath());
      steps.push({ step: "stop_recording", status: "success", details: `Video saved to: ${recordingPath}` });
    } catch (e) {
      steps.push({ step: "stop_recording", status: "error", details: e.message });
    }

    await cleanupOldRecordings();

    const totalDuration = Math.round((Date.now() - startTime) / 1000);
    return {
      success: true,
      steps,
      recording_path: recordingPath,
      generated_amount: amount,
      total_duration: `${totalDuration} seconds`,
      logs,
    };
  } catch (err) {
    if (currentRecordingProcess) {
      stopScreenRecordCmd(currentRecordingProcess);
      currentRecordingProcess = null;
    }
    steps.push({ step: "error", status: "error", details: err.message });
    addLog("Error: " + err.message);
    throw err;
  }
}
