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
  ];

  return { tools };
});

// Обработка вызовов инструментов
server.setRequestHandler(CallToolRequestSchema, async (request) => {
  const { name, arguments: args } = request.params;

  // Обработка currency инструмента
  if (name === "get_currency_rate") {
    try {
      const from = (args?.from || "USD").toString().toUpperCase();
      const to = (args?.to || "RUB").toString().toUpperCase();

      let rateInfo;
      let sourceLabel;
      // Если есть токен Тинькофф — используем его, иначе fallback на публичный источник
      if (TINKOFF_TOKEN) {
        rateInfo = await getCurrencyRateFromTinkoff(from, to);
        sourceLabel = "tinkoff-invest-api";
      } else {
        const currencyData = await getCurrencyRate();
        // Мы получаем базовый курс USD -> RUB из источника.
        const usdRub = Number(currencyData.rate);
        if (!Number.isFinite(usdRub) || usdRub <= 0) {
          throw new Error("Некорректный курс от источника");
        }
        if (from === "USD" && to === "RUB") {
          rateInfo = { pair: "USD/RUB", rate: usdRub, updated_at: null, source: currencyData.source };
        } else if (from === "RUB" && to === "USD") {
          rateInfo = { pair: "RUB/USD", rate: 1 / usdRub, updated_at: null, source: currencyData.source };
        } else {
          throw new Error(`Поддерживаются только пары USD/RUB и RUB/USD (получено: ${from}/${to})`);
        }
        sourceLabel = currencyData.source;
      }

      const timestamp = rateInfo.updated_at
        ? new Date(rateInfo.updated_at).toLocaleString("ru-RU")
        : "неизвестно";
      
      return {
        content: [
          {
            type: "text",
            text:
              `Текущий курс ${rateInfo.pair}: ${Number(rateInfo.rate).toFixed(6)}\n\n` +
              `Обновлено: ${timestamp}\n` +
              `Источник: ${sourceLabel}`,
          },
        ],
      };
    } catch (error) {
      return {
        content: [
          {
            type: "text",
            text: `Ошибка при получении курса валют: ${error.message}`,
          },
        ],
        isError: true,
      };
    }
  }

  // Обработка GitHub инструментов
  if (name === "search_repositories") {
    try {
      const query = args.query || "";
      const page = args.page || 1;
      const perPage = Math.min(args.perPage || 30, 100);

      const endpoint = `/search/repositories?q=${encodeURIComponent(query)}&page=${page}&per_page=${perPage}`;
      const result = await githubApiRequest(endpoint);

      const repos = result.items || [];
      const text = repos.length > 0
        ? repos.map((repo, idx) => 
            `${idx + 1}. **${repo.full_name}**\n` +
            `   ${repo.description || "Нет описания"}\n` +
            `   ⭐ ${repo.stargazers_count} | 🔀 ${repo.forks_count} | 📝 ${repo.language || "N/A"}\n` +
            `   🔗 ${repo.html_url}`
          ).join("\n\n") +
          `\n\nВсего найдено: ${result.total_count || 0} репозиториев`
        : "Репозитории не найдены";

      return {
        content: [
          {
            type: "text",
            text: text,
          },
        ],
      };
    } catch (error) {
      return {
        content: [
          {
            type: "text",
            text: `Ошибка при поиске репозиториев: ${error.message}`,
          },
        ],
        isError: true,
      };
    }
  }

  if (name === "get_file_contents") {
    try {
      const owner = args.owner;
      const repo = args.repo;
      const path = args.path;
      const branch = args.branch || "";

      const endpoint = `/repos/${owner}/${repo}/contents/${encodeURIComponent(path)}${branch ? `?ref=${branch}` : ""}`;
      const result = await githubApiRequest(endpoint);

      if (result.type === "file") {
        // Декодируем base64 содержимое
        const content = Buffer.from(result.content, "base64").toString("utf-8");
        return {
          content: [
            {
              type: "text",
              text: `**Файл:** ${result.path}\n**Размер:** ${result.size} байт\n\n\`\`\`\n${content}\n\`\`\``,
            },
          ],
        };
      } else if (result.type === "dir") {
        const items = Array.isArray(result) ? result : [];
        const text = items.length > 0
          ? items.map((item) => 
              `${item.type === "dir" ? "📁" : "📄"} ${item.name}`
            ).join("\n")
          : "Директория пуста";

        return {
          content: [
            {
              type: "text",
              text: `**Директория:** ${path}\n\n${text}`,
            },
          ],
        };
      }
    } catch (error) {
      return {
        content: [
          {
            type: "text",
            text: `Ошибка при получении содержимого файла: ${error.message}`,
          },
        ],
        isError: true,
      };
    }
  }

  throw new Error(`Неизвестный инструмент: ${name}`);
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
