# LCP-Harness v1 — Dokumen Desain Formal

**Status:** v1.2 — IMPLEMENTED P0–P4 (lihat Lampiran B untuk peta kelas aktual)
**Tanggal:** 9 Agustus 2026 | Diperbarui: 23 Agustus 2026
**Penulis:** LineCode Pro architecture group
**Cakupan:** Desain orkestrasi agent di atas sistem AI/tool LineCode Pro yang sudah ada. Dokumen ini adalah *design document*, bukan perubahan kode.

---

## Ringkasan Eksekutif

LCP-Harness adalah **control architecture** di atas sistem AI/agent LineCode Pro yang mengatur: lifecycle agent, task state, context, memory, tool execution, permission, evidence, verification, recovery, sub-agent, resource budget, dan persistence.

Harness **bukan** model baru, **bukan** memory database baru, dan **bukan** pengganti `ToolRegistry`, `ModelClient`, `ModelProtocol`, atau repository yang sudah ada. Tujuan utamanya adalah menjadikan komponen agent yang saat ini tersebar menjadi satu **lifecycle eksplisit yang dapat dikontrol**, dengan prinsip:

> **Model decides. Harness controls. Tools act. Environment observes. Verifier validates. Memory persists.**

**Keputusan desain utama v1 (hasil review kode):**

| ID | Keputusan | Bagian |
|----|-----------|--------|
| D01 | Task Controller **membungkus** loop generation yang ada (`GenerationFlowController`), bukan membuat loop paralel kedua | §6, §30 |
| D02 | Skema `agent_tasks` diperluas: `execution_profile`, `verification_policy`, `budget_json`, `verdict`, `attempt_count` | §35 |
| D03 | Task Capsule adalah *guidance* system-level (P0-region), **bukan** prioritas di atas pesan user terbaru (anti goal-drift) | §8 |
| D04 | Reconciliation task menggantung (INTERRUPTED) saat app start; harness wajib crash-safe | §30.4 |
| D05 | `agent_events` hanya untuk event lifecycle task-level (bukan tool-level); `agent_evidence` menyimpan ringkasan + referensi, bukan salinan penuh | §35.2 |
| D06 | Mode = policy profile dengan **4** mode: CHAT / PLAN / AGENT / CONTROL (Control tidak dilupakan) | §26 |
| D07 | Lifecycle memory v1 = gate confidence yang sudah ada (`MIN_KEEP_CONFIDENCE` / `RULE_CONFIDENCE`); lifecycle penuh CANDIDATE→VERIFIED adalah v2 | §19 |
| D08 | `Decision` adalah enum internal yang **diturunkan** dari output model yang sudah diparsing, bukan kontrak baru yang dipaksakan ke model | §10 |
| D09 | Task state wajib thread-safe (pola `PipelineProgressSession` / `AgentResultRegistry`) | §30.3 |
| D10 | Harness opsional & backward-compatible: conversation tanpa task berperilaku persis seperti hari ini | §30.2 |

---

## 1. Tujuan & Non-Tujuan

### Tujuan
1. Memberikan lifecycle eksplisit (CREATE → PLAN → EXECUTE → VERIFY → COMPLETE) untuk pekerjaan agent.
2. Membedakan **conversation** (history komunikasi) dari **task** (pekerjaan yang sedang dilakukan).
3. Menjadikan **evidence & verification** sebagai bagian dari loop, bukan klaim model belaka.
4. Memberikan **budget** (token, waktu, tool call, sub-agent) agar tidak ada infinite execution.
5. Mengontrol semua side-effect melalui **policy gate** terpusat.
6. Memetakan memory/learning/skill/sub-agent ke sistem yang **sudah ada**, tanpa mekanisme baru.

### Non-Tujuan (v1)
- ❌ Bukan LLM abstraction baru — `ModelClient` / `ModelProtocol` tetap satu-satunya jalan ke model.
- ❌ Bukan tool framework baru — `BaseTool` / `ToolRegistry` / `ToolExecutor` tetap.
- ❌ Bukan database engine / vector DB baru — raw SQLite + FTS yang ada tetap.
- ❌ Bukan filesystem watcher / daemon indexing / autonomous planning daemon.
- ❌ Bukan pengganti compaction: `ContextManager` + `ContextCompactionService` (soft 50% / hard 80%, tail retention, retry) tetap; harness hanya *orchestrator*-nya.
- ❌ Bukan keharusan: conversation tanpa task berjalan seperti hari ini (D10).

---

## 2. Model Mental

```
                    USER
                      |
                      v
                 INTENT/TASK
                      |
                      v
              +---------------+
              | TASK CONTROLLER|
              +---------------+
                      |
          +-----------+-----------+
          |                       |
          v                       v
   CONTEXT ENGINE          STATE ENGINE
          |                       |
          +-----------+-----------+
                      |
                      v
                 MODEL CLIENT
                      |
                      v
                DECISION/PLAN
                      |
                      v
                 POLICY GATE
                      |
                      v
                TOOL EXECUTOR
                      |
                      v
                 OBSERVATION
                      |
          +-----------+-----------+
          |                       |
          v                       v
      EVIDENCE                STATE UPDATE
          |                       |
          v                       |
      VERIFIER                   |
          |                       |
          +-----------+-----------+
                      |
                      v
                MEMORY LEARNING
                      |
                      v
              COMPLETE / CONTINUE
```

---

## 3. Komponen Harness (10 Konseptual)

1. **Task Controller** — pemilik lifecycle task; pembungkus loop generation (§6, §30).
2. **State Engine** — 5 lapis state: EPHEMERAL / SESSION / TASK / PROJECT / PERSISTENT (§7).
3. **Context Engine** — SELECT / PRUNE / COMPACT atas apa yang dikirim ke model (§8).
4. **Decision Controller** — boundary model ↔ dunia luar; menurunkan Decision dari output model (§10).
5. **Policy Gate** — pemeriksaan sebelum semua side effect (§11).
6. **Tool Runtime** — normalisasi output tool menjadi `ToolObservation` (§12).
7. **Evidence/Verification Engine** — CLAIM vs OBSERVATION vs EVIDENCE vs VERDICT (§14–§17).
8. **Memory & Learning Engine** — lifecycle memory/learning/skill yang evidence-backed (§19–§22).
9. **Recovery Engine** — klasifikasi failure + recovery berbudget (§25).
10. **Resource Governor** — budget token/waktu/tool/sub-agent/paralel/output (§26).

**Posisi dalam modul Gradle (v1):** sebagian besar komponen tidak memerlukan modul Gradle baru. Peletakan mengikuti aturan modul yang berlaku:

| Komponen | Lokasi v1 |
|----------|-----------|
| Enum/DTO task (`TaskStatus`, `TaskMode`, `Verdict`, `EvidenceLevel`, `ExecutionProfile`, `TaskCapsule`) | `:core-model` |
| Interface kontrak (mis. `TaskStore`) | `:core-api` |
| Logika murni pure-Java (`TaskStateMachine`, `VerdictEngine`, `TaskBudget`, `EvidenceClassifier`) | `:feature-model` (sejajar `RetryPolicy` / `ContextManager`, unit-testable tanpa Android) |
| Persistence (`AgentTaskRepository`) | `:data` (pola repository yang ada) |
| Orkestrasi (`TaskController`) | `:app` `cn.lineai.mvp.*` (sejajar controller lain) |
| Wiring | `MainDependencies` / `MainControllerInitializer` |

> **Aturan kunci:** tidak ada modul baru, tidak ada framework baru, tidak ada dependency injection framework. Harness mengikuti Java 11 + manual DI + raw SQLite + `ExecutorService` yang sudah dipakai project.

---

## 4. Task Controller & Task Capsule

### 4.1 Task vs Conversation

- **Conversation** = history komunikasi (`conversations` / `messages`).
- **Task** = unit pekerjaan yang sedang dilakukan; satu conversation dapat memiliki banyak task (sekuensial di v1).

```
Conversation
 ├── Task: fix build error
 ├── Task: refactor LoginController
 └── Task: investigate crash
```

### 4.2 Field minimal Task

`task_id`, `conversation_id`, `project_id`, `parent_task_id`, `mode`, `goal`, `scope`, `constraints`, `status`, `execution_profile`, `verification_policy`, `budget_json`, `completion_condition`, `failure_condition`, `risk_level`, `attempt_count`, `created_at`, `updated_at`, `completed_at`.

Status: `CREATED`, `PLANNING`, `EXECUTING`, `VERIFYING`, `BLOCKED`, `WAITING_USER`, `COMPLETED`, `FAILED`, `CANCELLED` (+ `INTERRUPTED` khusus reconciliation crash, D04).

### 4.3 Task Capsule (D03)

