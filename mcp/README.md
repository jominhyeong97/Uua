# Uua MCP Server

`POST /api/context`를 그대로 감싸는 얇은 stdio MCP 서버. 도구 1개만 노출:

- **`recall_context(query, project_key?, max_tokens?, top_k?)`** — Uua에서 의미가 가까운 메모리를 골라 토큰 예산 안의 컨텍스트 팩으로 돌려준다.

새 세션이 시작될 때 Claude/Gemini가 이 도구를 호출하면 "직전 작업 맥락"을 자동으로 받아간다 — 수동 handoff 문서의 종말이 v1의 dogfooding 목표.

## 설치 (Python 3.10+)

```powershell
# 윈도우 PowerShell 기준
cd "C:\Users\user\개인 프로젝트\Uua\mcp"
python -m venv .venv
.\.venv\Scripts\Activate.ps1
pip install -r requirements.txt
```

macOS/Linux:

```bash
cd ~/path/to/Uua/mcp
python3 -m venv .venv
source .venv/bin/activate
pip install -r requirements.txt
```

## 환경변수

| Var | Default | 의미 |
|---|---|---|
| `UUA_BASE_URL` | `https://uua.onrender.com` | 백엔드 루트 URL |
| `UUA_PROJECT_KEY` | `uua-render` | recall_context 호출 시 기본 projectKey (단계②~⑤ 라이브 검증 + dogfood 본 시작 모두 같은 키에 누적) |
| `UUA_HTTP_TIMEOUT` | `30` | HTTP 타임아웃(초) — Render 콜드스타트 60초 대비 여유 |

## 스모크 테스트 (MCP 프로토콜 없이)

`--test-call` 모드로 HTTP 경로만 검증할 수 있다. Claude Code에 등록하기 전에 먼저 통과시킬 것.

```powershell
.\.venv\Scripts\Activate.ps1
python uua_mcp.py --test-call "카프카 결정"
```

성공 시 `/api/context` 응답 JSON(pack/items/usedTokens/maxTokens)이 그대로 출력된다.

## Claude Code에 MCP 등록

Claude Code의 MCP 설정 파일에 아래 블록을 추가한다. 위치는 설치 환경마다 다르지만 대체로:

- Windows: `%APPDATA%\Claude\claude_desktop_config.json` (Claude Desktop)
- 또는 Claude Code CLI: `claude mcp add` 명령
- 또는 user-level config 직접 편집

### JSON 직접 편집 (Windows 예시)

```json
{
  "mcpServers": {
    "uua": {
      "command": "C:\\Users\\user\\개인 프로젝트\\Uua\\mcp\\.venv\\Scripts\\python.exe",
      "args": [
        "C:\\Users\\user\\개인 프로젝트\\Uua\\mcp\\uua_mcp.py"
      ],
      "env": {
        "UUA_PROJECT_KEY": "uua-render"
      }
    }
  }
}
```

> 경로의 백슬래시는 JSON에서 두 번(`\\`) 써야 한다. 한글 경로 그대로 OK.

저장 후 Claude Code를 **완전히 재시작**해야 MCP 서버 목록을 다시 읽는다.

### CLI 등록 (대안)

```powershell
claude mcp add uua --command "C:\Users\user\개인 프로젝트\Uua\mcp\.venv\Scripts\python.exe" --args "C:\Users\user\개인 프로젝트\Uua\mcp\uua_mcp.py"
```

## 사용 예 (Claude Code 안에서)

등록 후 새 세션에서 자연어로:

> "uua에서 어제 결정한 카프카 보상 트랜잭션 관련 메모리 가져와줘"

Claude가 자동으로 `recall_context` 도구를 호출해 컨텍스트 팩을 받아 답변에 반영한다.

## 트러블슈팅

- **`Connection refused` / 30초 타임아웃** — Render 무료티어 콜드스타트. 한 번 더 호출해 깨우거나 `UUA_HTTP_TIMEOUT=60`으로 늘림.
- **`503 embedding_unavailable cause=KILLED`** — 서버 측 킬스위치(`UUA_EMBEDDING_ENABLED=false`)가 켜져있음. Render env에서 확인.
- **`503 cause=DAILY_LIMIT`** — 오늘 1000회 한도 도달. `/api/usage/summary` 확인 후 자정(UTC) 지나면 리셋.
- **MCP 서버가 Claude Code에 안 보임** — config 파일 경로 + JSON 문법 + 재시작 여부 점검.

## 왜 Python인가 (왜 Java 아닌가)

- DESIGN.md SSOT: "기존 `/api/context` 로직 재사용, ~1일", "얇은 stdio 래퍼"
- 새 비즈니스 로직 0줄 — Java/Spring으로 짜는 건 과함
- MCP 공식 SDK는 Python이 가장 성숙
- 서버 본체(Spring Boot)와 분리해 dogfood 클라이언트는 가볍게 유지
