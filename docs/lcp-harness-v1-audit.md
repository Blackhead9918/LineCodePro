# LCP-Harness v1 — Audit Pre-Implementasi

**Tanggal:** 9 Agustus 2026
**Target:** `docs/lcp-harness-v1.md` DRAFT v1.0
**Tujuan:** Mengidentifikasi gap, inkonsistensi, dan ambiguitas yang harus diselesaikan sebelum penulisan kode dimulai.

---

## Ringkasan Hasil

| Severitas | Jumlah | Keterangan |
|-----------|--------|------------|
| 🔴 CRITICAL | 5 | Blocks P0 — harus diselesaikan sebelum menulis kode |
| 🟠 HIGH | 4 | Harus diselesaikan selama P0 |
| 🟡 MEDIUM | 8 | Selesaikan saat P0/P1 |
| ⚪ LOW | 5 | Bisa diselesaikan iteratif |

---

## 🔴 CRITICAL — Blocks P0

### C1. Schema versioning tidak dijelaskan

**Masalah:** `LineCodeDatabase` saat ini singleton version 1. Menambahkan 3 tabel baru **wajib** bump ke version 2 + migration logic di `LineCodeSchema`. Dokumen menyebut "pola `CREATE TABLE IF NOT EXISTS` + auto-migration (pola `ensureModelConfigColumns`)" (§35), tetapi `ensureModelConfigColumns` dirancang khusus untuk `ALTER TABLE ADD COLUMN` — bukan untuk membuat tabel baru. Membuat tabel baru membutuhkan migrasi tipe berbeda (tulis `CREATE TABLE` di dalam migration step).

**Dampak:** Jika tidak di-spec sekarang, implementasi `LineCodeSchema` akan ambiguous. Migration yang salah bisa corrupt DB user.

**Rekomendasi:**
1. Tambahkan spesifikasi: `LineCodeDatabase` → `DB_VERSION = 2`.
2. Di `LineCodeSchema.version2Migration(SQLiteDatabase)`: eksekusi `CREATE TABLE IF NOT EXISTS agent_tasks/agent_evidence/agent_events`.
3. `ensureModelConfigColumns` tetap untuk kolom tambahan di tabel existing; migrasi tabel baru dilakukan terpisah.
4. Test: `AgentTaskSchemaTest` — verifikasi tabel ada setelah migration, kolom sesuai, index aktif.

---

### C2. State transition table tidak ada

**Masalah:** Status task didefinisikan (CREATED, PLANNING, EXECUTING, VERIFYING, BLOCKED, WAITING_USER, COMPLETED, FAILED, CANCELLED, INTERRUPTED), tetapi **tidak ada tabel transisi valid**. Tanpa ini, `TaskStateMachine` (pure Java di `:feature-model`) tidak bisa diimplementasikan dengan benar. Transisi invalid harus ditolak — tapi "invalid" apa?

**Dampak:** Setiap developer akan membuat asumsi berbeda. Implementasi yang inkonsisten akan menyebabkan bug lifecycle.

**Rekomendasi — tabel transisi final v1:**

```
CREATED → PLANNING | CANCELLED | FAILED
PLANNING → EXECUTING | CANCELLED | FAILED | BLOCKED
EXECUTING → VERIFYING | BLOCKED | WAITING_USER | COMPLETED | FAILED | CANCELLED
VERIFYING → COMPLETED | FAILED | EXECUTING (replan) | BLOCKED
BLOCKED → PLANNING (retry) | EXECUTING (retry) | CANCELLED | FAILED
WAITING_USER → EXECUTING (user respond) | CANCELLED | FAILED
COMPLETED → (terminal, no transition)
FAILED → (terminal, no transition)
CANCELLED → (terminal, no transition)
INTERRUPTED → FAILED (reconciliation) | CANCELLED (cleanup)
```

