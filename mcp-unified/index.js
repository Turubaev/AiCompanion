#!/usr/bin/env node

/**
 * Unified MCP Server
 * Объединенный MCP сервер с инструментами GitHub и Currency
 * Заменяет отдельные серверы на один объединенный
 */

import { Server } from "@modelcontextprotocol/sdk/server/index.js";
import { StdioServerTransport } from "@modelcontextprotocol/sdk/server/stdio.js";
import {
  CallToolRequestSchema,
  ListToolsRequestSchema,
} from "@modelcontextprotocol/sdk/types.js";
import https from "https";
import http from "http";
import { execFile } from "child_process";
import { execSync } from "child_process";
import { fileURLToPath } from "url";
import path from "path";
import fs from "fs";
import {
  CONTROL_ANDROID_EMULATOR_TOOL,
  handleControlAndroidEmulator,
} from "./emulator/android-emulator-service.js";

const server = new Server(
  {
    name: "unified-mcp-server",
    version: "1.0.0",
  },
  {
    capabilities: {
      tools: {},
    },
  }
);

const GITHUB_TOKEN = process.env.GITHUB_PERSONAL_ACCESS_TOKEN || "";
const TINKOFF_TOKEN = process.env.TINKOFF_INVEST_TOKEN || "";
const TELEGRAM_BOT_TOKEN = (process.env.TELEGRAM_BOT_TOKEN || "").trim();
const SUPPORT_API_URL = (process.env.SUPPORT_API_URL || "http://127.0.0.1:3010").replace(/\/$/, "");

const __filename = fileURLToPath(import.meta.url);
const __dirname = path.dirname(__filename);
/** Корень папки с проектами (например AndroidStudioProjects); CloudBuddy лежит рядом с Ai_Companion */
const PROJECTS_ROOT = path.resolve(__dirname, "..", "..");
/** Известный репозиторий CloudBuddy: подстановка owner при запросе только по имени репо (удобство для одного репо; остальные репо — по переданным owner/repo). */
const CLOUDBUDDY_GITHUB_OWNER = "Turubaev";
const CLOUDBUDDY_GITHUB_REPO = "CloudBuddy";

function normalizeRepo(owner, repo) {
  const r = (repo || "").trim();
  const o = (owner || "").trim();
  if (!r) return { owner: o, repo: r };
  if (r.toLowerCase() !== "cloudbuddy") return { owner: o, repo: r };
  if (!o || o.toLowerCase() === "catandbunny") {
    return { owner: CLOUDBUDDY_GITHUB_OWNER, repo: CLOUDBUDDY_GITHUB_REPO };
  }
  return { owner: o, repo: r === "cloudbuddy" ? CLOUDBUDDY_GITHUB_REPO : r };
}

function buildRepoNotFoundMessage(owner, repo, apiError) {
  const url = "https://github.com/" + owner + "/" + repo;
  let msg = "Репозиторий не найден или недоступен: " + owner + "/" + repo + ".\n";
  msg += "Проверьте: 1) Откройте " + url + " — репо существует и имя владельца верное? ";
  msg += "2) Если репо приватный — на VPS в окружении MCP должен быть задан GITHUB_PERSONAL_ACCESS_TOKEN с правами repo. ";
  msg += "API: " + (apiError || "");
  return msg;
}

/**
 * HTTP запрос к support-api (GET или POST).
 * Возвращает { statusCode, body } или бросает ошибку.
 */
function supportApiRequest(path, method = "GET", body = null) {
  return new Promise((resolve, reject) => {
    const url = new URL(path, SUPPORT_API_URL);
    const isHttps = url.protocol === "https:";
    const lib = isHttps ? https : http;
    const options = {
      hostname: url.hostname,
      port: url.port || (isHttps ? 443 : 80),
      path: url.pathname + url.search,
      method,
      headers: {},
    };
    if (body != null) {
      options.headers["Content-Type"] = "application/json";
      options.headers["Content-Length"] = Buffer.byteLength(JSON.stringify(body), "utf8");
    }
    const req = lib.request(options, (res) => {
      let data = "";
      res.on("data", (chunk) => { data += chunk; });
      res.on("end", () => {
        resolve({ statusCode: res.statusCode, body: data });
      });
    });
    req.on("error", reject);
    if (body != null) req.write(JSON.stringify(body));
    req.end();
  });
}

/**
 * Выполняет HTTP запрос к GitHub API
 */