```
Task Capsule
goal:          Fix NullPointerException in LoginController
scope:         app/src/main/java/cn/lineai/login/
constraints:   - Java 11
               - no new dependency
               - preserve existing architecture
completion:    - compile succeeds
               - relevant test succeeds
               - diff contains intended fix
risk:          LOW
```

**Koreksi review (D03):** Capsule adalah *guidance* dengan prioritas tinggi — diinjeksi bersama konten system/policy (P0-region, lewat `SystemPromptProvider`), **bukan** prioritas di atas pesan user terbaru. Lihat §8 untuk urutan prioritas final.

**Aturan anti goal-drift:** jika pesan user terbaru mengubah topik secara material dari goal task aktif, task aktif di-**SUSPEND/CANCEL** (dan task baru boleh dibuat). Capsule yang stale tidak boleh mencemari context.

---

## 5. State Engine

Lima lapis state, dipetakan ke storage yang **sudah ada**:

| Lapis | Rentang hidup | Contoh | Penyimpanan v1 |
|-------|---------------|--------|----------------|
| EPHEMERAL | 1 siklus eksekusi | `current_tool_call`, `current_model_response`, `current_tool_output` | In-memory (field loop) |
| SESSION | 1 sesi agent | `selected_model`, `permission_mode`, `active_workspace`, `current_agent_mode` | Field controller yang ada (mis. `MainCoordinator`) |
| TASK | 1 pekerjaan | `plan`, `current_step`, `files_touched`, `errors`, `verification_state` | Baris `agent_tasks` + in-memory `TaskSession` |
| PROJECT | lintas conversation, 1 project | struktur project, build system, known commands, conventions | `projects` + snapshot on-demand (§32) |
| PERSISTENT | jangka panjang | user preference, verified convention, learned workflow, failure pattern | `memories` / `skills` |

**Prinsip:** harness **tidak** mencampur semua lapis menjadi satu "memory". `memories`, `working_memory`, `skills`, conversation index, dan project state tetap terpisah — sesuai yang sudah ada.

---

## 6. Context Engine

Mengontrol apa yang dikirim ke model. Tiga operasi: **SELECT / PRUNE / COMPACT**.

### 6.1 Prioritas context (final, D03)

| Level | Isi |
|-------|-----|
| P0 | system/policy + **active task capsule** (guidance) |
| P1 | **current user request** (pesan user terbaru — tidak boleh digeser capsule) |
| P2 | current tool result |
| P3 | relevant code/context |
| P4 | verification evidence |
| P5 | relevant working memory |
| P6 | relevant persistent memory |
| P7 | historical conversation |
| P8 | low-priority metadata |

### 6.2 Compaction: reuse penuh

Harness **tidak** mengganti algoritma compaction yang sudah ada:

- `ContextManager` (estimasi: `CHARS_PER_TOKEN=4`, `MESSAGE_OVERHEAD_TOKENS=8`, `DEFAULT_RESERVE_TOKENS=2048`) + `TokenUsageTracker` (observed usage).
- Soft compaction 50% (`SOFT_COMPACT_TRIGGER_RATIO`), hard compaction 80%, tail retention `COMPACT_USER_MESSAGE_MAX_TOKENS=20000`, transcript segmented ≤ `TRANSCRIPT_SEGMENT_MAX_CHARS=256KB`, retry `MAX_COMPACT_RETRIES`.

Harness cukup menjadi **orchestrator**: memicu compaction saat event task tertentu terjadi (mis. sebelum PLAN pada task besar), bukan mengganti implementasinya.

---

## 7. Context Freshness

Setiap context item dapat memiliki: `source`, `scope`, `created_at`, `updated_at`, `freshness`, `priority`, `estimated_tokens`.

- Kode tidak diasumsikan selalu fresh: `CACHE HIT` / `CACHE STALE` / `REFRESH REQUIRED`.
- **Tidak ada filesystem watcher.** Project/code map memakai **ON-DEMAND REFRESH** (§32).
- Trigger resmi: task start; setelah agent menulis/mengedit file; sebelum operasi yang butuh struktur project terbaru; setelah build/test memberi info struktural; user eksplisit minta refresh.

---

## 8. Decision Controller (D08)

Boundary antara model dan dunia luar. **Koreksi review:** model tidak dipaksa mengeluarkan struktur "Decision" baru yang harus dipatuhi 12+ provider/protocol. Sebaliknya:

```
Model output (teks + tool call yang sudah diparsing ToolCallTextParser)
        ↓
Decision = enum internal yang DITURUNKAN
        ↓
Policy Gate → Tool Resolution → Execution
```

Decision dapat berupa: `ANSWER`, `TOOL_CALL`, `PLAN`, `DELEGATE`, `ASK_USER`, `STOP`. Turunan sederhana: ada tool call terparsing → `TOOL_CALL`; teks tanpa tool → `ANSWER`; pola "rencana multi-langkah" → `PLAN` (v1 opsional); dst. Tidak ada perubahan format output model.

---

## 9. Policy Gate

Selalu berada sebelum side effect.

```
Model Decision → Policy Gate → ALLOW / BLOCK → Execute
```

Kategori (dipetakan ke yang sudah ada):

| Kategori | Contoh tool | Implementasi existing |
|----------|-------------|----------------------|
| READ_ONLY | `FileReadTool`, `GlobTool`, `ListDirectoryTool`, `WebSearchTool`, `git_status/git_diff/git_log` | tanpa konfirmasi; boleh di readonly mode |
| MUTATING | `FileWriteTool`, `FileEditTool`, `git_commit` | konfirmasi user (`ToolReviewListener`) |
| DESTRUCTIVE | `FileDeleteTool` | konfirmasi + gating khusus |
| EXTERNAL | `git_push`, `WebFetchTool` | konfirmasi; `UrlPolicy` |
| PRIVILEGED | PhoneControl | `PhoneControlService` / accessibility |

**Implementation point utama:** `PermissionModeController`, `ToolReviewListener`, `sessionAutoConfirmedTools` (session-scoped auto-confirm), `ToolPermissionService` / `ToolSettingsStore` (readonly-mode block), `ChatModeRepository.applyPermissionForMode()`. `GitTool` yang sudah ada sudah mencontohkan pola read-only vs write (confirm).

**Invariant:** agent tidak boleh melewati konfirmasi hanya karena model memiliki confidence tinggi (§42).

---

## 10. Tool Runtime

Harness **tidak** mengganti `BaseTool`. Rantai yang dipakai:

```
ToolRegistry → ToolExecutor → ToolExecutionCoordinator → ToolExecutionScheduler (4-thread pool)
```

Setiap tool harus menghasilkan **`ToolObservation`**:

```json
{
  "tool_name": "...",
  "success": true,
  "output": "...",
  "error": null,
  "duration_ms": 1234,
  "side_effect": "WRITE|READ|EXTERNAL|NONE",
  "artifacts": {}
}
```

Output tool **tidak otomatis masuk seluruhnya ke context**:

```
raw result → normalize → truncate/prune → observation → context
```

Ini konsisten dengan limit `ToolResult` 50 KB yang sudah ada (`ToolResult.truncateContent()`: ≤50KB utuh; >50KB → 25KB awal + notice + 25KB akhir; file >1MB ditolak `FileReadTool`). `ToolArgsCleaner` tetap dipakai sebelum parsing argumen.

---

## 11. Execution Profiles

Harness tidak boleh menganggap filesystem lokal sebagai satu-satunya environment. Setiap task memiliki **ExecutionProfile**: `LOCAL`, `SSH`, `IPC_TERMINAL`, `PHONE`.

| Aspek | LOCAL | SSH | IPC_TERMINAL | PHONE |
|-------|-------|-----|--------------|-------|
| Filesystem | `WorkspacePaths` / `SafPathResolver` | `SshFileTreeRepository` | Termux dir | n/a |
| shell | tidak ada (kecuali via provider) | `SshCommandExecutor` | `TermuxHelper` / IPC | n/a |
| Network | `UrlPolicy` | `UrlPolicy` | `UrlPolicy` | n/a |
| Tools | subset file/read | penuh + shell | penuh + shell | phone-only |
| Verifikasi build/test | ❌ tidak tersedia | ✅ | ⚠️ tergantung toolchain | ❌ |

Profile menentukan: available tools, filesystem boundary, network access, permission level, timeout, resource budget, dan — **koreksi review kunci** — **kemampuan verifikasi** (§15).

---

## 12. Evidence Engine

Setiap task membedakan: **CLAIM / OBSERVATION / EVIDENCE / VERDICT**.

```
CLAIM:      "Bug sudah diperbaiki."
OBSERVATION: File changed.
EVIDENCE:    Gradle build SUCCESS.  → E2
EVIDENCE:    Relevant test PASS.     → E3
EVIDENCE:    Diff matches scope.     → E2
VERDICT:     VERIFIED

CLAIM:      "Bug sudah diperbaiki."
EVIDENCE:    Agent mengatakan demikian.  → E0
VERDICT:     UNVERIFIED
```

