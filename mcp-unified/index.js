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
import { execFile } from "child_process";
import { fileURLToPath } from "url";
import path from "path";
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

const __filename = fileURLToPath(import.meta.url);
const __dirname = path.dirname(__filename);

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