async function githubApiRequest(endpoint, method = "GET", body = null) {
  return new Promise((resolve, reject) => {
    const options = {
      hostname: "api.github.com",
      path: endpoint,
      method: method,
      headers: {
        "User-Agent": "unified-mcp-server",
        "Accept": "application/vnd.github.v3+json",
      },
    };

    if (GITHUB_TOKEN) {
      options.headers["Authorization"] = `token ${GITHUB_TOKEN}`;
    }

    if (body) {
      options.headers["Content-Type"] = "application/json";
    }

    const req = https.request(options, (res) => {
      let data = "";

      res.on("data", (chunk) => {
        data += chunk;
      });

      res.on("end", () => {
        try {
          const json = JSON.parse(data);
          if (res.statusCode >= 200 && res.statusCode < 300) {
            resolve(json);
          } else {
            reject(new Error(`GitHub API error: ${json.message || data}`));
          }
        } catch (error) {
          reject(new Error(`Parse error: ${error.message}`));
        }
      });
    });

    req.on("error", (error) => {
      reject(error);
    });

    if (body) {
      req.write(JSON.stringify(body));
    }

    req.end();
  });
}

/**
 * Получает курс USD/RUB из публичного API
 */
async function getCurrencyRate() {
  return new Promise((resolve, reject) => {
    const url = "https://api.exchangerate-api.com/v4/latest/USD";
    
    https.get(url, (res) => {
      let data = "";
      
      res.on("data", (chunk) => {
        data += chunk;
      });
      
      res.on("end", () => {
        try {
          const json = JSON.parse(data);
          const rubRate = json.rates?.RUB;
          
          if (rubRate) {
            resolve({
              rate: rubRate,
              timestamp: json.time_last_updated || Date.now(),
              source: "exchangerate-api.com",
            });
          } else {
            reject(new Error("Курс RUB не найден в ответе API"));
          }
        } catch (error) {
          reject(new Error(`Ошибка парсинга ответа: ${error.message}`));
        }
      });
    }).on("error", (error) => {
      reject(new Error(`Ошибка запроса: ${error.message}`));
    });
  });
}

/**
 * Получает курс через Tinkoff Invest API (через python-скрипт)
 */
async function getCurrencyRateFromTinkoff(from, to) {
  return new Promise((resolve, reject) => {
    if (!TINKOFF_TOKEN) {
      reject(new Error("TINKOFF_INVEST_TOKEN не задан на сервере"));
      return;
    }

    const scriptPath = path.join(__dirname, "tinkoff_rate.py");
    execFile(
      "python3",
      [scriptPath, from, to],
      { 
        timeout: 12_000, 
        maxBuffer: 1024 * 1024,
        env: { ...process.env, TINKOFF_INVEST_TOKEN: TINKOFF_TOKEN }
      },
      (err, stdout, stderr) => {
        if (err) {
          const errorDetails = stderr || stdout || err.message;
          reject(new Error(`Tinkoff script error: ${errorDetails}`));
          return;
        }
        try {
          const output = (stdout || "").trim();
          if (!output) {
            reject(new Error("Tinkoff script returned empty output"));
            return;
          }
          const parsed = JSON.parse(output);
          if (!parsed.ok) {
            reject(new Error(parsed.error || "Unknown tinkoff error"));
            return;
          }
          resolve(parsed);
        } catch (e) {
          const output = (stdout || "").trim();
          reject(new Error(`Failed to parse tinkoff response: ${e.message}. Output: ${output.substring(0, 200)}`));
        }
      }
    );
  });
}

/**
 * Получает бумаги (акции, облигации, ETF) по бюджету в рублях через Tinkoff Invest API.
 */
async function getInstrumentsForBudget(budgetRub, instrumentType = "all", limit = 30) {
  return new Promise((resolve, reject) => {
    if (!TINKOFF_TOKEN) {
      reject(new Error("TINKOFF_INVEST_TOKEN не задан на сервере"));
      return;
    }
    const scriptPath = path.join(__dirname, "tinkoff_instruments.py");
    const args = [String(budgetRub), instrumentType, String(limit)];
    execFile(
      "python3",
      [scriptPath, ...args],
      {
        timeout: 60_000,
        maxBuffer: 4 * 1024 * 1024,
        env: { ...process.env, TINKOFF_INVEST_TOKEN: TINKOFF_TOKEN },
      },
      (err, stdout, stderr) => {
        if (err) {
          const details = (stderr || stdout || err.message || "").trim();
          reject(new Error(`Tinkoff instruments error: ${details}`));
          return;
        }
        try {
          const output = (stdout || "").trim();
          if (!output) {
            reject(new Error("Tinkoff instruments script returned empty output"));
            return;
          }
          const parsed = JSON.parse(output);
          if (!parsed.ok) {
            reject(new Error(parsed.error || "Unknown tinkoff instruments error"));
            return;
          }
          resolve(parsed);
        } catch (e) {
          reject(new Error(`Failed to parse tinkoff instruments response: ${e.message}. Output: ${(stdout || "").trim().substring(0, 300)}`));
        }
      }
    );
  });
}