**Invariant penting:** confidence tidak boleh naik hanya karena E0 diulang.

### 12.1 Evidence Levels

| Level | Definisi | Contoh |
|-------|----------|--------|
| E0 | model assertion | "kode terlihat benar" |
| E1 | tool observation | FileWrite berhasil |
| E2 | deterministic check | javac/Gradle sukses; diff scope check |
| E3 | independent verification | test sukses |
| E4 | external/user confirmation | user mengonfirmasi behaviour |

---

## 13. Coding Verification Pipeline (D01 — profile-dependent)

```
EDIT → DIFF → STATIC CHECK → BUILD → TEST → RUNTIME CHECK (jika tersedia) → VERDICT
```

**Koreksi review kunci:** pipeline ini hanya dapat dijalankan penuh di profile yang punya toolchain (SSH / IPC dengan toolchain). Di LOCAL murni tidak ada Gradle/compiler. Maka:

**Verification policy task** (`NONE | LIGHT | BUILD | BUILD_AND_TEST | FULL`) **dipetakan ke kemampuan profile**:

| Policy | LOCAL | SSH | IPC_TERMINAL | PHONE |
|--------|-------|-----|--------------|-------|
| NONE | ✅ | ✅ | ✅ | ✅ |
| LIGHT (diff + E1) | ✅ | ✅ | ✅ | n/a |
| BUILD | ⬇️ downgrade→LIGHT | ✅ | ⚠️ toolchain | ❌ |
| BUILD_AND_TEST | ⬇️ downgrade→LIGHT | ✅ | ⚠️ toolchain | ❌ |
| FULL | ⬇️ downgrade→LIGHT | ✅ | ⚠️ toolchain | ❌ |

**Aturan:**
1. Policy yang tidak didukung profile → **downgrade** ke tingkat tertinggi yang didukung (tidak pernah gagal diam-diam, tidak pernah mengklaim lebih tinggi dari kemampuan).
2. Hasil downgrade → verdict jujur **PARTIALLY_VERIFIED**, bukan VERIFIED.
3. Satu-satunya E2 yang selalu tersedia on-device adalah **diff scope check** (deterministik, murah) — jadikan tulang punggung verifikasi LOCAL.
4. `verification_policy` disimpan per task (`agent_tasks.verification_policy`) agar keputusan audit-able.

---

## 14. Diff sebagai Evidence

LineCode Pro sudah mencatat setiap write/edit ke `diff_records` (`DiffRecorder` di `ToolExecutor`) dan menyediakan revert (`DiffRepository` / `FileRestorer`). Harness memperlakukan diff sebagai evidence:

```
Diff
 +-- intended files?
 +-- unexpected files?
 +-- expected scope?
 +-- suspicious deletion?
 +-- dependency modification?
```

Diff bukan sekadar UI feature — ia bagian dari verification (E2-class, deterministik).

---

## 15. Verification Verdict

`VERIFIED` / `PARTIALLY_VERIFIED` / `UNVERIFIED` / `FAILED` / `BLOCKED`

Contoh: Build PASS + test tidak tersedia → **PARTIALLY_VERIFIED**, bukan VERIFIED.

---

## 16. Memory Engine (D07)

Harness mempertahankan sistem memory yang ada. Sumber:

- `MEMORY_EXTRACTION` → `MemoryExtractionService` (gate confidence: `MIN_KEEP_CONFIDENCE=0.78`, `RULE_CONFIDENCE=0.88`, `MODEL_DEFAULT_CONFIDENCE=0.82`; `MAX_MEMORIES=3`; durable-signal trigger).
- `MEMORY_UPDATE_TOOL` → `MemoryUpdateTool` (`memory_update`, scope user/project/environment, tolak sensitif).
- `LEARNING_CONTEXT` → `LearningContextService` / `MemoryRanker` (BM25).
- `VERIFIED_TASK_RESULT` → baru (dari verdict task).
- `USER_CORRECTION` → baru (koreksi user = sinyal kuat).

**Koreksi review (D07):** lifecycle penuh `CANDIDATE → SUPPORTED → VERIFIED → PERSISTENT` membutuhkan status "pending" yang belum ada di schema. Di v1:

```
CANDIDATE --confidence ≥ threshold--> PERSISTENT   (gate yang SUDAH ADA)
```

Artinya CANDIDATE→PERSISTENT cukup dimodelkan sebagai *confidence ≥ threshold = committed* (perilaku `MemoryExtractionService` saat ini). Lifecycle penuh dengan status SUPPORTED/VERIFIED/SUPERSEDED = **v2** — tidak menambah tabel memory baru di v1.

Memory classes: `USER_PREFERENCE`, `PROJECT_FACT`, `PROJECT_CONVENTION`, `WORKFLOW`, `FAILURE_PATTERN`, `TOOL_KNOWLEDGE`, `ENVIRONMENT_FACT`, `AGENT_SKILL`.

---

## 17. Working Memory

`working_memory` (tabel sudah ada, punya `expires_at`) untuk informasi berguna selama task/session tapi tidak layak jadi memory permanen:

```
Current blocker: Gradle daemon returned error X.
expires_at: task end + retention window
```

Lebih baik daripada memasukkan semua transient info ke `memories`. Tidak ada mekanisme persistence baru.

---

## 18. Learning Engine

Learning ≠ model mengubah dirinya sendiri.

```
TASK RESULT → EXTRACT LESSON → CREATE CANDIDATE → CHECK EVIDENCE → COMMIT / REJECT
```

Contoh: Gradle command X gagal 3× pada project tertentu (3 independent failures) → candidate "Avoid X; use Y" → commit sebagai `PROJECT_WORKFLOW` setelah sukses selanjutnya (validasi). Di v1: cukup memori kandidat berbasis evidence; pembelajaran otomatis lintas-task = P4.

---

## 19. Skill Engine

Skills = executable knowledge. Infrastruktur yang ada: `SkillRepository`, `SkillFileManager`, `GitHubSkillInstaller`, tabel `skills` + `skill_usage`. Lifecycle:

```
DISCOVER → LOAD → USE → OBSERVE RESULT → UPDATE USAGE → EVALUATE
```

Skill tidak menjadi authoritative hanya karena sering digunakan; **EVALUATE** (skor kegunaan berbasis hasil) = v2.

---

## 20. Sub-Agent Harness

Sub-agent = isolated execution unit. Fondasi: `AgentExecutionController`, `PipelineAgent`, `PipelineDependencyResolver`, `AgentResultRegistry` (sudah session-scoped, `clearAll()` saat ganti conversation), `AgentOutputTool`, `AgentProgressSession` / `PipelineProgressSession` (thread-safe).

```
Parent Task → Delegate → Sub-Agent Task
                           +-- own context, own state, own budget, restricted tools
                           ↓
                           Result → (kembali sebagai OBSERVATION) → Parent Verification
```

**Invariant:** hasil sub-agent tidak otomatis benar. Ia kembali sebagai **observation/evidence candidate**; parent memverifikasi. Tool budget sub-agent berbagi budget task via `AtomicInteger` (pola `toolCallBudget` yang sudah ada).

---

## 21. Agent Pipeline

Pipeline dipakai jika task bisa dipecah menjadi stages deterministik:

```
EXPLORE → PLAN → IMPLEMENT → VERIFY
```

Tidak wajib. Untuk task sederhana: `REQUEST → TOOL → VERIFY → DONE`. `PipelineDependencyResolver` (paralel layer) dan `PipelineProgressSession` yang sudah ada tetap dipakai.

---

## 22. Recovery Engine

Failure ≠ selalu retry. Klasifikasi + recovery:

| Failure | Recovery v1 | Mekanisme existing |
|---------|-------------|--------------------|
| MODEL_FAILURE | RETRY (max 3, backoff) | `RetryPolicy`: eksponensial 2→4→8→16 + jitter ±500ms; skip 4xx permanen (kecuali 408/429) via `statusCode` |
| NETWORK_FAILURE | RETRY | sama seperti model failure |
| TOOL_FAILURE | ALTERNATE_TOOL / retry tool | error tool dikembalikan sebagai observation; model memilih ulang |
| POLICY_BLOCK | ASK_USER / BLOCKED | alur `PermissionModeController` |
| BUILD_FAILURE / TEST_FAILURE | REPLAN (via model, karena kegagalan dikembalikan sebagai observation) | sudah *implicit* hari ini; di-formal-kan dengan attempt budget |
| CONTEXT_FAILURE | RETRY compaction | `ContextCompactionService.MAX_COMPACT_RETRIES` |
| RESOURCE_FAILURE | MODIFY budget / STOP | `Resource Governor` |
| USER_BLOCK | STOP / WAITING_USER | mekanisme cancel yang ada |

