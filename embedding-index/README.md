# Локальный индекс документов с эмбеддингами (AI Companion)

Пайплайн для тестовых профильных документов проекта AI Companion:

1. **Разбивка на чанки** — по параграфам (для .md — с учётом заголовков), с ограничением длины и перекрытием. Поддерживаются .md и .pdf (текст из PDF извлекается через PyMuPDF).
2. **Генерация эмбеддингов** — локально через `sentence-transformers` (модель `paraphrase-multilingual-mpnet-base-v2`, без API).
3. **Сохранение индекса** — JSON (полный дамп чанков + векторы) и FAISS (быстрый поиск по косинусной близости).

## Входные документы (RAG по CloudBuddy)

По умолчанию индексируется **проект CloudBuddy**: `README.md` и все `.md` в папке `docs/`. Путь к CloudBuddy задаётся в `build_index.py`: репозиторий должен лежать **рядом** с Ai_Companion (например `AndroidStudioProjects/CloudBuddy` рядом с `AndroidStudioProjects/Ai_Companion`).

Если папки CloudBuddy нет — скрипт выведет подсказку. Поддерживаются также `.md` и `.pdf`; список файлов можно изменить в `build_index.py` (`DEFAULT_DOCS`).

## Установка и запуск

```bash
cd embedding-index
python -m venv .venv
# Windows: активация venv
#   CMD:  .venv\Scripts\activate.bat
#   PowerShell:  .venv\Scripts\Activate.ps1  (если скрипт есть)
#   Без активации:  .venv\Scripts\python.exe build_index.py
# Linux/macOS:  source .venv/bin/activate
pip install -r requirements.txt
python build_index.py
```

При первом запуске `build_index.py` модель эмбеддингов будет загружена из Hugging Face (требуется интернет).

После выполнения в каталоге `embedding-index/output/` появятся:

- `index.json` — чанки, метаданные, эмбеддинги (удобно для отладки и интеграции).
- `index.faiss` — индекс FAISS для быстрого поиска.

## Поиск по индексу

```bash
python search.py "инвестиции и дивиденды"
python search.py "Kotlin Compose архитектура"
```

## RAG HTTP-сервер (для приложения)

После сборки индекса можно запустить сервер для режима RAG в Android-приложении:

```bash
python rag_server.py --port 5001
# с порогом релевантности (отсечение чанков с score < 0.5):
python rag_server.py --port 5001 --min-score 0.5
# с reranker (cross-encoder для переранжирования, лучше релевантность, медленнее):
python rag_server.py --port 5001 --reranker
```

- **POST** `/search` — тело `{"query": "текст запроса", "top_k": 5, "min_score": 0.5, "use_reranker": false}` → ответ `{"chunks": [{"text": "...", "score": ...}, ...]}`. Параметр `min_score` (опционально): чанки с `score < min_score` не возвращаются. Параметр `use_reranker`: переранжирование через cross-encoder (BAAI/bge-reranker-base).
- **GET** `/health` — проверка готовности и числа загруженных чанков.

В приложении в настройках включается «Включить RAG»; URL сервера задаётся в `local.properties` как `RAG_SERVICE_URL=http://10.0.2.2:5001` (эмулятор) или IP хоста.

## Структура

| Файл | Назначение |
|------|------------|
| `chunker.py` | Разбивка текста на чанки (параграфы, предложения). |
| `pdf_loader.py` | Извлечение текста из PDF (PyMuPDF). |
| `embedder.py` | Загрузка модели и вычисление эмбеддингов. |
| `index_store.py` | Сохранение/загрузка JSON и FAISS, поиск. |
| `build_index.py` | Скрипт сборки индекса. |
| `search.py` | Консольный поиск по запросу. |
| `rag_server.py` | HTTP-сервер для RAG (поиск чанков по запросу). |

Индекс подключён к чату AI Companion через режим RAG (включение в настройках).

## Чеклист: RAG отвечает по проекту CloudBuddy

1. **Репозиторий CloudBuddy** лежит рядом с Ai_Companion (например `D:\AndroidStudioProjects\CloudBuddy` рядом с `Ai_Companion`), в нём есть `README.md` и при необходимости папка `docs/` с `.md`.
2. **Сборка индекса:** из корня проекта:
   ```bash
   cd embedding-index
   .venv\Scripts\activate
   python build_index.py
   ```
   В консоли должно быть что-то вроде `Chunked N docs into M chunks` и `Saved JSON index: ...`.
3. **Запуск RAG-сервера:** в том же каталоге `embedding-index`:
   ```bash
   python rag_server.py --port 5001
   ```
   Сервер должен вывести `RAG index: ... chunks` и слушать порт 5001.
4. **В приложении:** в `local.properties` задан `RAG_SERVICE_URL`:
   - эмулятор: `RAG_SERVICE_URL=http://10.0.2.2:5001`;
   - телефон в той же Wi‑Fi: `RAG_SERVICE_URL=http://<IP_вашего_ПК>:5001`.
5. **В настройках приложения** включите «Включить RAG» (и при желании «Использовать Reranker», порог релевантности).

После этого вопросы вроде «Почему не работает авторизация?» или «Опиши структуру CloudBuddy» будут опираться на документацию CloudBuddy. Команда `/help` принудительно использует RAG (даже если переключатель выключен).
