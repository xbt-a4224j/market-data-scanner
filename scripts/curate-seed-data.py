#!/usr/bin/env python3
"""
Curate seed token CSVs for Issue #20.

LEGIT side: pull CoinGecko top-N tokens by market cap, intersect with
ethereum-platform contracts, verify each via Etherscan getContractCreation,
output to known-legit-tokens.csv.

SCAM side: pull MyEtherWallet darklist (community-curated address blacklist),
filter via Etherscan to those that are actual contract deployments, output
to known-scam-tokens.csv.

Etherscan V2 API; rate limit 5 calls/sec on free tier. We batch 5
addresses per getContractCreation call to be efficient.
"""

import csv
import datetime as dt
import json
import os
import sys
import time
from pathlib import Path
from urllib.request import urlopen, Request

REPO = Path(__file__).resolve().parent.parent / "Users/daj/dev/git/market-data-scanner"
if not REPO.exists():
    REPO = Path("/Users/daj/dev/git/market-data-scanner")
ETHERSCAN_KEY = os.environ.get("ETHERSCAN_API_KEY")
if not ETHERSCAN_KEY:
    sys.exit("ETHERSCAN_API_KEY not set in env")

ETHERSCAN_RATE_LIMIT_QPS = 5
SLEEP_BETWEEN_CALLS = 1.0 / ETHERSCAN_RATE_LIMIT_QPS


def fetch_json(url, timeout=30):
    req = Request(url, headers={"User-Agent": "scanner-seed-curator/1.0"})
    with urlopen(req, timeout=timeout) as r:
        return json.loads(r.read().decode("utf-8"))


def etherscan_creation(addresses):
    """Returns list of {contractAddress, contractCreator, txHash, blockNumber, timestamp}."""
    addrs_csv = ",".join(addresses)
    url = (
        f"https://api.etherscan.io/v2/api?chainid=1"
        f"&module=contract&action=getcontractcreation"
        f"&contractaddresses={addrs_csv}"
        f"&apikey={ETHERSCAN_KEY}"
    )
    try:
        d = fetch_json(url)
    except Exception as e:
        print(f"  etherscan call failed: {e}", file=sys.stderr)
        return []
    if d.get("status") != "1":
        return []
    return d.get("result") or []


def categorize(symbol, name):
    sym = (symbol or "").lower()
    nm = (name or "").lower()
    stable_syms = {"usdc", "usdt", "dai", "fdusd", "tusd", "frax", "usde",
                   "lusd", "gusd", "pyusd", "usdp", "usdd", "usds"}
    if sym in stable_syms or "usd" in sym and len(sym) <= 5:
        return "stablecoin"
    if sym in {"wbtc", "tbtc", "weth", "steth", "wsteth", "reth", "cbeth", "ankreth"}:
        return "wrapped_or_lst"
    meme_syms = {"shib", "pepe", "doge", "floki", "mog", "wojak", "wif",
                 "bonk", "neiro", "popcat"}
    if sym in meme_syms:
        return "meme_established"
    governance_syms = {"uni", "mkr", "aave", "comp", "ldo", "ena", "ondo",
                       "dydx", "crv", "snx", "bal", "yfi", "1inch", "pendle"}
    if sym in governance_syms:
        return "governance"
    return "bluechip_defi"


def curate_legit(top_n=250):
    """Top-N by market cap, intersected with eth-platform contract addresses."""
    print(f"=== LEGIT: fetching CoinGecko top-{top_n} by market cap ===")
    markets_url = (
        f"https://api.coingecko.com/api/v3/coins/markets"
        f"?vs_currency=usd&order=market_cap_desc"
        f"&per_page={min(top_n, 250)}&page=1&sparkline=false"
    )
    markets = fetch_json(markets_url)
    print(f"  got {len(markets)} top-cap entries")

    print("  fetching coingecko platforms list...")
    platforms_url = "https://api.coingecko.com/api/v3/coins/list?include_platform=true"
    platforms = fetch_json(platforms_url)
    by_id = {c["id"]: c.get("platforms", {}) for c in platforms}

    # Intersect: top markets that have an ethereum contract
    candidates = []
    for m in markets:
        eth_addr = by_id.get(m["id"], {}).get("ethereum")
        if eth_addr and eth_addr.startswith("0x") and len(eth_addr) == 42:
            candidates.append({
                "address": eth_addr.lower(),
                "symbol": m["symbol"].upper(),
                "name": m["name"],
                "rank": m.get("market_cap_rank") or 0,
                "mcap": m.get("market_cap") or 0,
            })
    print(f"  {len(candidates)} candidates have an ethereum contract address")

    # Verify each via Etherscan in batches of 5
    verified = []
    for i in range(0, len(candidates), 5):
        batch = candidates[i:i+5]
        addrs = [c["address"] for c in batch]
        results = etherscan_creation(addrs)
        result_by_addr = {r["contractAddress"].lower(): r for r in results}
        for c in batch:
            r = result_by_addr.get(c["address"])
            if r:
                ts = int(r["timestamp"])
                c["deployer"] = r["contractCreator"].lower()
                c["block"] = int(r["blockNumber"])
                c["ts_iso"] = dt.datetime.fromtimestamp(ts, dt.UTC).strftime("%Y-%m-%dT%H:%M:%SZ")
                c["category"] = categorize(c["symbol"], c["name"])
                verified.append(c)
        time.sleep(SLEEP_BETWEEN_CALLS)
        if i % 25 == 0:
            print(f"  verified {len(verified)}/{len(candidates)}...")

    print(f"  verified total: {len(verified)}")
    return verified