Aturan tambahan:
- `COMPLETED`/`FAILED`/`CANCELLED` = terminal state (no transition out).
- `INTERRUPTED` hanya ditetapkan oleh reconciliation (bukan oleh loop normal) → hanya bisa → `FAILED` atau `CANCELLED`.
- Setiap transisi yang tidak ada di tabel → throw `InvalidTransitionException` (untuk testing & logging).

---

### C3. Completion condition evaluator tidak didefinisikan

**Masalah:** `completion_condition TEXT` disimpan di `agent_tasks`, tetapi tidak dijelaskan BAGAIMANA kondisi ini dievaluasi. Apakah free-text yang dibaca model? Atau query terstruktur (mis. `{"build":"success","test":"pass","diff_scope":"matches"}`) yang diparse oleh `VerdictEngine`?

**Dampak:** Tanpa evaluator, tidak ada cara menentukan apakah task selesai — loop tidak pernah berhenti atau berhenti terlalu cepat.

**Rekomendasi:**
1. Di v1: `completion_condition` = **deskripsi free-text** untuk model (context). Evaluasi **model-judged** (E0) + **deterministic guards** (E2) yang terpisah.
2. `VerdictEngine` menerima: (a) task completion_condition (free-text, dikirim ke model sebagai guidance), (b) evidence collection terkini, (c) execution_profile. Menghasilkan verdict: `VERIFIED | PARTIALLY_VERIFIED | UNVERIFIED | FAILED | BLOCKED`.
3. Model loop sudah "tahu kapan selesai" (model memilih untuk stop tanpa tool call → ANSWER) — verifikasi kemudian memvalidasi apakah klaim itu benar.
4. `failure_condition` dievaluasi sama: model-dikomunikasikan, verdict menentukan.
5. Tambahan: TaskController menerima callback dari `GenerationFlowController.Host.onGenerationComplete()` sebagai sinyal "model selesai" → waktu evaluasi verdict.

---

### C4. Titik integrasi ke GenerationFlowController tidak spesifik

**Masalah:** §30.1 menyatakan "TaskController memanggil ChatInteractionController.dispatchMessage(...)" dan "setelah tiap iterasi selesai" mengevaluasi verdict. Tetapi:

1. `GenerationFlowController` berjalan **asinkron** (di ExecutorService background thread). Tidak ada "setelah tiap iterasi selesai" yang bisa dipanggil secara sinkron dari TaskController.
2. Kontrol sebenarnya berjalan lewat interface **`GenerationFlowController.Host`** (callbacks: `onGenerationStart`, `onModelResponse`, `onToolResult`, `onGenerationComplete`, `onError`). Ini adalah titik kontrol sebenarnya.
3. TaskController perlu menerima `Host` callback untuk: (a) mengecek budget setelah setiap model response, (b) mengumpulkan evidence setelah tool execution, (c) menentukan apakah loop perlu berlanjut.

**Dampak:** Tanpa spesifikasi hook point, TaskController akan ditempatkan di tempat yang salah atau menduplikasi logika control flow.

**Rekomendasi:**
1. TaskController menerima Host callback sebagai **decorator/wrapper**:
   ```
   class TaskAwareHost implements GenerationFlowController.Host {
       GenerationFlowController.Host delegate;
       TaskController taskController;
       
       onModelResponse(response) {
           taskController.onIterationComplete(response);
           delegate.onModelResponse(response);
       }
       onError(error) {
           taskController.onError(error);
           delegate.onError(error);
       }
       // ... delegates all callbacks, injecting task awareness
   }
   ```
2. `ChatInteractionController.dispatchMessage` dipanggil seperti biasa, tetapi dengan `TaskAwareHost` sebagai Host.
3. TaskController tidak mengontrol loop — ia **mengamati** loop lewat Host callbacks dan mengambil tindakan (budget check, evidence collect, state update) pada titik-titik yang tepat.
4. Untuk menghentikan loop (task FAILED/CANCELLED): TaskController mengembalikan `false` dari callback (jika Host mengizinkan abort) atau menggunakan `cancellationToken`.

---

### C5. Task mode vs ChatMode: konflik tidak diselesaikan

