"""
Uua MCP server — stdio 래퍼.

단일 도구 `recall_context`만 노출한다. 새 LLM 세션이 시작될 때 이 도구를 호출하면
의미검색 + 최신성 부스트 + 토큰버짓 그리디로 조립된 컨텍스트 팩이 돌아온다.

내부는 HTTP — `POST {UUA_BASE_URL}/api/context`. 새 비즈니스 로직 0줄(DESIGN.md).
"""
from __future__ import annotations

import json
import os
import sys
from typing import Any

import httpx
from mcp.server.fastmcp import FastMCP

UUA_BASE_URL = os.getenv("UUA_BASE_URL", "https://uua.onrender.com")
UUA_PROJECT_KEY_DEFAULT = os.getenv("UUA_PROJECT_KEY", "uua")
HTTP_TIMEOUT_SECONDS = float(os.getenv("UUA_HTTP_TIMEOUT", "30"))

mcp = FastMCP("uua")


async def _call_context_api(
    query: str,
    project_key: str,
    max_tokens: int,
    top_k: int,
) -> dict[str, Any]:
    """HTTP 호출만 떼어둔 헬퍼 — 도구와 --test-call 양쪽에서 재사용."""
    async with httpx.AsyncClient(timeout=HTTP_TIMEOUT_SECONDS) as client:
        resp = await client.post(
            f"{UUA_BASE_URL}/api/context",
            json={
                "query": query,
                "projectKey": project_key,
                "maxTokens": max_tokens,
                "topK": top_k,
            },
        )
        resp.raise_for_status()
        return resp.json()


@mcp.tool()
async def recall_context(
    query: str,
    project_key: str | None = None,
    max_tokens: int = 1000,
    top_k: int = 20,
) -> dict[str, Any]:
    """
    Search Uua memory engine and return a context pack assembled within a token budget.

    Use this at the start of a new session to recall what was decided/learned/discussed previously,
    so the new session has the context the old session ended with.

    Args:
        query: The question, topic, or task description to retrieve relevant memories for.
        project_key: Project namespace to search within. Defaults to env UUA_PROJECT_KEY or "uua".
        max_tokens: Maximum tokens in the returned context pack (1..8000, default 1000).
        top_k: Number of candidate memories to consider before scoring (1..100, default 20).

    Returns:
        JSON object with:
          - pack: human-readable context string (numbered citations)
          - items: list of source memories with text/source/createdAt/score
          - usedTokens: total tokens included in pack
          - maxTokens: the budget echoed back
    """
    pk = project_key or UUA_PROJECT_KEY_DEFAULT
    return await _call_context_api(query, pk, max_tokens, top_k)


def main() -> None:
    # --test-call: HTTP 경로만 검증 (MCP 프로토콜 없이) — Claude Code 등록 전 스모크용.
    if "--test-call" in sys.argv:
        import asyncio
        query = "smoke test query"
        try:
            idx = sys.argv.index("--test-call")
            if idx + 1 < len(sys.argv):
                query = sys.argv[idx + 1]
        except ValueError:
            pass
        result = asyncio.run(_call_context_api(query, UUA_PROJECT_KEY_DEFAULT, 1000, 20))
        print(json.dumps(result, ensure_ascii=False, indent=2))
        return

    # 기본 동작: stdio 트랜스포트로 MCP 서버 기동.
    mcp.run()


if __name__ == "__main__":
    main()