**Budget retry:** `max_attempts = 3` per task (field `attempt_count` / `max_attempts`). Setelah budget habis → `FAILED` / `ASK_USER`, bukan infinite retry.

---

## 23. Resource Governor

Budget: `TOKEN`, `TIME`, `TOOL_CALL`, `SUB_AGENT`, `PARALLELISM`, `OUTPUT_SIZE`.

```
Contoh task: token_budget: 30K, tool_budget: 30, sub_agent_budget: 2, parallel_tool_limit: 4
```

Enforcement points existing:

| Budget | Enforcement v1 |
|--------|----------------|
| Token (context window) | `ContextManager` + `ContextCompactionService` (soft/hard) |
| Parallelism | `ToolExecutionScheduler` 4-thread pool (+ `cancelRemainingFutures` saat cancel) |
| Tool call / sub-agent | `AtomicInteger toolCallBudget` (sudah dipakai `runAgentTool`/`runAgentPipelineTool`/`runAgentLoop`) |
| Output size | `ToolResult.truncateContent()` 50 KB |

v1: budget di-tracking dan di-enforce untuk **termination condition** (tool call, sub-agent, attempts); budget token = *soft advisory* (window tetap dikendalikan compaction). Enforce penuh token (cumulative spend) = P4.

---

## 24. Keep-Alive ≠ Harness Loop

`KeepAliveService` bukan agent harness daemon — fungsinya hanya menjaga **generation lifecycle** (foreground service agar proses tidak diputus Android saat generation berlangsung). Harness berhenti saat: task completed / failed / cancelled / generation stopped. Keep-alive tetap dipakai seperti sekarang.

---

## 25. Background Boundary

LCP-Harness tidak memperkenalkan: filesystem watcher, continuous code indexing daemon, autonomous planning daemon, continuous memory learner, background agent loop.

Execution hanya aktif saat ada lifecycle event: USER REQUEST, AGENT TASK, TOOL EXECUTION, GENERATION, VERIFICATION, EXPLICIT BACKGROUND SERVICE. Service yang ada tetap mengikuti lifecycle & permission masing-masing.

---

## 26. Modes (D06)

**Satu Task Controller, empat mode** (jangan lupa Control):

| Mode | Autonomy | Mutasi | Utamakan |
|------|----------|--------|----------|
| CHAT | minimal | tools opsional | answer-first |
| PLAN | analysis | tanpa mutasi by default | dekomposisi task |
| AGENT | penuh | plan→execute→verify→recover | eksekusi berverifikasi |
| CONTROL | phone-scoped | via PHONE_PERMISSION | kontrol device |

Mode = **policy profile**, bukan tiga/empat sistem terpisah. Mapping existing: `ChatModeRepository` (CHAT/PLAN/AGENT/CONTROL), `applyMode()` / `applyPermissionForMode()`. Task di v1 **hanya dibuat di mode AGENT** (dan PLAN opsional untuk task read-only); CHAT/CONTROL tidak membuat task (D10 — backward compatible).

---

## 27. Phone Control Profile

`PHONE_PROFILE`: tools `screenshot`, `click`, `swipe`, `long_press`, `view_hierarchy`, `global_action`. Semua action lewat `PHONE_PERMISSION` (accessibility `LineCodeAccessibilityService` via `PhoneControlService` interface). Phone tidak boleh mewarisi permission filesystem secara otomatis. Alur `Control` mode yang sudah ada tetap.

---

## 28. External Extensions

MCP & custom agents (`agentx_` / `mcpx_` via `ExtensionRepository`, `CustomAgentExtensionTool`, `CustomMcpHttpTool`) = **untrusted extension boundary**.

```
Extension → Capability discovery → Policy → Execution → Observation → Verification
```

Extension **tidak boleh otomatis**: menulis persistent memory, mengubah policy, memberi permission, atau memodifikasi harness. (Catatan: `ArchiveSecretRedactor` tetap menjadi garis pertahanan secret saat ekspor.)

---

## 29. Harness Event Model (D05)

Semua komponen berkomunikasi lewat event. **Koreksi review:** `agent_events` hanya untuk **event lifecycle task-level** (TASK_CREATED, TASK_STARTED, PLAN_CREATED, POLICY_CHECKED, TASK_BLOCKED, EVIDENCE_CREATED, VERIFICATION_COMPLETED, MEMORY_CANDIDATE_CREATED, MEMORY_COMMITTED, TASK_COMPLETED, TASK_FAILED, TASK_CANCELLED).

- Event tool-level (TOOL_STARTED/COMPLETED, FILE_CHANGED, DIFF_RECORDED, BUILD_*/TEST_*) **tidak** diduplikasi ke `agent_events` — sudah tercatat di `tool_calls` / `tool_results` / `diff_records`.
- `FILE_CHANGED` bukan filesystem watcher event: artinya *harness mengetahui file berubah karena aksi pipeline atau refresh on-demand* — cukup dipakai internal (in-memory), tidak perlu dipersist.
- Kebijakan pruning: event > N per task / > M hari dihapus (hindari pertumbuhan SQLite device).

---

## 30. Integrasi dengan Arsitektur yang Ada (D01)

### 30.1 Di mana harness hidup — pembungkusan, bukan loop paralel

**Koreksi review terpenting.** Alur hari ini sudah nyaris menjadi harness loop:

```
ChatInteractionController.dispatchMessage
        ↓
GenerationFlowController: model loop + tool execution
        ↓
continueModelAfterTools → shouldAutoCompactMidLoop → compaction
        ↓
ToolRunController / ToolExecutionCoordinator → ToolExecutor
        ↓
AgentExecutionController (sub-agent)
```

**Task Controller v1 = lapisan tipis yang MEMANGGIL alur yang ada, bukan loop kedua:**

```
TaskController.onUserIntent(goal)
   ├─ buat/update agent_tasks (status, budget check, capsule)
   ├─ panggil ChatInteractionController.dispatchMessage(...)  // TIDAK diubah
   ├─ setelah tiap iterasi selesai:
   │    ├─ evaluasi verdict (EvidenceCollector + VerdictEngine)
   │    ├─ cek budget & completion_condition
   │    └─ update state / recovery
   └─ selesai → COMPLETED / FAILED / CANCELLED
```

Aturan integrasi:
1. **Tidak mengubah** `GenerationFlowController` / `ModelClient` / `ToolExecutor` / protocol di v1 (kecuali titik injeksi task_id yang minimal).
2. Capsule diinjeksi lewat `SystemPromptProvider` (region P0), bukan sebagai pesan user.
3. `MainDependencies` / `MainControllerInitializer` me-wire `TaskController` + `AgentTaskRepository`; `ChatUiStateAssembler` menambah state task ringan ke `ChatUiState` (untuk UI, §31).

### 30.2 Backward compatibility (D10)

Conversation **tanpa task berperilaku persis seperti hari ini**. Task dibuat hanya di mode AGENT (dan PLAN read-only, opsional). Tidak ada migrasi data conversation yang sudah ada; `agent_tasks` adalah tabel baru yang kosong.

### 30.3 Thread-safety (D09)

Harness dipanggil dari main thread, thread stream model, thread pool tool (4), dan background agent. Aturan:
- Mutasi state task → confined ke thread generation + main (post back).
- Counter lintas thread (budget, attempt) → `AtomicInteger` (pola yang sudah dipakai `AgentResultRegistry` / `toolCallBudget`).
- Struktur progres → `synchronized` (pola `PipelineProgressSession`).
- `TaskController` tidak boleh memegang lock lintas thread yang bisa deadlock.

### 30.4 Crash reconciliation (D04)

Android bisa membunuh proses di tengah EXECUTING. Saat app start (`MainControllerInitializer`), `AgentTaskRepository.reconcileStaleTasks()`:
- task `CREATED`/`PLANNING`/`EXECUTING`/`VERIFYING` yang menggantung (updated_at lama / process mati) → `INTERRUPTED` (atau FAILED), tulis `agent_events` TASK_INTERRUPTED.
- Opsional v2: resume task dengan capsule utuh.

---

## 31. UI & i18n

**Koreksi review:** spec asli tidak menyentuh UI. Keputusan v1:

- Status/verdict task disurface lewat **mekanisme kartu progres agent yang sudah ada** (`AgentProgressSession`, `ToolCallAgentView`, `AgentToolResultDisplay`, JSON `linecode_agent_progress`), bukan sistem view baru.
- Ringkasan task (goal, status, verdict, budget tersisa) sebagai satu baris/card kecil di header chat saat task aktif.
- Semua string user-visible baru (label status, verdict, budget habis, dst.) → resource `R.string.*` di `values/strings.xml`, `values-zh/strings.xml`, `values-ru/strings.xml` (konvensi i18n project; Indonesian localization tetap di daftar saran fitur terpisah).
- Tidak ada string hardcoded di Java (termasuk di `feature-tool` / `tool-ui` jika tool baru dibuat).

---