**Masalah:** Task memiliki field `mode` (CHAT|PLAN|AGENT|CONTROL) dan conversation memiliki `ChatMode` dari `ChatModeRepository.applyMode()`. Jika task diklaim AGENT mode tapi conversation dalam CHAT mode, apa yang terjadi?

- Apakah TaskController otomatis switch ChatMode ke AGENT?
- Atau TaskController menolak membuat task jika mode tidak match?
- Atau mode task adalah independen dari ChatMode?

**Dampak:** Jika tidak diselesaikan, TaskController dan mode system akan berkonflik, menyebabkan permission flow yang tidak konsisten.

**Rekomendasi:**
1. Task `mode` **meng-override** ChatMode selama task aktif. TaskController memanggil `ChatModeRepository.applyMode(taskMode)` saat task dimulai (dengan `applyPermissionForMode()` yang menyertai).
2. Saat task selesai/dibatalkan, TaskController mengembalikan ChatMode ke CHAT (default).
3. Ini memastikan permission flow (`ToolPermissionService`, `PermissionModeController`) konsisten dengan task.
4. Task tidak bisa dibuat dengan mode yang bertentangan dengan mode conversation saat ini tanpa konfirmasi eksplisit user.

---

## 🟠 HIGH — Selesaikan dalam P0

### H1. Recovery budget overlap: RetryPolicy vs max_attempts

**Masalah:** `RetryPolicy` sudah memberikan 3 retry per generation di dalam `GenerationFlowController.handleModelError()`. Task `max_attempts = 3` berarti: apakah ini 3 × 3 = 9 model calls? Atau `max_attempts` = jumlah model calls total (bukan retry)?

**Rekomendasi:**
1. `max_attempts` = jumlah **generation attempts total** (bukan retry per generation).
2. TaskController menghitung setiap pemanggilan `model.decide()` sebagai 1 attempt.
3. RetryPolicy di dalam GenerationFlowController tetap berjalan (max 3 per generation), tetapi计数 ke `attempt_count` task juga.
4. Jika `attempt_count >= max_attempts` → FAILED, tanpa retry lagi.
5. Contoh: `max_attempts=3`; generasi pertama → retry 2× (masing-masing attempt dihitung) → mencapai 3 → FAILED.

### H2. Task creation trigger tidak didefinisikan

**Masalah:** §44 bertanya tapi tidak menjawab. Untuk P0, ini harus diputuskan.

**Rekomendasi — untuk P0:**
1. Task hanya dibuat **secara implisit** saat mode AGENT aktif dan user mengirim pesan.
2. Setiap pesan user di mode AGENT → `TaskController.onUserIntent(goal=userMessage)` → buat task baru (atau lanjutkan task aktif jika ada).
3. Tidak perlu perintah `/task` eksplisit di v1.
4. Mode CHAT/PLAN/CONTROL → tidak ada task (backward compatible, D10).

### H3. agent_events.task_id nullable tapi deskripsi bilang task-level saja

**Masalah:** DDL §35.3 mendefinisikan `task_id TEXT` (nullable), tetapi §29 menyatakan "`agent_events` hanya untuk event lifecycle **task-level**." Jika hanya task-level, task_id seharusnya `NOT NULL`.

**Rekomendasi:** Ubah DDL:
```sql
task_id TEXT NOT NULL,   -- event task-level: wajib ada task
```
Tambahkan foreign key jika memungkinkan:
```sql
FOREIGN KEY (task_id) REFERENCES agent_tasks(id) ON DELETE CASCADE
```

### H4. Reconciliation timing & async

**Masalah:** §30.4 bilang "saat app start (MainControllerInitializer)" tapi tidak menjelaskan: (a) sync atau async? (b) Jika sync, blocks UI startup (buruk). (c) Jika async, task states sementara inaccurate. (d) Jika reconciliation gagal (DB corrupt)?

