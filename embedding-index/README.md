# Локальный индекс документов с эмбеддингами (AI Companion)

Пайплайн для тестовых профильных документов проекта AI Companion:

1. **Разбивка на чанки** — по параграфам (для .md — с учётом заголовков), с ограничением длины и перекрытием. Поддерживаются .md и .pdf (текст из PDF извлекается через PyMuPDF).
2. **Генерация эмбеддингов** — локально через `sentence-transformers` (модель `paraphrase-multilingual-mpnet-base-v2`, без API).
3. **Сохранение индекса** — JSON (полный дамп чанков + векторы) и FAISS (быстрый поиск по косинусной близости).

## Входные документы

По умолчанию для теста индексируется один файл из корня репозитория:

- `Test_sample.pdf`

Поддерживаются также `.md` файлы. Список задаётся в `build_index.py` (`DEFAULT_DOCS`); можно вернуть три .md профиля или комбинировать PDF и .md.

## Установка и запуск

```bash
cd embedding-index
python -m venv .venv
.venv\Scripts\activate   # Windows
# source .venv/bin/activate  # Linux/macOS
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
