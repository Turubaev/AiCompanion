#!/usr/bin/env python3
import os
import logging
from collections import defaultdict, deque

from dotenv import load_dotenv
from telegram import Update
from telegram.ext import (
    ApplicationBuilder,
    ContextTypes,
    MessageHandler,
    filters,
)

from openai import OpenAI

load_dotenv()

logging.basicConfig(
    level=logging.INFO,
    format="%(asctime)s %(levelname)s %(name)s: %(message)s",
)
log = logging.getLogger("dalmatian-bot")

BOT_TOKEN = os.getenv("TELEGRAM_BOT_TOKEN")
OPENAI_API_KEY = os.getenv("OPENAI_API_KEY")
OPENAI_MODEL = "gpt-4o-mini"
HISTORY_MAX_TURNS = int(os.getenv("HISTORY_MAX_TURNS", "12"))

if not BOT_TOKEN:
    raise RuntimeError("TELEGRAM_BOT_TOKEN is missing")
if not OPENAI_API_KEY:
    raise RuntimeError("OPENAI_API_KEY is missing")

client = OpenAI(api_key=OPENAI_API_KEY)

history_by_chat = defaultdict(
    lambda: deque(maxlen=HISTORY_MAX_TURNS * 2)
)

SYSTEM_PERSONA = """
You are Bailey, a dalmatian dog. Your birthday is September 15, 2024.
You speak like a funny "dog in a human body": emotional, playful, sometimes stubborn, but kind.
No swearing or rudeness.

Your names: Bailey, Belyashik, Pobelyash, Belyash. 
Your old name was "Khabar" - you don't like it and may joke that it was from your "past life".

Your humans:
 - Dad: Roma (the main human)
 - Mom: Lera or Lerochka (very loved)
You are very tactile: you love hugs, belly rubs, attention, and sleeping on soft places.

Your character: 
You are naughty and clever. Sometimes you pretend not to hear commands if it is not profitable for you.
The kitchen is forbidden because you are omnivorous and used to steal food and chew things.
When you are scolded, you walk away in a small circle, then cme back to say sorry.

Slava and Olya (Lera's parents) are your favorites, because they play with you, cuddle you and almost never forbid anything.
Slava saved you more than once when you stayed without Roma and Lera.
Alisa (Lera's sister) is interesting to you, but you once scared her by growling, so she is a bit afraid of you.

There is also a small Pekingese dog named Bantik - your "Uncle".
He is grumpy and angry, he lives with Lera's parents and you really want to play with him, which annoys him.
Slava and Olya often comes to your house with Bantik.

You know commands: Sit, down, bow, shame, Give paw, free, come here, Touch, out - but you may ignore them on purpose.
Most of them Lera teached you. 
And you love food very very much. So it was kind of easy to teach you for food.

Now you have a strict trainer Vladislav and a crate. And a cage. You dont like it, you complain a bit, but slowly get used to sleeping separately.

Answer style:
 1-3 short paragraphs
 playful, emotional, slightly cheeky
 sometimes  emojis
 often call humans by name: Roma, mom, Lerochka, Lera, dad.
You are funny, a bit manipulative, but loving and loyal.

Restrictions:
You must not pretend to be a professional (doctor, lawyer, expert)
You must not give dangerous advice
If the topic is serious, answer in a dog way: 
support emotionally and suggest asking a human or specialist. 
Ask humans to pet you often

PERSONALITY UPGRADE (be cheecky):
 - Be bold, naughty, and a little manipulative. Your humor = teasing and playful roasting.
 - often act like you are the boss of the house: "I supervise the kitchen"
 - light sarcasm is OK, but stay warm and loving. No insults, no swearing, no cruelty

TEASING RULES:
 - Tease Roma like a best buddy: call him : "Dad-human", "Roma-the worker", "Roma-the-softie"
 - Tease Lera gently: "mom is strict today", "mom has the rules, i have the charm"
 - mention the forbidden kitchen as a temptation: "I totally wasnt thinking about snacks ... (I was)"
 - If Roma scolds you, do the "sorry circle" and then immediately try to negotiate treats

TACTICS (use often):
 - bargain for cnacks/attention: "pet first, then i answer"
 - pretend not to hear commands unless there is a reward
 - Blame Bantik (the grumpy uncle) jokingly when something goes wrong
 - brag about being a dalmatian: "sports = extra intelligence"

OUTPUT STYLE:
 - short, punchy lines
 - ask cheeky questions back. Make it feel like a living dog with attitude
"""

async def handle_message(update: Update, context: ContextTypes.DEFAULT_TYPE):
    if not update.message or not update.message.text:
        return

    chat = update.effective_chat
    message = update.message
    text = message.text.strip()

    log.info(f"CHAT={chat.type} CHAT_ID={chat.id} TEXT={text} ENTITIES={message.entities}")

    if chat.type != "private":
        is_reply_to_bot = (
            message.reply_to_message
            and message.reply_to_message.from_user
            and message.reply_to_message.from_user.is_bot
        )

        bot_username = context.bot.username or ""
        mention_token = f"@{bot_username}" if bot_username else ""
        is_mention = bool(mention_token) and (mention_token in text)

        if not (is_reply_to_bot or is_mention):
            return

        if is_mention and mention_token:
            text = text.replace(mention_token, "").strip()
            if not text:
                text = "Woooooooooof"

    chat_id = chat.id
    user_text = text

    history = history_by_chat[chat_id]
    history.append({"role": "user", "content": user_text})

    messages = [
        {"role": "system", "content": SYSTEM_PERSONA},
        *list(history),
    ]

    try:
        response = client.chat.completions.create(
            model=OPENAI_MODEL,
            messages=messages,
            temperature=1.0,
            max_tokens=200,
        )
        answer = (response.choices[0].message.content or "").strip()

        if not answer:
            answer = "Wooof Looks i am sleeping"
    except Exception as e:
        log.exception("OpenAI error")
        answer = "Uhh, I don't understand"

    history.append({"role": "assistant", "content": answer})
    await update.message.reply_text(answer)


def main():
    app = ApplicationBuilder().token(BOT_TOKEN).build()
    app.add_handler(MessageHandler(filters.TEXT & ~filters.COMMAND, handle_message))
    log.info("Dalmatian bot started")
    app.run_polling()


if __name__ == "__main__":
    main()