## 32. Project Snapshot & On-Demand Refresh

Refresh on-demand menghasilkan `ProjectSnapshot` (cache in-memory):

```
project_id, timestamp, modules, source_roots, important_files,
build_system, dependencies, recently_changed_files, known_errors
```

Trigger resmi: (1) task start, (2) setelah agent write/edit, (3) sebelum operasi yang butuh struktur terbaru, (4) setelah build/test memberi info struktural, (5) user eksplisit minta.

Sumber data: repository file tree yang ada (`SshFileTreeRepository`, `WorkspacePaths`, `ProjectRepository`). **Source of truth tetap filesystem/workspace; snapshot adalah cache.** Jika stale → refresh, bukan percaya cache.

---

## 33. Testing Strategy

Mengikuti konvensi project (JUnit 4, no Robolectric/Mockito, in-memory fakes):

| Target | Lokasi test | Pola |
|--------|-------------|------|
| `TaskStateMachine` (transisi status valid/invalid) | `feature-model/src/test/...` | pure Java, seperti `RetryPolicyTest` |
| `VerdictEngine` (E0–E4 → verdict; downgrade profile) | `feature-model` | pure Java |
| `TaskBudget` (counter, habis, reset) | `feature-model` | pure Java |
| `EvidenceClassifier` (klaim vs observation vs evidence) | `feature-model` | pure Java |
| `AgentTaskRepository` (CRUD + reconcile stale) | `data` test | in-memory DB fake (pola repository test yang ada) |
| `TaskController` (bukan loop paralel; bungkus dispatch) | `app` test | fake `GenerationFlowController`/`ChatInteractionController` |

---

## 34. Database Mapping (v1 — tidak ada database baru)

| Konsep | Tabel existing |
|--------|----------------|
| Conversation | `conversations` / `messages` |
| Tool execution | `tool_calls` / `tool_results` |
| Evidence | `tool_results` + `diff_records` + referensi `agent_evidence` |
| Memory | `memories` (+ FTS) |
| Transient memory | `working_memory` (+ FTS, `expires_at`) |
| Skills | `skills` + `skill_usage` |
| Project | `projects` |
| Execution providers | `ipc_providers` + metadata project/workspace |

Schema existing sudah punya 21 tabel utama + FTS untuk memories, conversations, working memory — **tidak perlu tabel baru** untuk hal di atas.

---

## 35. Minimal Schema Extension (D02, D05)

Jika harness butuh persistence eksplisit, v1 menambahkan **tiga tabel**. DDL berikut adalah kontrak konseptual; implementasi final via `LineCodeSchema` dengan pola `CREATE TABLE IF NOT EXISTS` + auto-migration (pola `ensureModelConfigColumns`), dan repository di `:data` sebagai satu-satunya pemanggil.

### 35.1 `agent_tasks` (diperluas vs spec asli)

```sql
CREATE TABLE IF NOT EXISTS agent_tasks (
    id                     TEXT PRIMARY KEY,
    conversation_id        TEXT NOT NULL,
    project_id             TEXT,
    parent_task_id         TEXT,               -- sub-agent: task induk
    mode                   TEXT NOT NULL DEFAULT 'AGENT',   -- CHAT|PLAN|AGENT|CONTROL
    goal                   TEXT NOT NULL,
    scope                  TEXT,
    constraints            TEXT,               -- JSON array
    status                 TEXT NOT NULL DEFAULT 'CREATED',
    risk_level             TEXT DEFAULT 'LOW',
    execution_profile      TEXT DEFAULT 'LOCAL',     -- LOCAL|SSH|IPC_TERMINAL|PHONE   (D02)
    verification_policy    TEXT DEFAULT 'LIGHT',     -- NONE|LIGHT|BUILD|BUILD_AND_TEST|FULL  (D02)
    completion_condition   TEXT,
    failure_condition      TEXT,
    budget_json            TEXT,               -- {"token":30000,"tool_call":30,"sub_agent":2,"parallel":4}  (D02)
    verdict                TEXT,               -- VERIFIED|PARTIALLY_VERIFIED|UNVERIFIED|FAILED|BLOCKED  (D02)
    attempt_count          INTEGER DEFAULT 0,  -- (D02)
    max_attempts           INTEGER DEFAULT 3,  -- (D02)
    created_at             INTEGER NOT NULL,
    updated_at             INTEGER NOT NULL,
    completed_at           INTEGER
);
CREATE INDEX IF NOT EXISTS idx_agent_tasks_conversation ON agent_tasks(conversation_id);
CREATE INDEX IF NOT EXISTS idx_agent_tasks_status        ON agent_tasks(status);
CREATE INDEX IF NOT EXISTS idx_agent_tasks_parent        ON agent_tasks(parent_task_id);
```

Kolom `execution_profile`, `verification_policy`, `budget_json`, `verdict`, `attempt_count`/`max_attempts` adalah **perluasan dari spec asli** (yang hanya punya id/conversation_id/project_id/parent_task_id/mode/goal/status/risk_level/completion_condition/created_at/updated_at/completed_at) — hasil review: ketiganya (profile, policy, budget) disebut di badan spec (bagian 12, 15, 26) tapi hilang dari skema.

### 35.2 `agent_evidence` (referensi, bukan salinan — D05)

```sql
CREATE TABLE IF NOT EXISTS agent_evidence (
    id          TEXT PRIMARY KEY,
    task_id     TEXT NOT NULL,
    type        TEXT NOT NULL,     -- CLAIM|OBSERVATION|EVIDENCE|VERDICT
    level       INTEGER NOT NULL,  -- 0..4 (E0..E4)
    source      TEXT NOT NULL,     -- "tool:FileWriteTool" | "build" | "test" | "diff" | "user" | "model"
    claim       TEXT,
    summary     TEXT,              -- ringkasan singkat, BUKAN salinan penuh output
    ref_table   TEXT,              -- tool_results | diff_records | messages | ... (referensi)
    ref_id      TEXT,              -- id baris sumber asli
    strength    REAL,
    verified    INTEGER DEFAULT 0,
    created_at  INTEGER NOT NULL
);
CREATE INDEX IF NOT EXISTS idx_agent_evidence_task ON agent_evidence(task_id);
```

### 35.3 `agent_events` (task-level saja + pruning — D05)

```sql
CREATE TABLE IF NOT EXISTS agent_events (
    id          TEXT PRIMARY KEY,
    task_id     TEXT NOT NULL,   -- event task-level: wajib ada task (audit H3)
    event_type  TEXT NOT NULL,     -- TASK_CREATED | TASK_STARTED | ... (task-level SAJA)
    source      TEXT,
    payload     TEXT,              -- JSON kecil, ≤ ~2KB
    created_at  INTEGER NOT NULL
);
CREATE INDEX IF NOT EXISTS idx_agent_events_task ON agent_events(task_id);
```

Tidak ada tabel baru untuk memory, skills, tool calls, atau diff — semuanya sudah tersedia.

---

## 36. Task Lifecycle

```
CREATE → LOAD STATE → REFRESH PROJECT CONTEXT → PLAN → POLICY CHECK → EXECUTE
→ OBSERVE → VERIFY → (FAIL → RECOVER → EXECUTE) → UPDATE STATE → LEARN
→ CHECK COMPLETION → (CONTINUE → PLAN) → COMPLETE
```

---

## 37. Canonical Agent Loop

```
while task.active:

    observe_state()
    build_context()
    decision = derive_decision(model.decide())      # D08: turunan dari output, bukan kontrak baru

    policy = check_policy(decision)
    if policy.blocked:
        handle_block()
    else:
        result = execute(decision)
        evidence = collect_evidence(result)
        verdict = verify(evidence, task.verification_policy, task.execution_profile)   # D01

        update_state(result, verdict)

        if recoverable_failure(verdict):
            recover()
        elif completion_condition_met():
            complete()
        else:
            continue()
```

> Implementasi aktual tetap memakai Java/controller/service yang ada — **pseudocode ini adalah contract, bukan kewajiban struktur class**. Alur eksekusi nyata = `TaskController` memanggil `GenerationFlowController` yang ada (§30.1).

---

## 38. Harness Invariants (final)

1. **Model tidak memiliki authority langsung** — model meminta action; harness menentukan apakah boleh.
2. **Tool result bukan otomatis truth** — tool result = observation; validation menentukan cukup-tidaknya untuk claim.
3. **Memory bukan conversation** — conversation = history; memory = selected persistent knowledge.
4. **Working memory harus expire** — transient state tidak boleh memenuhi persistent memory tanpa alasan.
5. **Self-evaluation bukan independent evidence** — agent tidak memverifikasi dirinya sendiri.
6. **No infinite execution** — setiap task punya budget + termination condition.
7. **No mandatory background watcher** — refresh project state on-demand.
8. **Sub-agent tidak otomatis trusted** — hasil sub-agent = observation/evidence candidate.
9. **Side effects harus policy-controlled** — write/delete/shell/git/push/phone punya permission boundary.
10. **Recovery punya budget** — retry tidak boleh infinite loop.
11. **(D03) User request terbaru tetap prioritas tertinggi** — capsule tidak boleh menggesernya; goal drift → task baru.
12. **(D01) Verifikasi dibatasi kemampuan profile** — verdict jujur lebih baik daripada klaim tinggi.