**Rekomendasi:**
1. Reconciliation = **async** (ExecutorService background, yang sama dengan service startup yang lain).
2. `AgentTaskRepository.reconcileStaleTasks()` dijalankan di background thread saat app start.
3. Task status sementara (selama reconciliation) = treat sebagai belum loaded (TaskController tidak akan menampilkan task sampai reconcile selesai).
4. Jika reconciliation gagal → catch exception, log error, tidak crash; task menggantung di status lama (benar, lebih baik dari crash).
5. Threshold "lama": `updated_at` lebih dari 5 menit lama dan status bukan terminal → INTERRUPTED.

---

## 🟡 MEDIUM — Selesaikan saat P0/P1

### M1. budget_json schema tidak didefinisikan

**Masalah:** Kolom `budget_json TEXT` menyimpan JSON tanpa formal schema. Apakah semua field opsional? Apa default untuk field yang tidak ada? Apa batas maksimal tiap field?

**Rekomendasi — formal schema:**
```json
{
  "token": 30000,         // optional, default: tidak ada (unlimited)
  "tool_call": 30,        // optional, default: 30
  "sub_agent": 2,         // optional, default: 2
  "parallel": 4,          // optional, default: 4 (maks thread pool)
  "time_sec": 600,        // optional, default: tidak ada (unlimited)
  "output_chars": 500000  // optional, default: 500000
}
```
Field yang tidak ada = unlimited (kecuali `tool_call`, `parallel` yang punya hard limit dari infrastruktur).

### M2. agent_evidence foreign key behavior

**Masalah:** `ref_table`/`ref_id` mereferensikan tabel lain (tool_results, diff_records) tanpa ON DELETE behavior. Ketika conversation dihapus (yang cascades ke messages → tool_calls → tool_results), evidence rows dengan ref yang dangling akan tercipta.

**Rekomendasi:**
1. `ref_table`/`ref_id` = soft reference (tanpa FK constraint) — pola yang sudah ada di codebase (relation cross-table tidak di-enforce di SQLite).
2. Cleanup: `AgentTaskRepository.cleanupOrphanedEvidence()` dipanggil saat conversation deletion (di `ConversationRepository.deleteConversation` atau `StorageMaintenanceController`).
3. Atau: saat load evidence, filter yang ref-nya tidak valid → treat sebagai "reference unavailable" (log warning, tampilkan ringkasan saja).

### M3. Multi-task saat BLOCKED/WAITING_USER

**Masalah:** §44 bilang "multi-task paralel dilarang." Tetapi: apakah task baru boleh dibuat saat task lama BLOCKED/WAITING_USER? Atau harus diselesaikan/dibatalkan dulu?

**Rekomendasi:**
1. Di v1: **satu task aktif per conversation**. Task baru tidak bisa dibuat sampai task lama COMPLETED/FAILED/CANCELLED.
2. BLOCKED/WAITING_USER = task masih aktif; user bisa input (tapi input itu untuk task aktif, bukan task baru).
3. User bisa CANCEL task aktif lalu buat task baru.
4. Multi-task per conversation = v2 (dengan task switcher UI).

### M4. Text length limits tidak ada

**Masalah:** `goal TEXT NOT NULL`, `scope TEXT`, `constraints TEXT` — tidak ada batas. Model bisa menghasilkan goal yang sangat panjang.

**Rekomendasi:**
| Field | Max chars | Reasoning |
|-------|-----------|-----------|
| `goal` | 4096 | cukup untuk deskripsi task; model-generated |
| `scope` | 2048 | path + deskripsi scope |
| `constraints` | 8192 | JSON array; bisa panjang tapi batasi |
| `completion_condition` | 4096 | free-text guidance |
| `failure_condition` | 2048 | free-text |
| `budget_json` | 1024 | JSON kecil |

Enforce di `AgentTaskRepository.create()` — truncate atau reject jika melebihi.

### M5. Thread-safety: mapping field spesifik

**Masalah:** §30.3 memberikan prinsip umum tapi tidak memetakan field mana yang perlu treatment apa.

