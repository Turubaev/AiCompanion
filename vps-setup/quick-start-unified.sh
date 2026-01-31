#!/bin/bash

# Быстрый старт - установка объединенного MCP сервера (GitHub + Currency + Tinkoff Invest API)
# Использование: bash quick-start-unified.sh YOUR_GITHUB_TOKEN [TINKOFF_INVEST_TOKEN]

set -e

if [ -z "$1" ]; then
    echo "Использование: bash quick-start-unified.sh YOUR_GITHUB_TOKEN"
    echo "Опционально: bash quick-start-unified.sh YOUR_GITHUB_TOKEN TINKOFF_INVEST_TOKEN"
    echo ""
    echo "Получите GitHub Personal Access Token на https://github.com/settings/tokens"
    echo "Нужны права: repo, read:org, read:user"
    exit 1
fi

GITHUB_TOKEN="$1"
TINKOFF_TOKEN="${2:-}"

echo "=== Быстрая установка Unified MCP Server (GitHub + Currency) ==="
echo ""

# Обновление системы
echo "[1/7] Обновление системы..."
sudo apt-get update -qq
sudo apt-get upgrade -y -qq

# Установка Node.js
echo "[2/7] Установка Node.js..."
if ! command -v node &> /dev/null; then
    curl -fsSL https://deb.nodesource.com/setup_20.x | sudo -E bash - > /dev/null 2>&1
    sudo apt-get install -y nodejs > /dev/null 2>&1
fi

# Установка socat
echo "[3/7] Установка socat..."
sudo apt-get install -y socat > /dev/null 2>&1

# Установка Python и git для Tinkoff Invest API
echo "[4/8] Установка Python и зависимостей для Tinkoff Invest API..."
sudo apt-get install -y python3 python3-pip git > /dev/null 2>&1
# Официальный SDK из GitHub. Зависимость "tinkoff" в PyPI отсутствует — ставим без неё, затем остальные deps
sudo pip3 install -q --no-deps "git+https://github.com/RussianInvestments/invest-python.git"
# tinkoff-investments требует protobuf<5.0.0 — иначе возможны ошибки при запросе курса
sudo pip3 install -q grpcio 'protobuf>=4.25.1,<5.0.0' python-dateutil cachetools deprecation

# Создание директории
echo "[5/8] Создание директории и файлов..."
mkdir -p ~/mcp-unified
cd ~/mcp-unified

# Создание package.json
cat > package.json << 'EOF'
{
  "name": "unified-mcp-server",
  "version": "1.0.0",
  "description": "Объединенный MCP сервер с инструментами GitHub и Currency",
  "type": "module",
  "main": "index.js",
  "scripts": {
    "start": "node index.js"
  },
  "dependencies": {
    "@modelcontextprotocol/sdk": "^1.0.0"
  }
}
EOF

# Создание index.js
cat > index.js << 'EOFILE'
#!/usr/bin/env node

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

