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

        # 1) Пробуем find_instrument по каждому тикеру
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

        # 2) Если не нашли — пробуем get_instrument_by (тикер + class_code CETS для валют MOEX)
        if inst is None:
            try:
                from tinkoff.invest import InstrumentIdType, InstrumentRequest
                req = InstrumentRequest(
                    id_type=InstrumentIdType.INSTRUMENT_ID_TYPE_TICKER,
                    id="USD000UTSTOM",
                    class_code=CLASS_CODE_CETS,
                )
                resp = client.instruments.get_instrument_by(req)
                if resp and getattr(resp, "instrument", None):
                    inst = resp.instrument
                    used_ticker = getattr(inst, "ticker", "USD000UTSTOM")
            except Exception:
                pass

        # 3) Перебор валют из списка (currencies)
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

