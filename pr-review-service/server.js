#!/usr/bin/env node
/**
 * PR Review Service
 * - POST /webhook/pr-review: receive diff from GitHub Action, call OpenAI, store review by author
 * - GET /reviews?github_username=XXX: return unread reviews for user, mark as read
 * - POST /register: register github_username + telegram_chat_id for Telegram delivery
 *
 * Env: OPENAI_API_KEY, TELEGRAM_BOT_TOKEN (optional), WEBHOOK_SECRET (optional), PORT (default 3000)
 */

import express from "express";
import fs from "fs";
import path from "path";
import { fileURLToPath } from "url";
import https from "https";

const __dirname = path.dirname(fileURLToPath(import.meta.url));
const PORT = parseInt(process.env.PORT || "3000", 10);
const OPENAI_API_KEY = (process.env.OPENAI_API_KEY || "").trim();
const TELEGRAM_BOT_TOKEN = (process.env.TELEGRAM_BOT_TOKEN || "").trim();
const WEBHOOK_SECRET = (process.env.WEBHOOK_SECRET || "").trim();

const DATA_DIR = path.join(__dirname, "data");
const REVIEWS_FILE = path.join(DATA_DIR, "reviews.json");
const REGISTRATIONS_FILE = path.join(DATA_DIR, "registrations.json");

let reviewsByUser = {}; // github_username -> [{ id, repo, pr_number, pr_title, reviewText, createdAt, read }]
let registrations = {}; // github_username -> { telegram_chat_id }
let nextId = 1;

function ensureDataDir() {
  if (!fs.existsSync(DATA_DIR)) {
    fs.mkdirSync(DATA_DIR, { recursive: true });
  }
}

function loadReviews() {
  ensureDataDir();
  if (fs.existsSync(REVIEWS_FILE)) {
    try {
      const raw = fs.readFileSync(REVIEWS_FILE, "utf8");
      const data = JSON.parse(raw);
      reviewsByUser = data.reviewsByUser || {};
      nextId = Math.max(nextId, ...Object.values(reviewsByUser).flat().map((r) => r.id || 0), 0) + 1;
    } catch (e) {
      console.error("Load reviews error:", e.message);
    }
  }
}

function saveReviews() {
  ensureDataDir();
  fs.writeFileSync(REVIEWS_FILE, JSON.stringify({ reviewsByUser }, null, 2), "utf8");
}

function loadRegistrations() {
  ensureDataDir();
  if (fs.existsSync(REGISTRATIONS_FILE)) {
    try {
      registrations = JSON.parse(fs.readFileSync(REGISTRATIONS_FILE, "utf8"));
    } catch (e) {
      console.error("Load registrations error:", e.message);
    }
  }
}

function saveRegistrations() {
  ensureDataDir();
  fs.writeFileSync(REGISTRATIONS_FILE, JSON.stringify(registrations, null, 2), "utf8");
}

function openaiChatCompletion(messages) {
  return new Promise((resolve, reject) => {
    const body = JSON.stringify({
      model: "gpt-4o-mini",
      messages,
      max_tokens: 2000,
      temperature: 0.3,
    });
    const options = {
      hostname: "api.openai.com",
      path: "/v1/chat/completions",
      method: "POST",
      headers: {
        "Content-Type": "application/json",
        "Authorization": `Bearer ${OPENAI_API_KEY}`,
        "Content-Length": Buffer.byteLength(body, "utf8"),
      },
    };
    const req = https.request(options, (res) => {
      let data = "";
      res.on("data", (chunk) => { data += chunk; });
      res.on("end", () => {
        try {
          const json = JSON.parse(data);
          if (json.error) {
            reject(new Error(json.error.message || JSON.stringify(json.error)));
            return;
          }
          const text = json.choices?.[0]?.message?.content?.trim() || "";
          resolve(text);
        } catch (e) {
          reject(new Error(`OpenAI parse error: ${e.message}`));
        }
      });
    });
    req.on("error", reject);
    req.write(body);
    req.end();
  });
}