server.setRequestHandler(ListToolsRequestSchema, async () => {
  const tools = [
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

server.setRequestHandler(CallToolRequestSchema, async (request) => {
  const { name, arguments: args } = request.params;

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

async function main() {
  const transport = new StdioServerTransport();
  await server.connect(transport);
  console.error("Unified MCP server running on stdio");
}

main().catch((error) => {
  console.error("Fatal error:", error);
  process.exit(1);
});
EOFILE

# Создание tinkoff_rate.py
cat > tinkoff_rate.py << 'PYEOF'
#!/usr/bin/env python3

import json
import os
import sys
from datetime import datetime, timezone

try:
    from tinkoff.invest import Client
except Exception as e:
    print(json.dumps({"ok": False, "error": f"Python dependency error: {e}"}), flush=True)
    sys.exit(2)


def _now_iso():
    return datetime.now(timezone.utc).isoformat()


def main():
    token = os.getenv("TINKOFF_INVEST_TOKEN", "").strip()
    if not token:
        print(json.dumps({"ok": False, "error": "TINKOFF_INVEST_TOKEN is not set"}), flush=True)
        sys.exit(1)

    # We support only USD/RUB and RUB/USD for now.
    from_ccy = (sys.argv[1] if len(sys.argv) > 1 else "USD").upper()
    to_ccy = (sys.argv[2] if len(sys.argv) > 2 else "RUB").upper()

    if not ((from_ccy, to_ccy) in [("USD", "RUB"), ("RUB", "USD")]):
        print(json.dumps({"ok": False, "error": f"Unsupported pair {from_ccy}/{to_ccy}"}), flush=True)
        sys.exit(1)

    # На MOEX в production тикер USD/RUB обычно USD000UTSTOM (CETS). Пробуем несколько вариантов.
    TICKERS_TO_TRY = ["USD000UTSTOM", "USDRUB_TOM", "USDRUB", "USD/RUB"]
    CLASS_CODE_CETS = "CETS"

    with Client(token) as client:
        inst = None
        used_ticker = None

        for ticker in TICKERS_TO_TRY:
            found = client.instruments.find_instrument(query=ticker)
            instruments = getattr(found, "instruments", []) or []
            for x in instruments:
                t = getattr(x, "ticker", "") or ""
                if t == ticker or (ticker in t and "RUB" in (t + getattr(x, "name", ""))):
                    inst = x
                    used_ticker = t
                    break
            if inst is not None:
                break
            if instruments and not inst:
                inst = instruments[0]
                used_ticker = getattr(instruments[0], "ticker", ticker)
                break

        if inst is None:
            try:
                from tinkoff.invest import InstrumentIdType, InstrumentRequest
                req = InstrumentRequest(id_type=InstrumentIdType.INSTRUMENT_ID_TYPE_TICKER, id="USD000UTSTOM", class_code=CLASS_CODE_CETS)
                resp = client.instruments.get_instrument_by(req)
                if resp and getattr(resp, "instrument", None):
                    inst = resp.instrument
                    used_ticker = getattr(inst, "ticker", "USD000UTSTOM")
            except Exception:
                pass

        if inst is None:
            try:
                curr_resp = client.instruments.currencies()
                currencies = getattr(curr_resp, "instruments", []) or []
                for c in currencies:
                    t = (getattr(c, "ticker", "") or "").upper()
                    n = (getattr(c, "name", "") or "").upper()
                    if "USD" in t and "RUB" in (t + n):
                        inst = c
                        used_ticker = getattr(c, "ticker", "USD/RUB")
                        break
            except Exception:
                pass

        if inst is None:
            print(json.dumps({"ok": False, "error": "Instrument not found for USD/RUB. Tried: " + ", ".join(TICKERS_TO_TRY)}), flush=True)
            sys.exit(1)

        used_ticker = used_ticker or getattr(inst, "ticker", None) or "USD/RUB"
        figi = getattr(inst, "figi", None)
        uid = getattr(inst, "uid", None)

        # Prefer instrument UID if available, otherwise FIGI.
        if uid:
            last = client.market_data.get_last_prices(instrument_id=[uid])
        elif figi:
            last = client.market_data.get_last_prices(figi=[figi])
        else:
            print(json.dumps({"ok": False, "error": "Instrument has neither uid nor figi"}), flush=True)
            sys.exit(1)

        prices = getattr(last, "last_prices", []) or []
        if not prices:
            print(json.dumps({"ok": False, "error": "No last prices returned"}), flush=True)
            sys.exit(1)

        lp = prices[0]
        price = getattr(lp, "price", None)
        if price is None:
            print(json.dumps({"ok": False, "error": "No price in response"}), flush=True)
            sys.exit(1)

        # price is Quotation(units, nano)
        units = getattr(price, "units", 0)
        nano = getattr(price, "nano", 0)
        usd_rub = float(units) + float(nano) / 1_000_000_000.0
        if usd_rub <= 0:
            print(json.dumps({"ok": False, "error": "Invalid price returned"}), flush=True)
            sys.exit(1)

        if from_ccy == "USD" and to_ccy == "RUB":
            rate = usd_rub
            pair = "USD/RUB"
        else:
            rate = 1.0 / usd_rub
            pair = "RUB/USD"

        ts = getattr(lp, "time", None)
        updated_at = ts.isoformat() if ts else _now_iso()

        print(
            json.dumps(
                {
                    "ok": True,
                    "pair": pair,
                    "rate": rate,
                    "updated_at": updated_at,
                    "source": "tinkoff-invest-api",
                    "ticker": used_ticker,
                }
            ),
            flush=True,
        )


if __name__ == "__main__":
    try:
        main()
    except Exception as e:
        print(json.dumps({"ok": False, "error": f"Unexpected error: {str(e)}"}), flush=True)
        sys.exit(1)
PYEOF

chmod +x tinkoff_rate.py

# Установка зависимостей
echo "[6/8] Установка зависимостей..."
npm install > /dev/null 2>&1

# Создание systemd service с socat
echo "[7/8] Настройка systemd service..."
sudo tee /etc/systemd/system/mcp-unified.service > /dev/null << EOF
[Unit]
Description=Unified MCP Server (GitHub + Currency)
After=network.target

[Service]
Type=simple
User=$USER
WorkingDirectory=$HOME/mcp-unified
Environment="GITHUB_PERSONAL_ACCESS_TOKEN=$GITHUB_TOKEN"
Environment="TINKOFF_INVEST_TOKEN=$TINKOFF_TOKEN"
ExecStart=/usr/bin/socat TCP-LISTEN:8080,fork,reuseaddr EXEC:"stdbuf -oL node $HOME/mcp-unified/index.js"
Restart=always
RestartSec=10
StandardOutput=journal
StandardError=journal

[Install]
WantedBy=multi-user.target
EOF

# Остановка старого GitHub сервера (если запущен)
echo "[8/8] Остановка старого GitHub сервера и запуск объединенного..."
if sudo systemctl is-active --quiet mcp-github; then
    sudo systemctl stop mcp-github.service
    sudo systemctl disable mcp-github.service
fi

# Запуск нового сервиса
sudo systemctl daemon-reload
sudo systemctl enable mcp-unified.service > /dev/null 2>&1
sudo systemctl restart mcp-unified.service

# Проверка
sleep 3
if sudo systemctl is-active --quiet mcp-unified; then
    echo ""
    echo "✅ Установка завершена успешно!"
    echo ""
    echo "Объединенный сервер работает на порту: 8080"
    echo "Доступные инструменты:"
    echo "  - get_currency_rate (курс USD/RUB)"
    echo "  - search_repositories (поиск репозиториев GitHub)"
    echo "  - get_file_contents (получение файлов из GitHub)"
    if [ -n "$TINKOFF_TOKEN" ]; then
      echo "  - источник курса: Tinkoff Invest API ✅"
    else
      echo "  - источник курса: fallback (публичный), т.к. TINKOFF_INVEST_TOKEN не задан"
    fi
    echo ""
    echo "Статус сервиса:"
    sudo systemctl status mcp-unified.service --no-pager -l | head -15
    echo ""
    echo "Проверка порта:"
    ss -tlnp | grep 8080 || echo "Порт еще не открыт, подождите несколько секунд"
    echo ""
    echo "Полезные команды:"
    echo "  sudo systemctl status mcp-unified    # Статус"
    echo "  sudo journalctl -u mcp-unified -f      # Логи"
    echo "  sudo systemctl restart mcp-unified    # Перезапуск"
else
    echo ""
    echo "⚠️  Сервис установлен, но не запущен. Проверьте логи:"
    echo "  sudo journalctl -u mcp-unified -n 50"
fi
