# PR Review Service

HTTP service for PR review flow: receives webhook from GitHub Action with diff, generates review via OpenAI, stores by GitHub username, serves reviews to the Android app.

## Endpoints

- **POST /webhook/pr-review** — called by GitHub Action. Body: `{ "diff", "repo", "pr_number", "author", "pr_title" }`. Optional header `X-Webhook-Secret`.
- **GET /reviews?github_username=XXX** — returns unread reviews for user (marks them read).
- **POST /register** — register `github_username` + `telegram_chat_id` for Telegram delivery.
- **GET /health** — readiness check.

## Env

- `OPENAI_API_KEY` — required for review generation.
- `TELEGRAM_BOT_TOKEN` — optional; if set and user registered, review is sent to Telegram.
- `WEBHOOK_SECRET` — optional; if set, webhook must send `X-Webhook-Secret` with this value.
- `PORT` — default 3000.

## Deploy on VPS

```bash
cd ~/pr-review-service   # or copy this folder to VPS
npm install --omit=dev
# Set env in systemd or .env
node server.js
```

Data is stored in `./data/reviews.json` and `./data/registrations.json`.
