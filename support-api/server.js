#!/usr/bin/env node
/**
 * Support API
 * - GET /user-context?email=...&include_tickets=1&include_history=1  -> { user_info, open_tickets, recent_interactions }
 * - GET /ticket/:id -> ticket details or 404
 * - POST /ticket -> { user_email, subject?, message } -> create ticket
 *
 * Env: PORT (default 3010)
 */

import express from "express";
import fs from "fs";
import path from "path";
import { fileURLToPath } from "url";

const __dirname = path.dirname(fileURLToPath(import.meta.url));
const PORT = parseInt(process.env.PORT || "3010", 10);
const DATA_DIR = path.join(__dirname, "data");
const DATA_FILE = path.join(DATA_DIR, "data.json");

let state = {
  users: {},
  tickets: [],
  interactions: [],
  nextTicketId: 1,
};

function ensureDataDir() {
  if (!fs.existsSync(DATA_DIR)) {
    fs.mkdirSync(DATA_DIR, { recursive: true });
  }
}

function loadData() {
  ensureDataDir();
  if (fs.existsSync(DATA_FILE)) {
    try {
      const raw = fs.readFileSync(DATA_FILE, "utf8");
      const data = JSON.parse(raw);
      state.users = data.users || {};
      state.tickets = data.tickets || [];
      state.interactions = data.interactions || [];
      state.nextTicketId = data.nextTicketId || 1;
      const maxId = state.tickets.reduce((acc, t) => {
        const n = parseInt((t.id || "").replace(/^TICKET-/, ""), 10);
        return isNaN(n) ? acc : Math.max(acc, n);
      }, 0);
      if (maxId >= state.nextTicketId) state.nextTicketId = maxId + 1;
    } catch (e) {
      console.error("Load data error:", e.message);
    }
  }
}

function saveData() {
  ensureDataDir();
  fs.writeFileSync(
    DATA_FILE,
    JSON.stringify(
      {
        users: state.users,
        tickets: state.tickets,
        interactions: state.interactions,
        nextTicketId: state.nextTicketId,
      },
      null,
      2
    ),
    "utf8"
  );
}

function ensureUser(email) {
  const e = (email || "").trim().toLowerCase();
  if (!e) return null;
  if (!state.users[e]) {
    const name = e.split("@")[0] || "User";
    state.users[e] = {
      name,
      email: e,
      plan: "free",
      created_at: new Date().toISOString(),
    };
    saveData();
  }
  return state.users[e];
}

function isoNow() {
  return new Date().toISOString();
}

const app = express();
app.use(express.json({ limit: "1mb" }));

// GET /user-context?email=...&include_tickets=1&include_history=1
app.get("/user-context", (req, res) => {
  const email = (req.query.email || "").trim().toLowerCase();
  if (!email) {
    return res.status(400).json({ error: "email required" });
  }
  const includeTickets = req.query.include_tickets !== "0" && req.query.include_tickets !== "false";
  const includeHistory = req.query.include_history !== "0" && req.query.include_history !== "false";

  const userInfo = ensureUser(email);
  if (!userInfo) {
    return res.status(400).json({ error: "invalid email" });
  }

  const openTickets = includeTickets
    ? state.tickets.filter((t) => t.user_email.toLowerCase() === email && t.status !== "closed")
    : [];
  const recentInteractions = includeHistory
    ? state.interactions
        .filter((i) => (i.user_email || "").toLowerCase() === email)
        .slice(-20)
        .reverse()
    : [];

  res.json({
    user_info: {
      name: userInfo.name,
      email: userInfo.email,
      plan: userInfo.plan,
      created_at: userInfo.created_at,
    },
    open_tickets: openTickets.map((t) => ({
      id: t.id,
      subject: t.subject,
      status: t.status,
      created_at: t.created_at,
      last_message: t.last_message,
    })),
    recent_interactions: recentInteractions.map((i) => ({
      type: i.type || "chat",
      timestamp: i.timestamp,
      summary: i.summary || "",
    })),
  });
});

// GET /ticket/:id
app.get("/ticket/:id", (req, res) => {
  const id = (req.params.id || "").trim();
  if (!id) {
    return res.status(400).json({ error: "ticket id required" });
  }
  const ticket = state.tickets.find((t) => t.id === id || t.id === "TICKET-" + id);
  if (!ticket) {
    return res.status(404).json({ error: "Тикет не найден" });
  }
  res.json({
    id: ticket.id,
    subject: ticket.subject,
    status: ticket.status,
    created_at: ticket.created_at,
    last_message: ticket.last_message,
    user_email: ticket.user_email,
    messages: ticket.messages || [],
  });
});

// POST /ticket -> { user_email, subject?, message }
app.post("/ticket", (req, res) => {
  const userEmail = (req.body?.user_email || "").trim().toLowerCase();
  const message = (req.body?.message || "").trim();
  if (!userEmail) {
    return res.status(400).json({ error: "user_email required" });
  }
  if (!message) {
    return res.status(400).json({ error: "message required" });
  }

  ensureUser(userEmail);

  const subject =
    (req.body?.subject || "").trim() ||
    message.split(/\n/)[0]?.trim().slice(0, 200) ||
    "Без темы";
  const id = "TICKET-" + state.nextTicketId++;
  const created_at = isoNow();
  const ticket = {
    id,
    user_email: userEmail,
    subject,
    status: "open",
    created_at,
    last_message: message,
    messages: [{ from: "user", text: message, at: created_at }],
  };
  state.tickets.push(ticket);
  state.interactions.push({
    user_email: userEmail,
    type: "ticket_created",
    timestamp: created_at,
    summary: "Создан тикет " + id + ": " + subject,
  });
  saveData();

  res.status(201).json({
    id: ticket.id,
    subject: ticket.subject,
    status: ticket.status,
    created_at: ticket.created_at,
  });
});

loadData();
app.listen(PORT, () => {
  console.error("Support API listening on port " + PORT);
});