/**
 * Отправляет сообщение пользователю в Telegram через Bot API.
 */
async function sendTelegramMessage(chatId, text) {
  if (!TELEGRAM_BOT_TOKEN) {
    throw new Error("TELEGRAM_BOT_TOKEN не задан на сервере");
  }
  const body = JSON.stringify({ chat_id: String(chatId), text: String(text) });
  const apiPath = `/bot${TELEGRAM_BOT_TOKEN}/sendMessage`;
  return new Promise((resolve, reject) => {
    const options = {
      hostname: "api.telegram.org",
      path: apiPath,
      method: "POST",
      headers: {
        "Content-Type": "application/json",
        "Content-Length": Buffer.byteLength(body, "utf8"),
      },
    };
    const req = https.request(options, (res) => {
      let data = "";
      res.on("data", (chunk) => { data += chunk; });
      res.on("end", () => {
        try {
          const json = JSON.parse(data);
          if (json.ok === true) {
            resolve({ ok: true, result: json.result });
          } else {
            const desc = json.description || "Telegram API error";
            if (desc === "Unauthorized") {
              const hint = !TELEGRAM_BOT_TOKEN
                ? "TELEGRAM_BOT_TOKEN не задан на сервере."
                : "Токен неверный или отозван. Проверьте TELEGRAM_BOT_TOKEN на VPS (см. vps-setup/DEBUG_TELEGRAM.md).";
              reject(new Error(`Ошибка: Unauthorized. ${hint}`));
            } else {
              reject(new Error(desc));
            }
          }
        } catch (e) {
          reject(new Error(`Parse error: ${e.message}`));
        }
      });
    });
    req.on("error", reject);
    req.write(body);
    req.end();
  });
}