**Rekomendasi:**
| Field/Structure | Thread Safety | Pattern |
|----------------|---------------|---------|
| `task.status` | synchronized (read/write lintas thread) | `PipelineProgressSession` pattern |
| `task.attempt_count` | `AtomicInteger` | `toolCallBudget` pattern |
| `task.budget tool_call` | `AtomicInteger` | sama |
| `TaskSession.plan/current_step` | confined ke generation thread | tidak perlu sync |
| `EvidenceCollector.evidenceList` | `synchronized` (add dari berbagai callback) | `PipelineProgressSession` pattern |
| `TaskController.state` | Main thread only (UI update) | `post(Runnable)` pattern |

### M6. Learning trigger path tidak didefinisikan

**Masalah:** §18 menyebut "TASK RESULT → EXTRACT LESSON → ..." tetapi path kode mana yang memicu ini tidak dijelaskan. Apakah `TaskController.onTaskComplete()` memanggil `MemoryExtractionService`? Atau ada komponen baru?

**Rekomendasi:**
1. Di v1: `TaskController.onTaskComplete(task, verdict)` memanggil `LearningContextService` (atau metode baru di `MemoryExtractionService`) untuk extract lesson dari task result + evidence.
2. Lesson dikirim sebagai "user message" ke model extraction, bukan sebagai tool call.
3. Jika verdict != VERIFIED → lesson lebih berhati-hati (lower confidence).
4. Jika verdict == VERIFIED → lesson dengan confidence lebih tinggi.

### M7. Task capsule vs context compaction

**Masalah:** Capsule diinjeksi via `SystemPromptProvider` (region P0). Ketika compaction hard 80% berjalan, system prompt JUGA bisa terpengaruh (compaction bisa membuang bagian system prompt). Jika capsule terbuang, task awareness hilang di tengah task.

**Rekomendasi:**
1. Capsule harus diperlakukan sebagai **bagian invariant dari system prompt** (tidak boleh di-compacted). Ini berarti: komponen capsule tidak boleh menjadi bagian dari transcript yang di-summarize.
2. Implementasi: capsule diinject ke setiap compaction output (re-add capsule ke system prompt setelah compaction), mirip bagaimana memory/learning context hari ini ditambahkan ulang.
3. Perlu verifikasi di `ContextCompactionService`: setelah compaction, capsule masih hadir di context. Ini cocok dengan pola yang ada (context terdiri dari `[base(excludeFromCompaction)] + [recent + summary + preserved]`).

### M8. Task ID generation strategy

**Masalah:** Tidak dijelaskan bagaimana task ID dibuat. Pola ID conversation saat ini: `{conversationId}_m{n}`. Task ID harus konsisten, unik, dan tidak bertabrak.

**Rekomendasi:**
1. Format: `{conversationId}_t{counter}` (mirip message pattern).
2. Counter = auto-increment per conversation (dari DB: `SELECT MAX(id) FROM agent_tasks WHERE conversation_id = ?`).
3. ID unik per conversation; global uniqueness tidak diperlukan (sama seperti message ID).

---

## ⚪ LOW — Bisa diselesaikan iteratif

### L1. Penamaan index

**Masalah:** DDL menggunakan prefix `idx_` untuk index names. Perlu dicek konsistensi dengan naming convention index yang sudah ada di `LineCodeSchema`.

**Rekomendasi:** Ikuti pola yang sudah ada. Jika tidak ada pola yang konsisten, gunakan `idx_{table}_{column}` (yang sudah dipakai di DDL).

### L2. Data retention & cleanup

**Masalah:** Tidak ada strategi untuk cleanup task lama. Completed/failed tasks persist selamanya → DB size meningkat.

**Rekomendasi:** Di v1: tidak ada cleanup otomatis. Manual cleanup di `StorageMaintenanceController` (sudah ada pattern untuk ini). v2: TTL per task (berdasarkan completed_at).

### L3. Cross-task state

**Masalah:** Task 2 yang beroperasi di file yang sama dengan Task 1 — apakah Task 2 melihat perubahan dari Task 1? Saat ini, setiap task mulai dengan context fresh.

