"""
Uua recall@K 평가셋 러너.

손으로 만든 golden.json을 읽어 /api/context를 호출하고,
"적어도 1개의 expected_source가 items에 들어왔는가"로 hit/miss를 집계한다.

사용:
    python runner.py                  # ./golden.json 사용
    python runner.py path/to/g.json   # 다른 경로 사용
    UUA_BASE_URL=http://localhost:8080 python runner.py   # 로컬 백엔드 대상
"""
from __future__ import annotations

import asyncio
import json
import os
import sys
import time
from typing import Any

import httpx

UUA_BASE_URL = os.getenv("UUA_BASE_URL", "https://uua.onrender.com")
HTTP_TIMEOUT_SECONDS = float(os.getenv("UUA_HTTP_TIMEOUT", "90"))


async def warmup(client: httpx.AsyncClient) -> None:
    """Render 콜드스타트 한 번 치르고 들어간다 — 첫 쿼리 타임아웃 방지."""
    try:
        await client.get(f"{UUA_BASE_URL}/actuator/health", timeout=HTTP_TIMEOUT_SECONDS)
    except Exception as e:  # noqa: BLE001
        print(f"warmup warning: {e}", file=sys.stderr)


async def query_context(
    client: httpx.AsyncClient,
    query: str,
    project_key: str,
    top_k: int,
    max_tokens: int,
) -> dict[str, Any]:
    resp = await client.post(
        f"{UUA_BASE_URL}/api/context",
        json={
            "query": query,
            "projectKey": project_key,
            "topK": top_k,
            "maxTokens": max_tokens,
        },
        timeout=HTTP_TIMEOUT_SECONDS,
    )
    resp.raise_for_status()
    return resp.json()


def truncate(s: str, n: int) -> str:
    return s if len(s) <= n else s[: n - 1] + "…"


async def main() -> None:
    golden_path = sys.argv[1] if len(sys.argv) > 1 else "golden.json"
    with open(golden_path, encoding="utf-8") as f:
        golden = json.load(f)

    top_k: int = int(golden.get("default_top_k", 20))
    max_tokens: int = int(golden.get("default_max_tokens", 1000))
    pairs: list[dict[str, Any]] = golden["pairs"]

    print(f"Eval @ K={top_k}, max_tokens={max_tokens}, base={UUA_BASE_URL}")
    print(f"Loaded {len(pairs)} golden pairs from {golden_path}")
    print("Warming up server (Render cold-start may take 30-60s)...")

    async with httpx.AsyncClient() as client:
        await warmup(client)
        print()

        results = []
        hits = 0
        sep = "─" * 75
        print(sep)
        for pair in pairs:
            t0 = time.time()
            try:
                resp = await query_context(
                    client, pair["query"], pair["project_key"], top_k, max_tokens
                )
            except httpx.HTTPError as e:
                print(f"ERROR \"{truncate(pair['query'], 32)}\" → {e}")
                results.append({
                    "query": pair["query"],
                    "error": str(e),
                    "hit": False,
                })
                continue
            elapsed_ms = int((time.time() - t0) * 1000)

            returned_sources = [item["source"] for item in resp["items"]]
            expected = set(pair["expected_sources"])
            hit_set = expected & set(returned_sources)
            is_hit = len(hit_set) > 0

            if is_hit:
                hits += 1
                first_hit = next(item for item in resp["items"] if item["source"] in expected)
                detail = f"hit {first_hit['source']} (score {first_hit['score']:.2f})"
                status = "PASS"
            else:
                top1 = resp["items"][0]["source"] if resp["items"] else "(empty)"
                detail = f"miss — top1 was {top1}"
                status = "FAIL"

            print(f"{status}  \"{truncate(pair['query'], 32)}\" → {detail} [{elapsed_ms}ms]")

            results.append({
                "query": pair["query"],
                "expected": sorted(expected),
                "returned": returned_sources,
                "hit": is_hit,
                "latency_ms": elapsed_ms,
            })

        print(sep)
        total = len(pairs)
        recall_pct = hits / total * 100 if total else 0
        print(f"recall@{top_k}: {hits}/{total} = {recall_pct:.1f}%")

        timestamp = time.strftime("%Y%m%dT%H%M%S")
        report = {
            "timestamp": timestamp,
            "config": {
                "top_k": top_k,
                "max_tokens": max_tokens,
                "base_url": UUA_BASE_URL,
            },
            "summary": {"hits": hits, "total": total, "recall_pct": recall_pct},
            "results": results,
        }
        out_path = f"results-{timestamp}.json"
        with open(out_path, "w", encoding="utf-8") as f:
            json.dump(report, f, ensure_ascii=False, indent=2)
        print(f"Report saved: {out_path}")


if __name__ == "__main__":
    asyncio.run(main())