// Регистрация инструментов
server.setRequestHandler(ListToolsRequestSchema, async () => {
  const tools = [
    // Currency инструмент
    {
      name: "get_currency_rate",
      description: "Получить текущий курс доллара США к российскому рублю (USD/RUB)",
      inputSchema: {
        type: "object",
        properties: {
          from: {
            type: "string",
            description: "Базовая валюта (по умолчанию USD)",
            enum: ["USD"],
            default: "USD",
          },
          to: {
            type: "string",
            description: "Целевая валюта (по умолчанию RUB)",
            enum: ["RUB"],
            default: "RUB",
          },
        },
        required: [],
      },
    },
    // GitHub инструменты (основные)
    {
      name: "search_repositories",
      description: "Поиск репозиториев на GitHub",
      inputSchema: {
        type: "object",
        properties: {
          query: {
            type: "string",
            description: "Поисковый запрос (см. синтаксис GitHub search)",
          },
          page: {
            type: "number",
            description: "Номер страницы для пагинации (по умолчанию: 1)",
          },
          perPage: {
            type: "number",
            description: "Количество результатов на странице (по умолчанию: 30, максимум: 100)",
          },
        },
        required: ["query"],
      },
    },
    {
      name: "get_file_contents",
      description: "Получить содержимое файла или директории из GitHub репозитория",
      inputSchema: {
        type: "object",
        properties: {
          owner: {
            type: "string",
            description: "Владелец репозитория (username или organization)",
          },
          repo: {
            type: "string",
            description: "Название репозитория",
          },
          path: {
            type: "string",
            description: "Путь к файлу или директории",
          },
          branch: {
            type: "string",
            description: "Ветка для получения содержимого",
          },
        },
        required: ["owner", "repo", "path"],
      },
    },
    {
      name: "get_git_branch",
      description: "Получить текущую ветку Git для локального проекта (например CloudBuddy). project_path: имя папки проекта (CloudBuddy) или абсолютный путь к репозиторию.",
      inputSchema: {
        type: "object",
        properties: {
          project_path: {
            type: "string",
            description: "Имя проекта (например CloudBuddy) или абсолютный путь к папке репозитория",
          },
        },
        required: ["project_path"],
      },
    },
    {
      name: "list_git_branches",
      description: "Показать список всех веток Git (локальных и удалённых) для локального проекта. project_path: имя папки (CloudBuddy) или абсолютный путь. Вызывай когда спрашивают про список веток, какие ветки есть.",
      inputSchema: {
        type: "object",
        properties: {
          project_path: {
            type: "string",
            description: "Имя проекта (например CloudBuddy) или абсолютный путь к папке репозитория",
          },
        },
        required: ["project_path"],
      },
    },
    {
      name: "list_repo_branches",
      description: "Список веток любого репозитория на GitHub. Вызывай при вопросах про ветки в каком-либо репо. Параметры: owner (владелец, например Turubaev, Microsoft), repo (название репо, например CloudBuddy, vscode). Примеры: Turubaev/CloudBuddy, facebook/react.",
      inputSchema: {
        type: "object",
        properties: {
          owner: {
            type: "string",
            description: "Владелец репозитория на GitHub (логин или организация), например Turubaev, Microsoft",
          },
          repo: {
            type: "string",
            description: "Название репозитория на GitHub, например CloudBuddy, vscode",
          },
          limit: {
            type: "number",
            description: "Максимум веток в ответе (по умолчанию 100)",
            default: 100,
          },
        },
        required: ["owner", "repo"],
      },
    },
    {
      name: "list_pull_requests",
      description: "Список pull request любого репозитория на GitHub. Вызывай при вопросах про PR, пулл-реквесты. Параметры: owner и repo (как в list_repo_branches), state: open, closed или all. Примеры репо: Turubaev/CloudBuddy, microsoft/vscode.",
      inputSchema: {
        type: "object",
        properties: {
          owner: {
            type: "string",
            description: "Владелец репозитория на GitHub (логин или организация), например Turubaev, Microsoft",
          },
          repo: {
            type: "string",
            description: "Название репозитория на GitHub, например CloudBuddy, vscode",
          },
          state: {
            type: "string",
            description: "Состояние PR: open, closed или all",
            enum: ["open", "closed", "all"],
            default: "open",
          },
          limit: {
            type: "number",
            description: "Максимум PR в ответе (по умолчанию 20)",
            default: 20,
          },
        },
        required: ["owner", "repo"],
      },
    },
    {
      name: "get_instruments_for_budget",
      description: "Найти бумаги (акции, облигации, ETF) в рублях, которые можно купить на указанную сумму. Вызывай когда пользователь спрашивает что купить на N рублей, как распределить средства, какие бумаги/облигации/акции купить для портфеля. Возвращает тикер, цену, лот, сколько лотов можно купить.",
      inputSchema: {
        type: "object",
        properties: {
          budget_rub: {
            type: "number",
            description: "Бюджет в рублях (например 13000)",
          },
          instrument_type: {
            type: "string",
            description: "Тип: shares (акции), bonds (облигации), etfs (ETF) или all (все)",
            enum: ["shares", "bonds", "etfs", "all"],
            default: "all",
          },
          limit: {
            type: "number",
            description: "Максимум результатов (по умолчанию 30)",
            default: 30,
          },
        },
        required: ["budget_rub"],
      },
    },
    {
      name: "send_telegram_message",
      description: "Отправить сообщение пользователю в Telegram. ОБЯЗАТЕЛЬНО вызывай этот инструмент после формирования рекомендаций по портфелю/инвестициям, если пользователь просил прислать рекомендации в Telegram (например «пришли в телеграмм», «отправь в Telegram»). Передай в text полный текст рекомендаций — тот же, что показываешь в чате. chat_id подставляется автоматически из настроек приложения. Вызови send_telegram_message в том же ответе, где даёшь рекомендации.",
      inputSchema: {
        type: "object",
        properties: {
          text: {
            type: "string",
            description: "Текст сообщения (рекомендации, стратегия, список бумаг и т.д.)",
          },
        },
        required: ["text"],
      },
    },
    CONTROL_ANDROID_EMULATOR_TOOL,
    // Support / CRM: контекст пользователя для support-ассистента
    {
      name: "get_user_support_context",
      description: "Получить контекст поддержки пользователя по email: информация о пользователе, открытые тикеты, история обращений. Используется support-ассистентом для ответов с учётом тикетов и истории.",
      inputSchema: {
        type: "object",
        properties: {
          user_email: {
            type: "string",
            description: "Email пользователя из контекста приложения",
          },
          include_tickets: {
            type: "boolean",
            description: "Включить открытые тикеты пользователя",
            default: true,
          },
          include_history: {
            type: "boolean",
            description: "Включить историю обращений",
            default: true,
          },
        },
        required: ["user_email"],
      },
    },
    {
      name: "get_ticket_details",
      description: "Получить детали тикета поддержки по id (например TICKET-1).",
      inputSchema: {
        type: "object",
        properties: {
          ticket_id: {
            type: "string",
            description: "Идентификатор тикета (TICKET-1, TICKET-2 и т.д.)",
          },
        },
        required: ["ticket_id"],
      },
    },
    {
      name: "create_ticket",
      description: "Создать новый тикет поддержки от имени пользователя.",
      inputSchema: {
        type: "object",
        properties: {
          user_email: {
            type: "string",
            description: "Email пользователя",
          },
          message: {
            type: "string",
            description: "Текст обращения",
          },
          subject: {
            type: "string",
            description: "Тема тикета (опционально)",
          },
        },
        required: ["user_email", "message"],
      },
    },
  ];

  return { tools };
});