---

## 39. Contoh Alur

### Coding Task — "Fix the build error."
`CREATE TASK → REFRESH PROJECT STATE → READ ERROR → BUILD EVIDENCE → PLAN → READ RELEVANT FILE → EDIT → RECORD DIFF → BUILD → [FAIL: analyze + replan] → [PASS: VERIFY DIFF] → TASK COMPLETE`

Catatan: di LOCAL murni, "BUILD" tidak tersedia → downgrade LIGHT → verdict PARTIALLY_VERIFIED sampai konfirmasi user.

### Research Task — "Find the best library for X."
`CREATE TASK → PLAN → WEB SEARCH → FETCH SOURCES → COLLECT EVIDENCE → COMPARE → GENERATE ANSWER → CITE SOURCES → COMPLETE`

Tanpa coding verification pipeline — verification policy task-specific.

### Destructive Operation — "Delete this directory."
`REQUEST → MODEL DECISION → POLICY → DESTRUCTIVE ACTION → REQUIRE CONFIRMATION → EXECUTE → OBSERVE → RECORD`

Agent tidak boleh melewati confirmation karena confidence tinggi.

---

## 40. Harness Profiles

`CHAT_PROFILE`, `CODING_PROFILE`, `RESEARCH_PROFILE`, `PHONE_PROFILE`, `SUB_AGENT_PROFILE`.

Setiap profile menentukan: tools, verification, permissions, budget, memory policy, completion policy. Core harness tetap sama.

---

## 41. Yang Sudah Ada vs yang Perlu Ditambahkan

**Sudah ada (dipakai, tidak diganti):** `ModelClient`, `ModelProtocol` (+ 4 implementasi), `ToolRegistry`, `ToolExecutor`, `ToolExecutionScheduler`, `ToolExecutionCoordinator`, `PermissionModeController`, `ToolReviewListener`, `ContextManager`, `ContextCompactionService`, `TokenUsageTracker`, `MemoryExtractionService`, `MemoryRanker`, `LearningContextService`/`LearningContextStore`, `MemoryUpdateTool`, `SkillRepository`, `SkillFileManager`, `GitHubSkillInstaller`, `AgentExecutionController`, `PipelineAgent`, `PipelineDependencyResolver`, `AgentResultRegistry`, `DiffRecorder`/`DiffRepository`, `FileRestorer`, `ConversationRepository`, `ProjectRepository`, `KeepAliveService`, `RetryPolicy`, `GitTool`, `ChatModeRepository`, `PhoneControlService`.

**Perlu ditambahkan (konseptual, v1):** `TaskController` (pembungkus), `TaskCapsule`, `TaskStateMachine` (pure Java), `VerdictEngine`, `TaskBudget`, `EvidenceClassifier`, `AgentTaskRepository` (+ 3 tabel), reconciliation crash, event model task-level, project snapshot/on-demand refresh, injeksi capsule ke `SystemPromptProvider`, UI status task ringan.

---

## 42. Prioritas Implementasi

| Prioritas | Isi | Keluar |
|-----------|-----|--------|
| **P0 — Harness Core** | Enum/DTO di `:core-model`; `TaskStateMachine`/`TaskBudget` pure Java di `:feature-model`; `AgentTaskRepository` + tabel `agent_tasks` di `:data`; `TaskController` membungkus `GenerationFlowController`; reconciliation crash; backward-compat guard | Task lifecycle + budget + completion jalan, tanpa mengubah alur existing |
| **P1 — Verification** | `VerdictEngine` + `EvidenceClassifier`; diff-as-evidence (scope check); profile capability matrix (downgrade + verdict jujur); rekam `agent_evidence` (referensi) | Verdict nyata E0–E4; tidak ada klaim palsu di LOCAL |
| **P2 — Context** | Prioritas P0–P8 final; injeksi capsule via `SystemPromptProvider`; freshness; project snapshot + on-demand refresh | Model bekerja dengan task awareness |
| **P3 — Learning** | Memory candidate berbasis verdict (VERIFIED task → lesson); failure pattern; `agent_events` + pruning | Memory evidence-backed |
| **P4 — Advanced** | Sub-agent budget lintas-task; pipeline verification; adaptive resource governor; memory lifecycle penuh (SUPPORTED/VERIFIED/SUPERSEDED); skill EVALUATE | (opsional, v2) |

---

## 43. Risiko & Mitigasi

| Risiko | Mitigasi |
|--------|----------|
| Task loop paralel menduplikasi `GenerationFlowController` | D01: TaskController adalah lapisan tipis yang memanggil alur existing; dilarang mengubah alur model/tool di v1 |
| Verifikasi mengklaim BUILD padahal LOCAL | D01/D12: capability matrix + downgrade + verdict jujur; diff scope check sebagai E2 lokal |
| Goal drift (capsule stale mendominasi) | D03: capsule di P0-region, user request tetap P1; deteksi ganti topik → task baru |
| Task menggantung di EXECUTING setelah process death | D04: reconciliation saat start → INTERRUPTED |
| Duplikasi data event/evidence | D05: event task-level saja; evidence = ringkasan + referensi; pruning |
| Thread-safety | D09: confine mutasi + AtomicInteger + synchronized (pola yang sudah ada) |
| Breakback compat conversation lama | D10: task opsional, hanya mode AGENT; tanpa task = perilaku hari ini |
| i18n regresi (string hardcoded) | Konvensi project: semua string lewat `R.string.*` (en/zh/ru); CI grep CJK yang disarankan di code-review-findings |
| Scope creep jadi "agent framework" | Non-tujuan §1 + P0–P4; PR kecil bertahap; unit test pure Java tiap komponen |

---

## 44. Pertanyaan Terbuka

1. **Trigger pembuatan task:** auto-deteksi dari intent user, atau eksplisit (perintah `/task` / tombol)? v1 menyarankan: mode AGENT + deteksi intent jelas; sisanya tetap CHAT biasa.
2. **Task pause/resume:** apakah user bisa pause task lalu lanjut di sesi berikutnya? (v1: no — task hidup per conversation; resume = v2.)
3. **Budget token:** satuan & cara akuntansi cumulative spend di tengah compaction? (v1: tracking, enforce hanya tool_call/sub_agent/attempts.)
4. **Multi-task paralel dalam satu conversation:** dilarang di v1 (sekuensial); sidebar task multi = v2.
5. **Interaksi dengan `/todo` yang sudah ada:** task ≠ todo list; task adalah unit lifecycle, todo adalah state yang di-ekspos ke model. Perlu dokumen relasi eksplisit.
6. **Verdict ditampilkan ke user atau internal?** Rekomendasi: ringkasan kecil di UI; detail di `agent_evidence`.

---

## 45. Addendum: Spesifikasi Implementasi (Resolusi Audit Lengkap)

Bagian ini menyelesaikan seluruh temuan dari Audit Pre-Implementasi (`docs/lcp-harness-v1-audit.md`) dan menambahkan spesifikasi yang dibutuhkan sebelum penulisan kode dimulai.

---

### 45.1 Schema Versioning (Resolusi C1)

`LineCodeDatabase` saat ini version 1. Menambah 3 tabel baru = **DB_VERSION = 2**.

**Migration step di `LineCodeSchema`:**

