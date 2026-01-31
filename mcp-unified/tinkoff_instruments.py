#!/usr/bin/env python3
"""
Поиск бумаг (акции, облигации, ETF) по бюджету в рублях через Tinkoff Invest API.
Вывод: JSON с инструментами, которые можно купить на указанную сумму (цена, лот, кол-во лотов).
"""

import json
import os
import sys
from datetime import datetime, timezone

try:
    from tinkoff.invest import Client, InstrumentStatus
except Exception as e:
    print(json.dumps({"ok": False, "error": f"Python dependency error: {e}"}), flush=True)
    sys.exit(2)


def _now_iso():
    return datetime.now(timezone.utc).isoformat()


def _quotation_to_float(q):
    if q is None:
        return 0.0
    u = getattr(q, "units", 0) or 0
    n = getattr(q, "nano", 0) or 0
    return float(u) + float(n) / 1_000_000_000.0


def main():
    token = os.getenv("TINKOFF_INVEST_TOKEN", "").strip()
    if not token:
        print(json.dumps({"ok": False, "error": "TINKOFF_INVEST_TOKEN is not set"}), flush=True)
        sys.exit(1)

    # budget_rub [instrument_type] [limit]
    budget_rub = 0.0
    try:
        budget_rub = float(sys.argv[1]) if len(sys.argv) > 1 else 0.0
    except Exception:
        pass
    if budget_rub <= 0:
        print(json.dumps({"ok": False, "error": "Usage: tinkoff_instruments.py <budget_rub> [shares|bonds|etfs|all] [limit]"}), flush=True)
        sys.exit(1)

    instrument_type = (sys.argv[2] if len(sys.argv) > 2 else "all").lower()
    limit = 30
    try:
        limit = max(1, min(100, int(sys.argv[3]))) if len(sys.argv) > 3 else 30
    except Exception:
        pass

    allowed = {"shares", "bonds", "etfs", "all"}
    if instrument_type not in allowed:
        print(json.dumps({"ok": False, "error": f"instrument_type must be one of {list(allowed)}"}), flush=True)
        sys.exit(1)

    instruments_to_fetch = []
    if instrument_type in ("shares", "all"):
        instruments_to_fetch.append("shares")
    if instrument_type in ("bonds", "all"):
        instruments_to_fetch.append("bonds")
    if instrument_type in ("etfs", "all"):
        instruments_to_fetch.append("etfs")

    collected = []  # {figi, uid, ticker, name, type, lot, currency}
    price_by_figi = {}
    price_by_uid = {}
    batch_size = 100

    with Client(token) as client:
        for kind in instruments_to_fetch:
            meth = getattr(client.instruments, kind)
            req = InstrumentStatus.INSTRUMENT_STATUS_BASE
            resp = meth(instrument_status=req)
            items = getattr(resp, "instruments", []) or []
            cap = 400 if kind == "bonds" else 300
            for i, x in enumerate(items):
                if i >= cap:
                    break
                figi = getattr(x, "figi", None) or ""
                uid = getattr(x, "uid", None) or ""
                if not figi and not uid:
                    continue
                ticker = (getattr(x, "ticker", None) or "").strip()
                name = (getattr(x, "name", None) or "").strip()
                lot = int(getattr(x, "lot", 0) or 0)
                if lot <= 0:
                    continue
                cur = (getattr(x, "currency", None) or "").strip().upper()
                if cur != "RUB":
                    continue
                buy = getattr(x, "buy_available_flag", True)
                if not buy:
                    continue
                collected.append({
                    "figi": figi,
                    "uid": uid,
                    "ticker": ticker,
                    "name": name,
                    "type": kind,
                    "lot": lot,
                })

        if not collected:
            print(
                json.dumps(
                    {
                        "ok": True,
                        "budget_rub": budget_rub,
                        "instruments": [],
                        "message": "Нет подходящих инструментов в RUB с buy_available.",
                        "updated_at": _now_iso(),
                    }
                ),
                flush=True,
            )
            return

        figi_list = [x["figi"] for x in collected if x["figi"]]
        uid_list = [x["uid"] for x in collected if x["uid"] and not x["figi"]]

        for i in range(0, len(figi_list), batch_size):
            chunk = figi_list[i : i + batch_size]
            try:
                r = client.market_data.get_last_prices(figi=chunk)
            except Exception:
                continue
            for lp in getattr(r, "last_prices", []) or []:
                fid = getattr(lp, "figi", None) or getattr(lp, "instrument_uid", None)
                if fid:
                    p = getattr(lp, "price", None)
                    if p is not None:
                        price_by_figi[fid] = _quotation_to_float(p)
        for i in range(0, len(uid_list), batch_size):
            chunk = uid_list[i : i + batch_size]
            try:
                r = client.market_data.get_last_prices(instrument_id=chunk)
            except Exception:
                continue
            for lp in getattr(r, "last_prices", []) or []:
                uid = getattr(lp, "instrument_uid", None) or getattr(lp, "figi", None)
                if uid:
                    p = getattr(lp, "price", None)
                    if p is not None:
                        price_by_uid[uid] = _quotation_to_float(p)

    def get_price(item):
        if item["figi"] and item["figi"] in price_by_figi:
            return price_by_figi[item["figi"]]
        if item["uid"] and item["uid"] in price_by_uid:
            return price_by_uid[item["uid"]]
        return None

    results = []
    for item in collected:
        pr = get_price(item)
        if pr is None or pr <= 0:
            continue
        cost_per_lot = pr * item["lot"]
        if cost_per_lot <= 0:
            continue
        lots = int(budget_rub / cost_per_lot)
        if lots < 1:
            continue
        total = cost_per_lot * lots
        results.append({
            "ticker": item["ticker"],
            "name": item["name"],
            "type": item["type"],
            "price_rub": round(pr, 4),
            "lot": item["lot"],
            "lots_affordable": lots,
            "total_cost_rub": round(total, 2),
        })

    # Sort by total_cost_rub ascending to suggest diversification (smaller positions)
    results.sort(key=lambda r: (r["total_cost_rub"], r["ticker"]))
    results = results[: limit]

    print(
        json.dumps(
            {
                "ok": True,
                "budget_rub": budget_rub,
                "instrument_type": instrument_type,
                "instruments": results,
                "updated_at": _now_iso(),
                "source": "tinkoff-invest-api",
            },
            ensure_ascii=False,
        ),
        flush=True,
    )


if __name__ == "__main__":
    try:
        main()
    except Exception as e:
        print(json.dumps({"ok": False, "error": f"Unexpected error: {str(e)}"}), flush=True)
        sys.exit(1)