async function sendTelegram(chatId, text) {
  if (!TELEGRAM_BOT_TOKEN) return;
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
      res.on("end", () => resolve());
    });
    req.on("error", reject);
    req.write(body);
    req.end();
  });
}

const app = express();
app.use(express.json({ limit: "100kb" }));

app.post("/webhook/pr-review", async (req, res) => {
  if (WEBHOOK_SECRET) {
    const received = (req.headers["x-webhook-secret"] || "").trim();
    const expected = String(WEBHOOK_SECRET).trim();
    if (received !== expected) {
      return res.status(401).json({ error: "Invalid webhook secret" });
    }
  }
  const { diff, repo, pr_number, author, pr_title } = req.body || {};
  if (!diff || !repo || !author) {
    return res.status(400).json({ error: "Missing diff, repo or author" });
  }
  if (!OPENAI_API_KEY) {
    return res.status(500).json({ error: "OPENAI_API_KEY not set" });
  }
  const prNum = pr_number != null ? pr_number : "?";
  const title = pr_title || "";
  const diffTrimmed = String(diff).slice(0, 70000);

  const systemPrompt = `Ты — ревьюер кода. Проанализируй diff и выдай структурированный отчёт на русском в формате:

1) Найденные проблемы (стиль, дублирование, нарушение соглашений)
2) Потенциальные баги (логика, краевые случаи)
3) Советы по улучшению (рефакторинг, тесты, документация)

Будь конкретным: указывай файлы и строки где возможно. Не придумывай то, чего нет в diff.`;

  const userContent = `Репозиторий: ${repo}\nPR #${prNum}${title ? ` — ${title}` : ""}\n\nDiff:\n${diffTrimmed}`;

  try {
    const reviewText = await openaiChatCompletion([
      { role: "system", content: systemPrompt },
      { role: "user", content: userContent },
    ]);
    const authorNorm = String(author).trim().toLowerCase();
    if (!reviewsByUser[authorNorm]) reviewsByUser[authorNorm] = [];
    const review = {
      id: nextId++,
      repo: String(repo),
      pr_number: prNum,
      pr_title: String(title),
      reviewText,
      createdAt: new Date().toISOString(),
      read: false,
    };
    reviewsByUser[authorNorm].push(review);
    saveReviews();

    const chatId = registrations[authorNorm]?.telegram_chat_id || registrations[author]?.telegram_chat_id;
    if (chatId) {
      const telegramText = `Ревью PR #${prNum} (${repo})\n\n${reviewText.slice(0, 3500)}`;
      await sendTelegram(chatId, telegramText).catch((e) => console.error("Telegram send error:", e.message));
    }

    return res.status(200).json({ ok: true, reviewId: review.id });
  } catch (e) {
    console.error("Review generation error:", e);
    return res.status(500).json({ error: e.message || "OpenAI error" });
  }
});

app.get("/reviews", (req, res) => {
  const username = (req.query.github_username || "").trim();
  if (!username) {
    return res.status(400).json({ error: "Missing github_username" });
  }
  const key = username.toLowerCase();
  const list = reviewsByUser[key] || [];
  const unread = list.filter((r) => !r.read);
  unread.forEach((r) => { r.read = true; });
  if (unread.length) saveReviews();
  return res.json({ reviews: unread });
});

app.post("/register", (req, res) => {
  const { github_username, telegram_chat_id } = req.body || {};
  const user = (github_username || "").trim();
  if (!user) {
    return res.status(400).json({ error: "Missing github_username" });
  }
  const key = user.toLowerCase();
  registrations[key] = { telegram_chat_id: String(telegram_chat_id || "").trim() };
  if (user !== key) registrations[user] = registrations[key];
  saveRegistrations();
  return res.json({ ok: true });
});

app.get("/health", (req, res) => {
  res.json({ ok: true, openai: !!OPENAI_API_KEY });
});

loadReviews();
loadRegistrations();

app.listen(PORT, "0.0.0.0", () => {
  console.error(`PR Review Service listening on 0.0.0.0:${PORT}`);
});