```java
// LineCodeSchema.java — method version2Migration(SQLiteDatabase db)

// --- Tabel agent_tasks ---
db.execSQL("CREATE TABLE IF NOT EXISTS agent_tasks ("
    + "id TEXT PRIMARY KEY, "
    + "conversation_id TEXT NOT NULL, "
    + "project_id TEXT, "
    + "parent_task_id TEXT, "
    + "mode TEXT NOT NULL DEFAULT 'AGENT', "
    + "goal TEXT NOT NULL, "
    + "scope TEXT, "
    + "constraints TEXT, "
    + "status TEXT NOT NULL DEFAULT 'CREATED', "
    + "risk_level TEXT DEFAULT 'LOW', "
    + "execution_profile TEXT DEFAULT 'LOCAL', "
    + "verification_policy TEXT DEFAULT 'LIGHT', "
    + "completion_condition TEXT, "
    + "failure_condition TEXT, "
    + "budget_json TEXT, "
    + "verdict TEXT, "
    + "attempt_count INTEGER DEFAULT 0, "
    + "max_attempts INTEGER DEFAULT 3, "
    + "created_at INTEGER NOT NULL, "
    + "updated_at INTEGER NOT NULL, "
    + "completed_at INTEGER)");

// --- Tabel agent_evidence ---
db.execSQL("CREATE TABLE IF NOT EXISTS agent_evidence ("
    + "id TEXT PRIMARY KEY, "
    + "task_id TEXT NOT NULL, "
    + "type TEXT NOT NULL, "
    + "level INTEGER NOT NULL, "
    + "source TEXT NOT NULL, "
    + "claim TEXT, "
    + "summary TEXT, "
    + "ref_table TEXT, "
    + "ref_id TEXT, "
    + "strength REAL, "
    + "verified INTEGER DEFAULT 0, "
    + "created_at INTEGER NOT NULL)");

// --- Tabel agent_events ---
db.execSQL("CREATE TABLE IF NOT EXISTS agent_events ("
    + "id TEXT PRIMARY KEY, "
    + "task_id TEXT NOT NULL, "
    + "event_type TEXT NOT NULL, "
    + "source TEXT, "
    + "payload TEXT, "
    + "created_at INTEGER NOT NULL)");

// --- Index ---
db.execSQL("CREATE INDEX IF NOT EXISTS idx_agent_tasks_conversation ON agent_tasks(conversation_id)");
db.execSQL("CREATE INDEX IF NOT EXISTS idx_agent_tasks_status ON agent_tasks(status)");
db.execSQL("CREATE INDEX IF NOT EXISTS idx_agent_tasks_parent ON agent_tasks(parent_task_id)");
db.execSQL("CREATE INDEX IF NOT EXISTS idx_agent_evidence_task ON agent_evidence(task_id)");
db.execSQL("CREATE INDEX IF NOT EXISTS idx_agent_events_task ON agent_events(task_id)");
```

**Test:** `AgentTaskSchemaTest` — verifikasi tabel ada, kolom sesuai, index aktif, INSERT/SELECT berjalan.

---

### 45.2 State Transition Table (Resolusi C2)

```
State           │ Transisi Valid
────────────────┼──────────────────────────────────────────────────────────────────────
CREATED         │ → PLANNING | CANCELLED | FAILED
PLANNING        │ → EXECUTING | CANCELLED | FAILED | BLOCKED
EXECUTING       │ → VERIFYING | BLOCKED | WAITING_USER | COMPLETED | FAILED | CANCELLED
VERIFYING       │ → COMPLETED | FAILED | EXECUTING (replan) | BLOCKED
BLOCKED         │ → PLANNING (retry) | EXECUTING (resume) | CANCELLED | FAILED
WAITING_USER    │ → EXECUTING (user respond) | CANCELLED | FAILED
COMPLETED       │ → (terminal)
FAILED          │ → (terminal)
CANCELLED       │ → (terminal)
INTERRUPTED     │ → FAILED | CANCELLED
```

**Implementasi:** `TaskStateMachine` pure Java di `:feature-model` — `Map<TaskStatus, Set<TaskStatus>> VALID_TRANSITIONS` + method `transition(current, next)` yang throw `InvalidTransitionException` untuk transisi invalid. Terminal states = empty set.

**Test:** `TaskStateMachineTest` — semua transisi valid; semua invalid → exception; terminal states tidak ada transisi.

---

### 45.3 Completion Condition Evaluator (Resolusi C3)

Dua komponen evaluasi:

1. **Model-judged (E0):** model membaca `completion_condition` (free-text di capsule) dan memilih berhenti atau lanjut — today's behavior.
2. **Deterministic guards (E2):** `VerdictEngine` pure Java mengevaluasi evidence post-generation:
   - Ada diff tercatat? (E1)
   - Diff scope match task scope? (E2)
   - Build test tersedia & berhasil? (E2/E3, tergantung profile)
   - Ada tool error? (E1)
   - Model klaim selesai tapi tidak ada perubahan? → UNVERIFIED

`VerdictEngine` menerima: task capsule, evidence list, execution profile → menghasilkan verdict `VERIFIED | PARTIALLY_VERIFIED | UNVERIFIED | FAILED | BLOCKED`. Dipanggil dari `TaskController.onGenerationComplete()` via Host callback.

---

### 45.4 Host Wrapper Pattern (Resolusi C4)

Kontrol sesungguhnya = interface `GenerationFlowController.Host`. TaskController menganalisis callback:

```java
class TaskAwareGenerationHost implements GenerationFlowController.Host {
    Host delegate; TaskController taskController;

    onGenerationStart(id)  → taskController.onGenerationStart(id);  delegate.onGenerationStart(id);
    onModelResponse(resp)  → taskController.onModelResponse(resp);  delegate.onModelResponse(resp);
    onToolResult(name,r)   → taskController.onToolResult(name,r);   delegate.onToolResult(name,r);
    onGenerationComplete(id) → Verdict v = taskController.onGenerationComplete(id); delegate.onGenerationComplete(id);
    onError(error)         → taskController.onError(error);         delegate.onError(error);
}
```

**Pembatalan loop:** `cancellationToken.cancel()` (pattern dari `ToolExecutionScheduler.cancelRemainingFutures`).

---

### 45.5 Task Mode vs ChatMode (Resolusi C5)

Task `mode` **meng-override** ChatMode selama task aktif:

- `TaskController.onUserIntent()`: simpan ChatMode saat ini, panggil `chatModeRepository.applyMode(task.mode())` + `applyPermissionForMode()`.
- `TaskController.onTaskComplete()`: kembalikan ChatMode ke sebelum task.
- Task tidak bisa dibuat dengan mode berbeda dari mode conversation tanpa konfirmasi eksplisit.
- CHAT mode: tidak ada task (D10). PLAN mode: task read-only. CONTROL mode: phone profile.

---

### 45.6 Recovery Budget (Resolusi H1)

- `attempt_count` = jumlah pemanggilan `dispatchMessage` dari TaskController (bukan retry per generation).
- `max_attempts = 3` = jumlah generation attempts total.
- RetryPolicy (3 per generation) = internal generation, dihitung sebagai 1 attempt.
- `attempt_count >= max_attempts` → FAILED.

---

### 45.7 Task Creation Trigger (Resolusi H2)

v1: task dibuat **secara implisit** saat mode AGENT + user mengirim pesan. Satu task aktif per conversation; task baru hanya jika task lama terminal. Tidak ada perintah `/task` eksplisit.

---

### 45.8 Reconciliation Async (Resolusi H4)

`AgentTaskRepository.reconcileStaleTasks()` di background thread saat app start (5 menit threshold). Task non-terminal yang stale → INTERRUPTED. Exception → catch + log, tidak crash.

---

### 45.9 budget_json Schema (Resolusi M1)

```json
{"token":30000, "tool_call":30, "sub_agent":2, "parallel":4, "time_sec":600, "output_chars":500000}
```
Semua field optional (default: unlimited kecuali `tool_call` hard max 100). Parsing via `TaskBudget.parse(json)`.

---

### 45.10 Evidence FK Behavior (Resolusi M2)

Soft reference (tanpa FK constraint). Cleanup saat `deleteConversation()` atau load-time filter untuk ref yang dangling.

---

### 45.11 Multi-Task Rule (Resolusi M3)

Satu task aktif per conversation (v1). User bisa CANCEL lalu buat baru. Multi-task = v2.

---

### 45.12 Text Length Limits (Resolusi M4)

goal ≤ 4096, scope ≤ 2048, constraints ≤ 8192, completion_condition ≤ 4096, budget_json ≤ 1024, evidence summary ≤ 2048, event payload ≤ 2048. Enforce di `AgentTaskRepository.create()`.

---

### 45.13 Thread-Safety Field Mapping (Resolusi M5)

| Field | Treatment |
|-------|-----------|
| `task.status` | `synchronized` |
| `task.attempt_count` | `AtomicInteger` |
| `budget.tool_call_remaining` | `AtomicInteger` |
| `plan/current_step` | confined ke generation thread |
| `evidenceList` | `synchronized` |
| `TaskController.state` | main thread (post) |

---

### 45.14 Learning Trigger Path (Resolusi M6)

`TaskController.onTaskComplete()` → `MemoryExtractionService.extractFromTaskResult(goal, scope, verdict, evidence, ...)` → kirim ke model extraction menggunakan prompt template yang ada. VERIFIED → confidence lebih tinggi; FAILED → failure pattern class.

---

### 45.15 Capsule vs Compaction (Resolusi M7)

Capsule = invariant dari system prompt (`excludeFromCompaction`). Setelah compaction, capsule di-re-inject ke system prompt — mirip `MemoryPromptBuilder`. Test: setelah hard compaction, capsule goal masih ada.

---

### 45.16 Task ID Generation (Resolusi M8)

Format: `{conversationId}_t{counter}` (mirip `{convId}_m{n}`). Counter dari `SELECT MAX` + 1. Unik per conversation.

---

### 45.17 KeepAlive & Task

