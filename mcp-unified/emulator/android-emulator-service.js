/**
 * MCP-инструмент control_android_emulator: регистрация схемы и вызов логики эмулятора.
 */

import * as emulatorManager from "./emulator-manager.js";

export const CONTROL_ANDROID_EMULATOR_TOOL = {
  name: "control_android_emulator",
  description:
    "Управляет Android эмулятором. ВАЖНО: для полного демо (открыть приложение, отправить сообщение про инвестиции, записать экран) используй action=simulate_user_flow. Для только записи экрана — action=record_screen. Действия строго из enum, других нет.",
  inputSchema: {
    type: "object",
    properties: {
      action: {
        type: "string",
        enum: [
          "create_avd",
          "start_emulator",
          "install_apk",
          "record_screen",
          "simulate_user_flow",
          "stop_recording",
          "get_recording_path",
        ],
        description:
          "Действие. simulate_user_flow — полный сценарий демо (эмулятор, приложение, ввод сообщения, запись, ожидание ответа). record_screen — только начать запись. stop_recording — остановить и сохранить. Других действий нет.",
      },
      avd_name: {
        type: "string",
        description: "Имя AVD (для create_avd, start_emulator)",
      },
      system_image: {
        type: "string",
        description: "System image для create_avd",
      },
      apk_path: {
        type: "string",
        description: "Путь к APK (для install_apk и simulate_user_flow, если приложение не установлено)",
      },
      package_name: {
        type: "string",
        description: "Пакет приложения (по умолчанию dev.catandbunny.ai_companion)",
      },
      recording_duration: {
        type: "integer",
        description: "Длительность записи в секундах (для record_screen)",
      },
      test_message: {
        type: "string",
        description:
          "Опционально: свой текст сообщения для simulate_user_flow. Если не задан — используется сообщение про инвестиции с случайной суммой.",
      },
    },
    required: ["action"],
  },
};

/**
 * Обработчик вызова инструмента control_android_emulator.
 * @param {object} args - аргументы из MCP (action, avd_name, apk_path, package_name, recording_duration, test_message)
 * @returns {Promise<object>} - результат в формате MCP (content) и структурированный результат для агента
 */
export async function handleControlAndroidEmulator(args) {
  const action = (args?.action || "").toString();
  const avdName = (args?.avd_name || "").trim() || undefined;
  const apkPath = (args?.apk_path || "").trim() || undefined;
  const packageName = (args?.package_name || "").trim() || undefined;
  const recordingDuration = Math.max(30, Math.min(600, Number(args?.recording_duration) || 180));
  const testMessage = (args?.test_message || "").trim() || undefined;
  const systemImage = (args?.system_image || "").trim() || undefined;

  let result;

  switch (action) {
    case "create_avd":
      if (!avdName) throw new Error("Для create_avd укажите avd_name");
      result = await emulatorManager.createAvd(avdName, systemImage);
      break;
    case "start_emulator":
      result = await emulatorManager.startEmulator(avdName);
      break;
    case "install_apk":
      if (!apkPath) throw new Error("Для install_apk укажите apk_path");
      result = await emulatorManager.installApkAction(apkPath, packageName);
      break;
    case "record_screen":
      result = await emulatorManager.recordScreen(recordingDuration);
      break;
    case "stop_recording":
      result = await emulatorManager.stopRecording(true);
      break;
    case "get_recording_path":
      result = await emulatorManager.getRecordingPath();
      break;
    case "simulate_user_flow": {
      result = await emulatorManager.simulateUserFlow({
        avdName,
        apkPath,
        packageName,
        testMessage,
        waitAfterStartMs: 10000,
        waitForResponseMs: 45000,
      });
      break;
    }
    default:
      throw new Error("Неизвестное действие: " + action);
  }

  const text = JSON.stringify(result, null, 2);
  return {
    content: [{ type: "text", text }],
    structured: result,
  };
}