async function handleTool(name, fn) {
  try {
    return await fn();
  } catch (error) {
    return { content: [{ type: "text", text: "Ошибка: " + error.message }], isError: true };
  }
}

server.setRequestHandler(CallToolRequestSchema, async (request) => {
  const { name, arguments: args } = request.params;

  if (name === "get_currency_rate") {
    return handleTool(name, async () => {
      const from = (args?.from || "USD").toString().toUpperCase();
      const to = (args?.to || "RUB").toString().toUpperCase();
      let rateInfo;
      let sourceLabel;
      if (TINKOFF_TOKEN) {
        rateInfo = await getCurrencyRateFromTinkoff(from, to);
        sourceLabel = "tinkoff-invest-api";
      } else {
        const currencyData = await getCurrencyRate();
        const usdRub = Number(currencyData.rate);
        if (!Number.isFinite(usdRub) || usdRub <= 0) throw new Error("Некорректный курс от источника");
        if (from === "USD" && to === "RUB") {
          rateInfo = { pair: "USD/RUB", rate: usdRub, updated_at: null, source: currencyData.source };
        } else if (from === "RUB" && to === "USD") {
          rateInfo = { pair: "RUB/USD", rate: 1 / usdRub, updated_at: null, source: currencyData.source };
        } else {
          throw new Error("Поддерживаются только пары USD/RUB и RUB/USD (получено: " + from + "/" + to + ")");
        }
        sourceLabel = currencyData.source;
      }
      const timestamp = rateInfo.updated_at ? new Date(rateInfo.updated_at).toLocaleString("ru-RU") : "неизвестно";
      return { content: [{ type: "text", text: "Текущий курс " + rateInfo.pair + ": " + Number(rateInfo.rate).toFixed(6) + "\n\nОбновлено: " + timestamp + "\nИсточник: " + sourceLabel }] };
    });
  }

  if (name === "search_repositories") {
    return handleTool(name, async () => {
      const query = args.query || "";
      const page = args.page || 1;
      const perPage = Math.min(args.perPage || 30, 100);
      const endpoint = "/search/repositories?q=" + encodeURIComponent(query) + "&page=" + page + "&per_page=" + perPage;
      const result = await githubApiRequest(endpoint);
      const repos = result.items || [];
      const text = repos.length > 0
        ? repos.map((repo, idx) => (idx + 1) + ". **" + repo.full_name + "**\n   " + (repo.description || "Нет описания") + "\n   ⭐ " + repo.stargazers_count + " | 🔀 " + repo.forks_count + " | 📝 " + (repo.language || "N/A") + "\n   🔗 " + repo.html_url).join("\n\n") + "\n\nВсего найдено: " + (result.total_count || 0) + " репозиториев"
        : "Репозитории не найдены";
      return { content: [{ type: "text", text }] };
    });
  }

  if (name === "get_file_contents") {
    return handleTool(name, async () => {
      const owner = args.owner;
      const repo = args.repo;
      const filePath = args.path;
      const branch = args.branch || "";
      const endpoint = "/repos/" + owner + "/" + repo + "/contents/" + encodeURIComponent(filePath) + (branch ? "?ref=" + branch : "");
      const result = await githubApiRequest(endpoint);
      if (result.type === "file") {
        const content = Buffer.from(result.content, "base64").toString("utf-8");
        return { content: [{ type: "text", text: "**Файл:** " + result.path + "\n**Размер:** " + result.size + " байт\n\n```\n" + content + "\n```" }] };
      }
      if (result.type === "dir") {
        const items = Array.isArray(result) ? result : [];
        const text = items.length > 0 ? items.map((item) => (item.type === "dir" ? "📁" : "📄") + " " + item.name).join("\n") : "Директория пуста";
        return { content: [{ type: "text", text: "**Директория:** " + filePath + "\n\n" + text }] };
      }
      throw new Error("Неизвестный тип: " + result.type);
    });
  }

  if (name === "get_git_branch") {
    return handleTool(name, () => {
      const projectPath = (args?.project_path || "").trim();
      if (!projectPath) {
        return { content: [{ type: "text", text: "Укажите project_path (например CloudBuddy или абсолютный путь)." }], isError: true };
      }
      const repoDir = path.isAbsolute(projectPath) ? projectPath : path.join(PROJECTS_ROOT, projectPath);
      if (!fs.existsSync(repoDir)) {
        const hint = "Инструменты Git выполняются на сервере, где запущен MCP. Папки " + projectPath + " здесь нет. Чтобы смотреть ветки проекта с вашего ПК (например CloudBuddy), запустите MCP локально: node run-tcp.js 8080 и укажите в приложении хост/порт этого ПК.";
        return { content: [{ type: "text", text: "Папка не найдена: " + repoDir + ".\n\n" + hint }], isError: true };
      }
      try {
        const branch = execSync("git", ["-C", repoDir, "rev-parse", "--abbrev-ref", "HEAD"], { encoding: "utf-8" }).trim();
        return { content: [{ type: "text", text: "Текущая ветка: **" + branch + "**\nРепозиторий: " + repoDir }] };
      } catch (e) {
        const msg = e.stderr && e.stderr.toString().trim() ? e.stderr.toString().trim() : e.message;
        const hint = fs.existsSync(path.join(repoDir, ".git")) ? "" : " Возможно, это не git-репозиторий или папка пуста.";
        return { content: [{ type: "text", text: "Ошибка при получении ветки для " + repoDir + ": " + msg + "." + hint }], isError: true };
      }
    });
  }

  if (name === "list_git_branches") {
    return handleTool(name, () => {
      const projectPath = (args?.project_path || "").trim();
      if (!projectPath) {
        return { content: [{ type: "text", text: "Укажите project_path (например CloudBuddy или абсолютный путь)." }], isError: true };
      }
      const repoDir = path.isAbsolute(projectPath) ? projectPath : path.join(PROJECTS_ROOT, projectPath);
      if (!fs.existsSync(repoDir)) {
        const hint = "Инструменты Git выполняются на сервере, где запущен MCP. Папки " + projectPath + " здесь нет. Чтобы смотреть ветки проекта с вашего ПК (например CloudBuddy), запустите MCP локально: node run-tcp.js 8080 и укажите в приложении хост/порт этого ПК.";
        return { content: [{ type: "text", text: "Папка не найдена: " + repoDir + ".\n\n" + hint }], isError: true };
      }
      try {
        const out = execSync("git", ["-C", repoDir, "branch", "-a"], { encoding: "utf-8" });
        const lines = out.split("\n").map((s) => s.trim()).filter(Boolean);
        const list = lines.map((line) => {
          const isCurrent = line.startsWith("*");
          const name = line.replace(/^\*\s*/, "").trim();
          return (isCurrent ? "→ " : "  ") + name;
        });
        return { content: [{ type: "text", text: "Ветки в " + repoDir + " (текущая отмечена →):\n\n" + list.join("\n") }] };
      } catch (e) {
        const msg = e.stderr && e.stderr.toString().trim() ? e.stderr.toString().trim() : e.message;
        const hint = fs.existsSync(path.join(repoDir, ".git")) ? "" : " Возможно, это не git-репозиторий.";
        return { content: [{ type: "text", text: "Ошибка при получении списка веток для " + repoDir + ": " + msg + "." + hint }], isError: true };
      }
    });
  }

  if (name === "list_repo_branches") {
    return handleTool(name, async () => {
      let owner = (args?.owner || "").trim();
      let repo = (args?.repo || "").trim();
      const norm = normalizeRepo(owner, repo);
      owner = norm.owner;
      repo = norm.repo;
      if (!owner || !repo) {
        return { content: [{ type: "text", text: "Укажите owner и repo (любой репозиторий на GitHub, например owner=Microsoft, repo=vscode). Для CloudBuddy: owner=Turubaev, repo=CloudBuddy." }], isError: true };
      }
      const perPage = Math.min(100, Math.max(1, Number(args?.limit) || 100));
      const tryRepo = (repoName) => "/repos/" + encodeURIComponent(owner) + "/" + encodeURIComponent(repoName) + "/branches?per_page=" + perPage;
      let branches;
      try {
        branches = await githubApiRequest(tryRepo(repo));
      } catch (e) {
        if (e.message && e.message.includes("Not Found") && repo.toLowerCase() === "cloudbuddy") {
          try {
            branches = await githubApiRequest(tryRepo(CLOUDBUDDY_GITHUB_REPO));
            repo = CLOUDBUDDY_GITHUB_REPO;
          } catch (e2) {
            return { content: [{ type: "text", text: buildRepoNotFoundMessage(owner, CLOUDBUDDY_GITHUB_REPO, e2.message) }], isError: true };
          }
        } else {
          return { content: [{ type: "text", text: buildRepoNotFoundMessage(owner, repo, e.message) }], isError: true };
        }
      }
      if (!Array.isArray(branches)) {
        return { content: [{ type: "text", text: "Неожиданный ответ API или репозиторий не найден." }], isError: true };
      }
      const lines = branches.map((b, i) => (i + 1) + ". " + (b.name || "?") + (b.protected ? " (protected)" : ""));
      const text = lines.length > 0
        ? "Ветки в репозитории " + owner + "/" + repo + ":\n\n" + lines.join("\n")
        : "В репозитории " + owner + "/" + repo + " нет веток или он недоступен.";
      return { content: [{ type: "text", text }] };
    });
  }

  if (name === "list_pull_requests") {
    return handleTool(name, async () => {
      let owner = (args?.owner || "").trim();
      let repo = (args?.repo || "").trim();
      const norm = normalizeRepo(owner, repo);
      owner = norm.owner;
      repo = norm.repo;
      if (!owner || !repo) {
        return { content: [{ type: "text", text: "Укажите owner и repo (любой репозиторий на GitHub, например owner=Microsoft, repo=vscode). Для CloudBuddy: owner=Turubaev, repo=CloudBuddy." }], isError: true };
      }
      const state = (args?.state || "open").toLowerCase();
      if (!["open", "closed", "all"].includes(state)) {
        return { content: [{ type: "text", text: "state должен быть open, closed или all." }], isError: true };
      }
      const perPage = Math.min(100, Math.max(1, Number(args?.limit) || 20));
      const tryRepo = (repoName) => "/repos/" + encodeURIComponent(owner) + "/" + encodeURIComponent(repoName) + "/pulls?state=" + state + "&per_page=" + perPage;
      let prs;
      try {
        prs = await githubApiRequest(tryRepo(repo));
      } catch (e) {
        if (e.message && e.message.includes("Not Found") && repo.toLowerCase() === "cloudbuddy") {
          try {
            prs = await githubApiRequest(tryRepo(CLOUDBUDDY_GITHUB_REPO));
            repo = CLOUDBUDDY_GITHUB_REPO;
          } catch (e2) {
            return { content: [{ type: "text", text: "Ошибка GitHub API: " + e2.message }], isError: true };
          }
        } else {
          return { content: [{ type: "text", text: "Ошибка GitHub API: " + e.message }], isError: true };
        }
      }
      if (!Array.isArray(prs)) {
        return { content: [{ type: "text", text: "Неожиданный ответ API." }], isError: true };
      }
      const lines = prs.map((pr, i) => {
        const num = pr.number;
        const title = pr.title || "(без названия)";
        const author = pr.user?.login || "?";
        const head = pr.head?.ref || "?";
        const base = pr.base?.ref || "?";
        const url = pr.html_url || "";
        return (i + 1) + ". #" + num + " **" + title + "**\n   " + author + " → " + head + " → " + base + "\n   " + url;
      });
      const text = lines.length > 0
        ? "Pull requests (" + state + ") в " + owner + "/" + repo + ":\n\n" + lines.join("\n\n")
        : "Нет pull request со состоянием " + state + " в " + owner + "/" + repo;
      return { content: [{ type: "text", text }] };
    });
  }

  if (name === "get_instruments_for_budget") {
    return handleTool(name, async () => {
      const budgetRub = Number(args?.budget_rub);
      if (!Number.isFinite(budgetRub) || budgetRub <= 0) {
        return { content: [{ type: "text", text: "Укажите бюджет в рублях (budget_rub > 0)." }], isError: true };
      }
      const instrumentType = (args?.instrument_type || "all").toString().toLowerCase();
      const limit = Math.min(100, Math.max(1, Number(args?.limit) || 30));
      const parsed = await getInstrumentsForBudget(budgetRub, instrumentType, limit);
      const list = parsed.instruments || [];
      const lines = list.map((r, i) => (i + 1) + ". **" + r.ticker + "** (" + r.type + ") — " + (r.name || "—") + "\n   Цена: " + r.price_rub + " ₽, лот: " + r.lot + ", лотов можно купить: " + r.lots_affordable + ", сумма ≈ " + r.total_cost_rub + " ₽");
      const msg = (parsed.message || "") + (lines.length ? "\n\n" + lines.join("\n\n") : "\n\nНет подходящих бумаг.");
      return { content: [{ type: "text", text: "Бюджет: " + parsed.budget_rub + " ₽. Источник: " + (parsed.source || "tinkoff-invest-api") + ".\n\n" + msg }] };
    });
  }

  if (name === "send_telegram_message") {
    const chatId = args?.chat_id?.toString()?.trim();
    const text = args?.text?.toString() ?? "";
    if (!chatId) {
      return { content: [{ type: "text", text: "chat_id не задан. Укажите Telegram Chat ID в настройках приложения." }], isError: true };
    }
    if (!text) {
      return { content: [{ type: "text", text: "Укажите текст сообщения (text)." }], isError: true };
    }
    return handleTool(name, async () => {
      await sendTelegramMessage(chatId, text);
      return { content: [{ type: "text", text: "Сообщение успешно отправлено в Telegram." }] };
    });
  }

  if (name === "control_android_emulator") {
    return handleTool(name, async () => {
      const { content } = await handleControlAndroidEmulator(args || {});
      return { content };
    });
  }

  if (name === "get_user_support_context") {
    return handleTool(name, async () => {
      const userEmail = (args?.user_email || "").toString().trim();
      if (!userEmail) {
        return { content: [{ type: "text", text: "Укажите user_email." }], isError: true };
      }
      const includeTickets = args?.include_tickets !== false;
      const includeHistory = args?.include_history !== false;
      const path = "/user-context?email=" + encodeURIComponent(userEmail) +
        "&include_tickets=" + (includeTickets ? "1" : "0") +
        "&include_history=" + (includeHistory ? "1" : "0");
      const { statusCode, body } = await supportApiRequest(path);
      if (statusCode !== 200) {
        let errMsg = "Support API error";
        try {
          const j = JSON.parse(body);
          if (j.error) errMsg = j.error;
        } catch (_) { errMsg = body || errMsg; }
        return { content: [{ type: "text", text: errMsg }], isError: true };
      }
      const payload = JSON.parse(body);
      return { content: [{ type: "text", text: JSON.stringify(payload, null, 2) }] };
    });
  }

  if (name === "get_ticket_details") {
    return handleTool(name, async () => {
      const ticketId = (args?.ticket_id || "").toString().trim();
      if (!ticketId) {
        return { content: [{ type: "text", text: "Укажите ticket_id." }], isError: true };
      }
      const path = "/ticket/" + encodeURIComponent(ticketId);
      const { statusCode, body } = await supportApiRequest(path);
      if (statusCode === 404) {
        try {
          const j = JSON.parse(body);
          return { content: [{ type: "text", text: j.error || "Тикет не найден" }], isError: true };
        } catch (_) {
          return { content: [{ type: "text", text: "Тикет не найден" }], isError: true };
        }
      }
      if (statusCode !== 200) {
        let errMsg = "Support API error";
        try {
          const j = JSON.parse(body);
          if (j.error) errMsg = j.error;
        } catch (_) { errMsg = body || errMsg; }
        return { content: [{ type: "text", text: errMsg }], isError: true };
      }
      const ticket = JSON.parse(body);
      const lines = [
        "Тикет #" + ticket.id,
        "Тема: " + (ticket.subject || ""),
        "Статус: " + (ticket.status || ""),
        "Создан: " + (ticket.created_at || ""),
        "Последнее сообщение: " + (ticket.last_message || ""),
        "",
        "Сообщения:",
        ...(ticket.messages || []).map((m) => "[" + (m.at || "") + "] " + (m.from || "") + ": " + (m.text || "")),
      ];
      return { content: [{ type: "text", text: lines.join("\n") }] };
    });
  }

  if (name === "create_ticket") {
    return handleTool(name, async () => {
      const userEmail = (args?.user_email || "").toString().trim();
      const message = (args?.message || "").toString().trim();
      if (!userEmail) {
        return { content: [{ type: "text", text: "Укажите user_email." }], isError: true };
      }
      if (!message) {
        return { content: [{ type: "text", text: "Укажите message." }], isError: true };
      }
      const subject = (args?.subject || "").toString().trim() || undefined;
      const body = { user_email: userEmail, message, subject };
      const { statusCode, body: resBody } = await supportApiRequest("/ticket", "POST", body);
      if (statusCode !== 201 && statusCode !== 200) {
        let errMsg = "Не удалось создать тикет";
        try {
          const j = JSON.parse(resBody);
          if (j.error) errMsg = j.error;
        } catch (_) { errMsg = resBody || errMsg; }
        return { content: [{ type: "text", text: errMsg }], isError: true };
      }
      const created = JSON.parse(resBody);
      const text = "Тикет создан: " + created.id + "\nТема: " + (created.subject || "") + "\nСтатус: " + (created.status || "open");
      return { content: [{ type: "text", text }] };
    });
  }

  throw new Error("Неизвестный инструмент: " + name);
});

// Запуск сервера
async function main() {
  const transport = new StdioServerTransport();
  await server.connect(transport);
  console.error("Unified MCP server running on stdio");
}

main().catch((error) => {
  console.error("Fatal error:", error);
  process.exit(1);
});