KeepAliveService dikelola oleh GenerationFlowController seperti biasa. TaskController tidak mempengaruhinya. Cancel task → cancel cancellationToken → KeepAliveService berhenti otomatis.

---

### 45.18 Cancellation Propagation

Parent task cancel → `AgentExecutionController.cancelRunningAgents()` → semua sub-agent future cancel → semua CANCELLED.

---

### 45.19 Task & Todo Relation

Task ≠ Todo. Task goal bisa di-sync ke todo list (opsional v1). Task completion → todo marked done. Independen secara default.

---

### 45.20 Performance & Cleanup

- 3 tabel + 5 index: overhead minimal (task creation jarang).
- Evidence hanya diload saat verdict dievaluasi.
- Event pruning: > 500/task atau > 7 hari dihapus.
- `TaskController.cleanup()` saat conversation switch (mirip `AgentResultRegistry.clearAll()`).

---

### 45.21 Negative Test Cases (Resolusi L5)

`TaskStateMachineTest` (transisi valid/invalid), `AgentTaskRepositoryTest` (reconcile stale/corrupt), `VerdictEngineTest` (downgrade/conflict/empty), `TaskBudgetTest` (exhaust/boundary/parse), `TaskControllerTest` (cancel mid-generation/blocked tasks).

---

### 45.22 Masalah Potensial Masa Depan

1. Conversation resume harus juga resume task state dari DB.
2. Task capsule tidak berubah image handling (`ImageInputPayload`).
3. Tool confirmation flow tidak berubah (selain mode override).
4. Capsule survival saat compaction harus di-test integration.
5. Error dari task harus juga dicatat di `ErrorLogController`.
6. Schema index naming harus konsisten dengan existing.
7. `TaskController.cleanup()` saat conversation switch mencegah memory leak.
8. Memory extraction berjalan seperti biasa — capsule di P0 tidak mengganggu extraction dari transcript.

---

### Ringkasan Resolusi

| ID | Status | § |
|----|--------|---|
| C1–C5 | ✅ Resolved | §45.1–45.5 |
| H1–H4 | ✅ Resolved | §45.6–45.8 + §35.3 fix |
| M1–M8 | ✅ Resolved | §45.9–45.16 |
| L1–L5 | ✅ Resolved | §45.20–45.21 |
| Future | ⚠️ Tracked | §45.22 |

---

## Lampiran A — Peta Koreksi Review → Bagian Dokumen

| Poin review | Bagian |
|-------------|--------|
| Verification profile-dependent (BUILD/test tak mungkin di LOCAL) | §13, §15, D01/D12 |
| TaskController membungkus GenerationFlowController, bukan loop paralel | §30.1, D01 |
| Skema `agent_tasks` diperluas (profile/policy/budget/verdict/attempt) | §35.1, D02 |
| Task Capsule P1 berbahaya (goal drift) → P0-region guidance | §6.1, §4.3, D03 |
| UI & i18n belum ada di spec → mekanisme kartu agent + R.string | §31 |
| Crash recovery task menggantung → INTERRUPTED + reconciliation | §30.4, D04 |
| `agent_events`/`agent_evidence` duplikasi data → task-level + referensi | §29, §35.2/35.3, D05 |
| Mode sebenarnya 4 (CHAT/PLAN/AGENT/CONTROL) | §26, D06 |
| Lifecycle memory CANDIDATE→VERIFIED → gate confidence existing (v1) | §16, D07 |
| Decision jangan jadi kontrak model baru → derived enum | §8, D10/D08 |
| Thread-safety | §30.3, D09 |
| Backward-compatible & opsional | §30.2, D10 |

---

## Lampiran B — Status Implementasi (P0–P4)

Seluruh prioritas P0–P4 telah diimplementasikan sebagai kode Java murni di atas modul
yang ada (tanpa framework baru, sesuai §46). Validasi: **504 unit test, 0 failures**
across `:core-model`, `:feature-model`, dan `:app`; kompilasi bersih untuk
`:app`, `:data`, `:feature-model`, `:core-model`.

### Peta Kelas per Modul

| Fase | Kelas | Modul | Spesifikasi |
|------|-------|-------|-------------|
| P0 | `TaskStatus`, `TaskMode`, `TaskVerdict`, `EvidenceLevel`, `ExecutionProfile`, `TaskRiskLevel`, `TaskVerificationPolicy`, `PolicyCategory` | `:core-model` | §5, §10, §12–§15, §26 |
| P0 | `TaskBudget`, `TaskCapsule`, `AgentTask`, `AgentTaskStore` (interface) | `:core-model` | §6, §26, §35.1, DIP |
| P0 | `TaskStateMachine` | `:feature-model` | §45.2 (tabel transisi) |
| P0 | `HarnessSchemaMigration` (DB v2: 3 tabel + 5 index), `AgentTaskRepository` | `:data` | §35, §45.1, §45.8 |
| P0 | `TaskController`, `TaskAwareGenerationHost` | `:app` (mvp.harness) | §30, §36–§37, D01/D09/D10 |
| P1 | `AgentEvidence` | `:core-model` | §35.2, D05 |
| P1 | `VerificationCapabilityMatrix` (downgrade + honest-verdict cap) | `:feature-model` | §13, D01/D12 |
| P1 | `DiffScopeChecker` (satu-satunya E2 on-device) | `:feature-model` | §14, aturan 3 §13 |
| P1 | `EvidenceRecorder` (summary + referensi, bukan salinan) | `:feature-model` | §35.2, D05 |
| P1 | `EvidenceClassifier` | `:feature-model` | §14 |
| P2 | `ContextPriority` (P0–P8 final), `ContextItem`, `ProjectSnapshot` | `:core-model` | §6.1, §7, §31 |
| P2 | `FreshnessEvaluator` (CACHE_HIT/STALE/REFRESH_REQUIRED, tanpa watcher) | `:feature-model` | §7, §32 |
| P2 | `ContextSelector` (SELECT/PRUNE; P0/P1 protected) | `:feature-model` | §6 |
| P3 | `MemoryCandidate` (8 MemoryClass + Source) | `:core-model` | §16, §18, D07 |
| P3 | `FailurePatternTracker` (≥3 kegagalan independen → kandidat) | `:feature-model` | §18 |
| P3 | `TaskLessonExtractor` (VERIFIED→WORKFLOW, PARTIALLY→PROJECT_FACT) | `:feature-model` | §18, §45.14/M6 |
| P4 | `SubAgentBudgetLedger` (per-task + session cap, hard cap 16) | `:feature-model` | §23, Invariant 8 |
| P4 | `PipelineStageVerifier` (gerbang evidence EXPLORE→PLAN→IMPLEMENT→VERIFY) | `:feature-model` | §24 |
| P4 | `AdaptiveResourceGovernor` (sliding-window, hanya menurunkan/memulihkan base) | `:feature-model` | §26 |

### Test Suite Harness (semua lulus)

| Test | Jumlah | Modul |
|------|--------|-------|
| `TaskStatusTest`, `TaskBudgetTest`, `ContextItemPriorityTest`, `ProjectSnapshotTest` | 38 | `:core-model` |
| `TaskStateMachineTest`, `VerdictEngineTest`, `DiffScopeCheckerTest`, `VerificationCapabilityMatrixTest`, `ContextSelectorTest`, `FreshnessEvaluatorTest`, `EvidenceRecorderTest` | 94 | `:feature-model` |
| `SubAgentBudgetLedgerTest`, `PipelineStageVerifierTest`, `AdaptiveResourceGovernorTest` | 31 | `:feature-model` |
| `TaskControllerTest` (lifecycle, recovery budget, learning, resume, reconcile) | 12 | `:app` |

### Keputusan Implementasi yang Menyimpang/Diperjelas dari Desain

1. **Recovery path**: `VERIFYING → PLANNING` tidak ada di tabel §45.2 (hanya `→ EXECUTING`);
   `handleRecovery` memakai `VERIFYING → EXECUTING` sesuai kontrak — tertangkap oleh test.
2. **Resume task** (`resumeActiveTask`) diimplementasikan lebih awal dari jadwal v2
   karena dibutuhkan test sub-agent lintas-kontrol; menjawab §45.22 item 1.
3. **`AgentTaskStore` interface** ditambahkan (core-model) agar `TaskController`/
   `EvidenceRecorder` ter-test di JVM dengan in-memory fake — konsisten prinsip DIP.
4. **Memory lifecycle v1 tetap gate-confidence** (D07); `FailurePatternTracker`/`TaskLessonExtractor`
   hanya *menghasilkan kandidat* — commit tetap lewat `MemoryExtractionService` yang ada.
5. **Belum ter-wire ke `MainCoordinator`/`MainDependencies`** — komponen siap pakai,
   integrasi UI/prompt injection (`SystemPromptProvider`) menyusul sebagai langkah wiring terpisah.