**Rekomendasi:** Di v1: setiap task fresh. Cross-task state = v2. TaskCapsule scope bisa mencakup "task sebelumnya" sebagai reference jika diperlukan.

### L4. project_id source & multi-workspace

**Masalah:** Task `project_id` — dari mana nilainya? Jika ada beberapa workspace aktif, mana yang dipilih?

**Rekomendasi:** project_id = active workspace primary. Jika multi-workspace, pilih workspace yang paling relevan dengan scope task (heuristic: path scope mengandung workspace root). Di v1: satu project per task.

### L5. Negative test cases tidak disebut di testing strategy

**Masalah:** §33 menyebut test untuk happy paths. Tidak ada mention untuk: invalid state transition, DB corruption, concurrent access, budget overflow, reconciliation edge cases.

**Rekomendasi:** Tambahkan ke testing strategy:
- `TaskStateMachineTest`: semua transisi invalid → throw exception.
- `AgentTaskRepositoryTest`: reconcile dengan status corrupt, concurrent writes.
- `VerdictEngineTest`: downgrade profile, evidence conflict, empty evidence.
- `TaskBudgetTest`: boundary cases (exact 0 left, overflow).

---

## Peta Temuan ke Bagian Dokumen

| ID | Temuan | Bagian terkait | Tindakan |
|----|--------|----------------|----------|
| C1 | Schema versioning | §35 | Tambah spesifikasi DB_VERSION=2 + migration |
| C2 | State transition table | §36, §4.2 | Buat tabel transisi eksplisit |
| C3 | Completion evaluator | §37, §4.2 | Definisikan model-judged + deterministic guard |
| C4 | GenerationFlowController hook | §30.1 | Definisikan Host wrapper pattern |
| C5 | Task mode vs ChatMode | §26, §30.1 | Definisikan override behavior |
| H1 | Recovery budget overlap | §22, §23 | Definisikan hitungan attempt |
| H2 | Task creation trigger | §44 | Putuskan untuk v1: implicit di AGENT mode |
| H3 | agent_events DDL nullable | §35.3 | Ubah task_id NOT NULL |
| H4 | Reconciliation async | §30.4 | Definisikan async + threshold |
| M1 | budget_json schema | §35.1 | Definisikan JSON schema formal |
| M2 | FK behavior evidence | §35.2 | Soft reference + cleanup |
| M3 | Multi-task BLOCKED | §44 | Satu task aktif per conversation |
| M4 | Text length limits | §35 | Definisikan batas per field |
| M5 | Thread-safety mapping | §30.3 | Buat tabel field→treatment |
| M6 | Learning trigger path | §18 | Hubungkan ke MemoryExtractionService |
| M7 | Capsule vs compaction | §8, §30.1 | Capsule = invariant, re-add post-compaction |
| M8 | Task ID generation | §4.2 | Format `{convId}_t{counter}` |
| L1 | Index naming | §35 | Ikuti pola existing |
| L2 | Data retention | — | Manual cleanup v1; TTL v2 |
| L3 | Cross-task state | §5 | Fresh per task; v2 |
| L4 | project_id source | §35.1 | Active workspace |
| L5 | Negative test cases | §33 | Tambah ke testing strategy |

---

## Rekomendasi Implementasi Berdasarkan Audit

Sebelum menulis satu baris kode pun, selesaikan:

1. **Tabel transisi state** (C2) — ini adalah kontrak utama `TaskStateMachine`.
2. **Spesifikasi Host wrapper** (C4) — ini menentukan arsitektur `TaskController`.
3. **DB_VERSION + migration** (C1) — ini menentukan bagaimana `LineCodeSchema` diubah.
4. **Task mode vs ChatMode** (C5) — ini menentukan permission flow.
5. **Completion evaluator** (C3) — ini menentukan bagaimana verdict dihitung.

Lima hal ini harus ditambahkan ke `docs/lcp-harness-v1.md` sebelum P0 dimulai.