def fetch_darklist():
    """MyEtherWallet community darklist."""
    url = "https://raw.githubusercontent.com/MyEtherWallet/ethereum-lists/master/src/addresses/addresses-darklist.json"
    return fetch_json(url)


def curate_scam():
    print("=== SCAM: fetching MyEtherWallet darklist ===")
    entries = fetch_darklist()
    print(f"  raw entries: {len(entries)}")

    # Verify each via Etherscan in batches of 5; only contract addresses survive
    verified = []
    by_addr = {e["address"].lower(): e for e in entries}
    addresses = list(by_addr.keys())
    for i in range(0, len(addresses), 5):
        batch = addresses[i:i+5]
        results = etherscan_creation(batch)
        for r in results:
            addr = r["contractAddress"].lower()
            src_entry = by_addr.get(addr)
            if not src_entry:
                continue
            ts = int(r["timestamp"])
            comment = src_entry.get("comment", "").strip()
            scam_type = classify_scam(comment)
            verified.append({
                "address": addr,
                "deployer": r["contractCreator"].lower(),
                "block": int(r["blockNumber"]),
                "ts_iso": dt.datetime.fromtimestamp(ts, dt.UTC).strftime("%Y-%m-%dT%H:%M:%SZ"),
                "scam_type": scam_type,
                "comment": comment,
                "first_reported": src_entry.get("date", ""),
            })
        time.sleep(SLEEP_BETWEEN_CALLS)
        if i % 50 == 0:
            print(f"  scanned {i}/{len(addresses)}; verified {len(verified)} contracts so far...")

    print(f"  verified total: {len(verified)} contract entries")
    return verified


def classify_scam(comment):
    c = comment.lower()
    if "fake" in c and ("token" in c or "coin" in c):
        return "impersonation"
    if "rugpull" in c or "rug pull" in c or "rug-pull" in c:
        return "rug_pull"
    if "honeypot" in c:
        return "honeypot"
    if "ponzi" in c or "pyramid" in c:
        return "ponzi"
    if "phishing" in c or "fake" in c and "site" in c:
        return "phishing_factory"
    if "hack" in c or "exploit" in c or "stole" in c:
        return "exploit"
    if "scam" in c:
        return "other_scam"
    return "other"


def write_legit_csv(rows, out_path):
    out_path.parent.mkdir(parents=True, exist_ok=True)
    with out_path.open("w", newline="") as f:
        w = csv.writer(f)
        w.writerow([
            "token_address", "symbol", "name", "deployer_address",
            "deployment_block", "deployment_ts", "category",
            "market_cap_rank", "source", "source_url", "notes",
        ])
        for r in rows:
            w.writerow([
                r["address"], r["symbol"], r["name"], r["deployer"],
                r["block"], r["ts_iso"], r["category"], r["rank"],
                "coingecko_top_250 + etherscan_v2",
                f"https://etherscan.io/token/{r['address']}",
                f"market_cap_rank={r['rank']}",
            ])
    print(f"  wrote {len(rows)} rows to {out_path}")


def write_scam_csv(rows, out_path):
    out_path.parent.mkdir(parents=True, exist_ok=True)
    with out_path.open("w", newline="") as f:
        w = csv.writer(f)
        w.writerow([
            "token_address", "deployer_address", "deployment_block",
            "deployment_ts", "scam_type", "first_reported",
            "source", "source_url", "notes",
        ])
        for r in rows:
            w.writerow([
                r["address"], r["deployer"], r["block"], r["ts_iso"],
                r["scam_type"], r["first_reported"],
                "myetherwallet_ethereum_lists_darklist + etherscan_v2",
                "https://github.com/MyEtherWallet/ethereum-lists/blob/master/src/addresses/addresses-darklist.json",
                r["comment"][:200],
            ])
    print(f"  wrote {len(rows)} rows to {out_path}")


def main():
    legit = curate_legit(top_n=250)
    write_legit_csv(legit, REPO / "src/test/resources/eval/known-legit-tokens.csv")

    scam = curate_scam()
    write_scam_csv(scam, REPO / "src/test/resources/eval/known-scam-tokens.csv")

    print()
    print("==== summary ====")
    print(f"legit: {len(legit)} entries")
    print(f"scam:  {len(scam)} entries")


if __name__ == "__main__":
    main()
