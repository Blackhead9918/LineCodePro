# LineCode Pro — Hasil Analisis Kode & Rencana Perbaikan

Dokumen ini mencatat temuan analisis kode (bug / kesalahan logika), status perbaikan, serta
saran peningkatan dan fitur baru. Tanggal analisis: Agustus 2026.

---

## 🔴 Bug Kritis

### #1 — `ToolExecutor.injectDependencies()` membuang field penting ToolContext  — ✅ DIPERBAIKI

**Lokasi:** `feature-tool/.../ToolExecutor.java` → `injectDependencies()`

**Masalah:** `GenerationFlowController.toolContext()` sengaja membangun `ToolContext` tanpa
`toolSettingsStore` / `modelRepository` / `modelServiceProvider` / `learningContextStore`
(berharap diisi oleh ToolExecutor). Akibatnya `needsInjection()` selalu `true` untuk **setiap**
eksekusi tool di loop utama, sehingga `injectDependencies()` selalu dipanggil — dan method ini
membangun ulang `ToolContext` dari nol **tanpa menyalin** `progressListener`, `agentResultStore`,
`stringResolver`, dan `appContext`.

**Dampak:**
- `ShellExecuteTool` (streaming output), `ImageGenerationTool`, `ImageUnderstandingTool`,
  `ImageResponseParser` → progress live ke UI **mati total** (silent no-op).
- `AgentOutputTool` → `getAgentResultStore()` selalu `null` → fitur `agent_output` (v1.2.5) rusak.
- `context.getString(R.string.*)` di tool → string kosong.

**Perbaikan yang diterapkan:**
- `ToolContext` menambah getter `getProgressListener()` dan `getStringResolver()`.
- `injectDependencies()` kini menyalin `progressListener`, `appContext`, `stringResolver`, dan
  `agentResultStore` dari konteks asli, sambil tetap mengisi dependensi yang null.

---

### #2 — `StringResolver`/`appContext` tidak pernah di-wire di produksi  — ✅ DIPERBAIKI

**Lokasi:** `ToolContext.getString()` vs seluruh pemanggil `ToolContext.builder()` di produksi.

**Masalah:** Tidak ada kode produksi (non-test) yang memanggil `.stringResolver(...)` atau
`.appContext(...)`. `AgentExecutionController.setContext()` juga **tidak pernah dipanggil** dari
`MainControllerInitializer`. Akibatnya `context.getString(R.string.*)` selalu `""` saat runtime —
semua pesan error tool yang dilokalisasi menjadi kosong.

**Perbaikan yang diterapkan:**
- `MainControllerInitializer` kini memasang `StringResolver` (berbasis `Context`) ke
  `GenerationFlowController.setStringResolver(...)`.
- `GenerationFlowController.toolContext()` menambahkan `.stringResolver(stringResolver)`.
- `AgentExecutionController` mendapat `stringResolver()` internal dan `executeAgentToolCall()`
  menambahkan `.stringResolver(...)` ke ToolContext sub-agent.
- `MainControllerInitializer` kini memanggil `agentExecutionController.setContext(context)` sehingga
  helper `string(R.string..., fallback)` dan `AgentPromptBuilder` ikut terisi.

---

## 🟠 Bug Sedang (belum diperbaiki)

### #3 — String user-visible bahasa Cina hardcoded (regresi i18n)
±1.129 baris mengandung CJK di file Java; sebagian adalah string user/model-visible:
`GenerationFlowController` (`"模型没有返回文本。"`, `"执行失败: "`, `"未知错误"`),
`AgentExecutionController` (`"Agent 工具调用为空"`, `"explore Agent 不允许写入文件。"`),
`ToolExecutor` (`"工具调用为空"`, `"参数解析失败: "`, ...), `ChatInteractionController`,
`ChatUiStateAssembler`, `ContextCompactionController`, `LineCodeArchiveService`,
`SshFileTreeRepository`, `SkillRepository`, `SkillFileManager`, `tool-ui`
(`AgentPipelineSummaryParser`, `AgentToolResultDisplay`), dll.
**Rencana:** migrasi ke `R.string.*` + CI check grep CJK.

### #4 — Context sub-agent terlalu minim  — ✅ DIPERBAIKI (parsial)
`AgentExecutionController.executeAgentToolCall()` sebelumnya hanya mengisi homePath/extraWriteRoots/
toolCallId/bypassPathProtection — tanpa `agentResultStore`, sehingga `agent_output` nested di dalam
agent selalu null. **Perbaikan:** `executeAgentToolCall()` kini memasang `.agentResultStore(agentResultRegistry)`
(bertahan setelah `injectDependencies` berkat fix #1). Streaming progress live di dalam agent tetap
memakai `AgentProgressSession` (mekanisme card progress agent) — tidak diubah.

---

## 🟡 Temuan Minor / Risiko

1. **Logika retry** (`GenerationFlowController.handleModelError`) — ✅ DIPERBAIKI:
   - `retryAttemptAfterFailure()`: attempt 1-based, benar-benar 3 retry (sebelumnya hanya 2 karena
     `nextAttempt >= MAX_RETRIES`), fail setelah 3 retry (bukan loop selamanya).
   - Label retry benar: "Retry 1/3" (sebelumnya "Retry 2/3" di retry pertama).
   - `RetryPolicy` (feature-model, pure + unit-tested): backoff eksponensial 2s→4s→8s→16s (cap)
     + jitter ±500ms (sebelumnya delay tetap 5s).
   - **Skip retry error permanen**: `ModelCompletionException` kini membawa `statusCode` (diisi dari
     HTTP layer di `AbstractHttpModelProtocol`); 4xx kecuali 408/429 langsung gagal tanpa retry sia-sia.
   - Test: `RetryPolicyTest` (feature-model, 7 metode) + `GenerationFlowControllerTest`
     (`retryAttemptsStartAtOneAndStopAfterMaxRetries`).
2. **`MemoryExtractionService`** menyuntikkan memori seed Cina hardcoded
   (mis. `"当前项目不能使用 AndroidX。"`) ke DB — menjadi "memori" permanen tanpa persetujuan.
   — ✅ DIPERBAIKI: blok khusus AndroidX di `ruleBasedCandidates()` yang membuat konten
   fabrikasi (bukan kata-kata user) dihapus; ekstraksi kini selalu menyimpan kalimat asli user
   verbatim (loop kalimat generik sudah menangkap kendala AndroidX dengan scope proyek).
   Regresi test: `MemoryExtractionServiceTest` memastikan konten = kalimat asli user dan
   transkrip saja tidak memproduksi seed.
3. **Duplikasi logika prompt** — ✅ DIPERBAIKI: `SkillPromptBuilder` (app/src, 145 baris) adalah
   dead code tanpa pemanggil — dihapus; implementasi aktif `SkillRepository.buildExtensionPrompt()`
   (data layer) tetap dipakai. Komentar `SkillPromptProvider` diselaraskan.
4. Banyak `catch (Exception ignored)` — beberapa sah, beberapa menyembunyikan error nyata.
   — ✅ DI-AUDIT (sebagian):
   - `MemoryExtractionService` (4 catch) — ekstraksi memori/skill model gagal + parse kandidat
     kini mencatat `Log.w(TAG, ..., e)` tanpa mengubah alur.
   - `ConversationResumeSanitizer` (4 catch) — semua best-effort yang SAH (pesan tool sering
     teks biasa non-JSON; rawJson rusak tidak boleh menggagalkan resume); intent ditandai komentar.
   - `ToolCallTextParser` (3 catch) — semua SAH (parser model output harus resilien; kegagalan
     parse = "tidak ada tool call", teks dipertahankan); intent ditandai javadoc kelas + komentar.
   - `SshCommandExecutor` (4 catch) — semua SAH (polling `getExitStatus` sebelum konek, cleanup
     `disconnect`, drain thread best-effort yang tidak memengaruhi exit status); intent ditandai komentar.
   - `ArchiveSecretRedactor` (5 catch) — semua SAH; jalur redaksi kebanyakan **fail-closed**
     (header/JSON tak terparse → diekspor kosong, bukan bocor). Satu-satunya fail-open (snapshot
     copy di `performRedactDatabaseSnapshot`) praktis tak terjangkau karena `JSONObject.toString()`
     selalu bisa diparse ulang; intent ditandai komentar.
   - `McpExtensionRepository` (4 catch) — semua SAH (toleransi DB rusak: headers/tools JSON korup
     tidak boleh crash saat startup; inputSchema rusak di-skip tapi tool tetap tersimpan); ditandai komentar.
   - `ToolPromptRenderer` (3 catch) — semua SAH (**fail-soft**: satu tool yang `getParameters()`
     error menurun ke `{}`, prompt tetap terbangun utuh); ditandai komentar.
   - `AgentExecutionController` (3 catch) — semua SAH: `isBypassPathProtection` **fail-safe** (error
     supplier → default tidak bypass proteksi); snapshot progress agent best-effort (hanya progress
     card JSON yang hilang, fullOutput tetap terekam); payload pipeline non-JSON default "running".
   - `MessageContentSanitizer` (3 catch) — semua SAH (fail-soft: konten non-JSON fallback ke teks/
     pesan generik, inline image tetap di-strip); ditandai komentar.
   - `MessageRecord` (3 catch) — semua SAH (toleransi DB rusak: rawJson korup → tidak crash saat
     resume, diperlakukan kosong); ditandai komentar.
   - Sisa (jumlah kecil, pola identik best-effort yang sudah terverifikasi di 9 file di atas):
     `GenerationLifecycleController` (2), `GenerationFlowController` (2), `SshConfig` (2),
     `SkillRepository` (2), `ThemeSettingsRepository` (2), `ToolMessageController` (2), dll.
5. **Memory leak `AgentResultRegistry`** — ✅ DIPERBAIKI: `clearGeneration()` tidak pernah dipanggil;
   records + fullOutput agent menumpuk sepanjang sesi app. Ditambahkan `clearAll()` yang dipanggil di
   semua titik pergantian percakapan (newConversation, deleteConversation, clearCurrentConversation,
   loadConversation, clearChatHistory via StorageMaintenanceController, reloadAfterLineCodeImport)
   — sejajar dengan `TokenUsageTracker.reset()`.
6. **`ToolExecutionScheduler` tidak membatalkan future saat cancel** — ✅ DIPERBAIKI: saat
   `cancellationToken` dibatalkan di tengah batch eksekusi paralel, future yang masih berjalan
   kini di-`cancel(true)` (pola `cancelRemainingFutures`, sama seperti di pipeline agent), mencegah
   tool blocking memenuhi thread pool 4 thread dan menyumbat tool berikutnya.

---

## 💡 Saran Perbaikan (Improvement)

1. ~~Perbaiki #1 & #2~~ ✅ (selesai) — tambah unit test regresi (`ToolExecutorTest`).
2. ~~Testing lokal~~ ⚠️ Lingkungan sandbox terlalu kecil untuk build Gradle (timeout saat
   `:build-logic:compileKotlin`). Sebagai gantinya ditambahkan workflow GitHub Actions
   `.github/workflows/test.yml` (unit test semua modul, JDK 21 + Android SDK 36). Commit/push
   hanya dilakukan saat diminta eksplisit.
2. **Unit test ToolContext utuh** setelah `execute()` — cegah regresi "context dibangun ulang".
3. **Retry eksponensial + jitter** (500ms→2s→5s + random) & perbaiki penghitungan attempt.
4. **Factory `AppToolContextFactory`** tunggal untuk membangun ToolContext lengkap (dipakai
   GenerationFlowController, AgentExecutionController, ToolExecutor) — hilangkan divergensi.
5. **CI check**: grep CJK di Java non-komentar + verifikasi setiap `R.string` tersedia di
   en/zh/ru (tool-ui/feature-tool punya string ru lebih sedikit: 55 vs 228).

---

## 🚀 Saran Fitur Baru

1. **Tool Git bawaan** (`git_status`, `git_diff`, `git_log`, `git_commit`, `git_push`) — ✅ SELESAI:
   - `GitTool` (feature-tool, 1 kelas + 5 instance bernama) — `git_status`/`git_diff`/`git_log`
     read-only tanpa konfirmasi (kategori READ, boleh di readonly mode); `git_commit`/`git_push`
     butuh konfirmasi (kategori WRITE, diblokir di readonly mode via `ToolPermissionService` otomatis).
   - Eksekusi didelegasikan ke `ShellExecuteTool` internal → routing SSH / terminal provider (IPC)
     yang sama dengan `shell_execute`; command selalu berjalan di workspace root
     (`ToolContext.getHomePath()`); cwd bisa dioverride per panggilan.
   - `buildCommand()` pure static (unit-testable tanpa Android): `git status --short --branch`,
     `git diff --no-ext-diff --no-color [--cached] [--stat] [-- path]`,
     `git log --oneline --no-color [--max-count=N] [-- path]`,
     `git add [paths| -A] && git commit [-m msg|--allow-empty]`, `git push [remote] [branch]`;
     semua argumen di-shell-quote (single-quote, escape `'`).
   - Registrasi: `BuiltInToolProviders` (5 instance) + `ToolNames.GIT_*` (core-api) +
     group `git` (MODE_REMOTE) di `ToolSettingsRepository.buildDefaultConfigs()` + kartu
     `ToolCallShellView` (fallback command kosong kini menampilkan nama tool asli).
   - Test: `GitToolTest` (konstruksi command, validasi message kosong, flag izin, kategori,
     nama stabil).
2. **Tampilkan statistik token** per percakapan dari `TokenUsageTracker` (data sudah ada).
2. **Tampilkan statistik token** per percakapan dari `TokenUsageTracker` (data sudah ada).
3. **UI Diff & rollback interaktif** per tool call (`DiffRepository`/`FileRestorer` sudah ada).
4. **Eksekusi agent paralel** yang aman (infrastruktur `AgentResultRegistry` sudah mendukung).
5. **Pencarian percakapan dengan highlight** (`conversation_index` FTS sudah ada).
6. **Multi-workspace tabs** (beberapa root proyek sekaligus).
7. **Template proyek quick-start** (empty/Android/Node/Python).
8. **Ekspor transkrip Markdown/PDF** lebih baik (`feature-share` sudah ada).
9. **Localization Bahasa Indonesia** (pola en/zh/ru sudah siap).
10. **Auto-draft pesan** & auto-scroll pintar saat streaming panjang.

---

# Audit #2 — Code & Logic Bugs (Oktober 2026)

> Bagian ini ditulis dalam bahasa Inggris (dokumentasi teknis utama repo memakai bahasa
> Inggris). Temuan di atas dipertahankan sebagai riwayat.

Scope: static review of the runtime-critical paths — tool execution loop, sub-agent
authorization, context compaction, HTTP policy enforcement, and the data/archive layer.
Environment note: the audit sandbox has no JDK / Android SDK, so nothing here was
compiled or executed. Findings were validated by reading the code and the existing unit
tests; run the gates below before shipping.

---

## 🔴 Critical

### C1 — `explore` agents lose their read-only guarantee in SSH / IPC mode — ✅ FIXED

**Where:** `app/.../mvp/agent/AgentExecutionController.java` → `isAgentToolAllowed()`.

**Problem:** the method returned `true` as soon as `isRemoteExecutionMode()` was true,
*before* applying the per-type category whitelist. In SSH / terminal-provider execution
mode this handed an `explore` sub-agent every enabled tool — including `shell_execute`
(category `SYSTEM`), `file_write`, `file_edit` and `file_delete`.
`validateAgentWriteScope()` only guards tools recognised by `isFileWriteTool()`, so
`shell_execute` had no scope check at all: an explore agent could run
`sed -i`, `rm -rf`, `git push`, … on the remote host despite being documented and
prompted as read-only.

**Fix:** the read-only restriction is now evaluated first; the remote-mode bypass only
applies to agent types whose allowed categories are not exactly `{READ}`. Explore agents
stay read-only in every execution mode. Existing tests (`localExploreRejectsShellExecute`,
`localExploreAllowsReadToolsOnly`, `subCodingAllowsShellExecute`) keep their semantics.

---

## 🟠 High

### H1 — Tool batches were reordered: parallel reads ran before earlier writes — ✅ FIXED

**Where:** `feature-tool/.../ToolExecutionCoordinator.java` (`createPlan`) feeding
`app/.../mvp/ToolExecutionScheduler.java`.

**Problem:** `createPlan` put **every** concurrency-safe call into `concurrentTasks` and
everything else into `sequentialTasks`, and the scheduler always drained the concurrent
batch first. A model turn such as `[file_edit, file_read]` or `[git_commit, git_status]`
therefore executed the read *before* the write, while `orderedResults()` still reported the
results in the model's requested order — so the model saw stale state (pre-edit file
content, pre-commit status) presented as the consequence of its own change. This is a
silent correctness bug in the tool loop, not just a performance detail.

**Fix:** only a *leading* run of concurrency-safe calls is parallelised; the first
non-safe call closes the parallel prefix and everything after it is sequenced, so
execution order always equals the requested order. The same loop now skips `null` entries
(previously a null element landed in the sequential list and would NPE in the scheduler at
`call.getId()`).

### H2 — Redirects bypassed `UrlPolicy` (and leaked credentials cross-host) — ✅ FIXED

**Where:** `core-security/.../SimpleHttpClient.java` → `execute()` / `download()`.

**Problem:** `setInstanceFollowRedirects(true)` meant only the initial URL passed through
`UrlPolicy.requireHttpOrLocalCleartextUrl()`. A 30x to another host skipped the
cleartext/host policy completely, and the JDK re-sent the original request headers
(including `Authorization` / API keys) to the redirect target. Affected paths: MCP HTTP
(`CustomMcpHttpTool`, `McpExtensionRepository`), web search / fetch, image APIs, model
catalog fetchers, GitHub skill install — i.e. every untrusted-network caller of the shared
client, plus the documented claim that "all outbound URLs pass through `UrlPolicy`".

**Fix:** redirects are followed manually (max 5 hops); each hop is re-validated with
`UrlPolicy.requireHttpOrLocalCleartextUrl`, non-HTTP(S) targets fail closed, relative
`Location` values are resolved against the current URL, sensitive headers are dropped when
the redirect leaves the original host, and a 303/301/302 on a non-GET request downgrades to
GET (matching the JDK's former behaviour).

**Residual (documented, not fixed):** `AbstractHttpModelProtocol.openJsonPost()` still
uses the JDK's automatic redirect following and validates only the configured model URL.
Chat completions are POSTs, so a fix requires replaying the body per hop; recommended as a
follow-up or an explicitly accepted risk (the base URL is user-supplied).

### H3 — Auto-compaction could summarize away the message the user just sent — ✅ FIXED

**Where:** `app/.../mvp/ChatInteractionController.java` → `dispatchMessage()`.

**Problem:** `activeUserMessageId` was read from `messages.get(messages.size() - 1)`.
When a model-switch notice (`ChatMessage.modelSwitchNotice`, `excludeFromContext=true`) was
appended after the user message, that id belonged to the notice.
`ContextCompactionController.getAutoCompactPreservedTail()` compares the id against the
last *context* message — which is the real user message, because notices are excluded from
context — so the comparison failed and the preserved tail came back empty. The current user
turn was then marked `excludeFromContext` by the compaction and only its summarized form
reached the model: `buildModelMessages(userInput)` does **not** re-insert `userInput` into
the request (it is only used to build the learning context), so the model answered a
summary of the request instead of the request itself.

**Fix:** capture the id of the message that was just appended.

---

## 🟡 Minor / risks (not fixed, low impact)

1. `ModelPromptController.completeToolCallPairsForRequest()` builds a `toolCallIds` set
   that is never read (dead code). Remove it to keep the duplicate-detection logic obvious.
2. `FileMultiEditTool`: the duplicate-match guard uses
   `content.indexOf(oldString, firstIndex + 1)` while the error message counts with
   `countOccurrences()` (non-overlapping). Overlapping occurrences are correctly rejected,
   but the message can read "found 1 matches". Reuse one count for both.
3. `UrlPolicy` private-network predicates are hostname prefix tests, so
   `http://10.example.com` or `http://192.168.example.com` qualify as LAN cleartext hosts.
   Require an actual IP literal (dotted-quad arithmetic on the raw host, no DNS) before
   classifying as private.
4. `ToolExecutionScheduler`: cancellation is only observed between `future.get()` calls, so
   stopping a generation waits for the in-flight parallel tool; completed results are also
   discarded on cancel instead of being reported. Consider cancelling in the token callback
   and returning already-completed results.
5. Tool-result truncation (`ToolResult.truncateContent`) keeps 25K + marker + 25K, so the
   result slightly exceeds `MAX_TOOL_RESULT_CHARS`. Documented behaviour; only relevant if
   something asserts the exact cap.
6. Hardcoded user-visible Chinese persists in the reviewed paths (same class as #3 above):
   `ToolExecutor` ("工具调用为空", "未知工具: ", "参数解析失败: "),
   `GenerationFlowController` ("模型没有返回文本。"),
   `AgentExecutionController` ("Agent 工具调用为空", "Agent 已终止。"),
   `ChatInteractionController` ("还没有可用模型。请进入 设置 → 模型管理 → 添加模型，保存后再发送消息。"),
   `ToolExecutionScheduler` ("执行失败: ", "未知错误"),
   `ContextCompactionController` ("上下文压缩失败：…"). Several of these are regressions:
   the localised resources already exist (e.g. `user_rejected_tool` is used correctly).

---

## ✅ Verified correct (no action needed)

- `GitTool` command construction: every model-supplied argument (paths, commit message,
  remote, branch) is single-quoted with `'` → `'\''` escaping; `--max-count` uses a parsed
  int. No injection path found.
- `FileToolPathPolicy.resolve()`: canonicalises root, target and extra Skills roots before
  the boundary check; the `isInside` prefix test uses `File.separator`; bypass is only
  reachable through `ToolContext.bypassPathProtection`.
- `ArchiveSecretRedactor`: header / raw-JSON redaction is fail-closed; the single fail-open
  path (`performRedactDatabaseSnapshot` copy) is practically unreachable.
- `RetryPolicy`: 1-based attempts map to 2s / 4s / 8s / 16s (capped) plus jitter; permanent
  4xx (except 408/429) skip retry.
- `FileMultiEditTool`: overlap detection and right-to-left application are correct.
- `LineCodeDatabaseArchive.importSnapshot`: single transaction, delete order reverse of
  insert order, rejects newer `schemaVersion`, table names validated against the schema.
- `ContextManager.selectWindow()`: tool groups are kept together with their assistant
  tool-call message; the budget check cannot split a group.

---

## Not audited / out of scope

UI layer (`app/src/main/java/cn/lineai/ui/**`, `:tool-ui`, `:markdown`, `:ui-theme`), SSH
internals (`SshCommandExecutor`, `SshConnectionPool`, `TermuxHelper`), the IPC/AIDL
surface, share/PDF export, i18n resource completeness (spot-checked only), and Gradle build
logic.

---

## 🔌 Aktivasi 5 tool yang sebelumnya mati

Lima tool sudah terdaftar di `BuiltInToolProviders` dan bahkan sudah disebut di system prompt,
tetapi tidak ada di grup default mana pun di `ToolSettingsRepository.buildDefaultConfigs()`,
sehingga `getEnabledToolNames()` tidak pernah mengembalikannya dan `ToolExecutor` menolak
setiap panggilan ("tool tidak diizinkan"). Semuanya kini aktif:

- `file_outline` dan `file_multi_edit` → grup `file_ops` (mode lokal), sesuai instruksi
  system prompt yang sudah menyuruh model memakai keduanya.
- `agent_output` → grup `agent` (semua mode). Tool pengambil hasil agent async ini memakai
  `AgentResultStore` yang memang sudah di-wire di `GenerationFlowController` dan
  `AgentExecutionController`, jadi `agent_id` yang dikembalikan `agent` / `agent_pipeline`
  sekarang bisa diambil isinya. Tetap dikecualikan untuk sub-agent lewat
  `getAgentExcludedToolNames()` supaya tidak rekursif.
- `memory_update` dan `memory_recall` → grup baru `memory` (default aktif, ikon BOOK_OPEN),
  plus `tool_group_memory_name/desc` di values/values-zh/values-ru.

Perbaikan anti-halusinasi pada jalur memori:

- `MemoryRecallTool` sebelumnya stub yang mengembalikan teks contoh. Sekarang benar-benar
  mencari: `LearningContextStore.searchMemories(...)` → `MemoryRanker.rank(...,
  allowRecentFallback=false)`. Tanpa hit kata kunci hasilnya kosong dan model diberi pesan
  eksplisit "tidak ada memori yang cocok — jangan menebak", bukan daftar memori tidak
  relevan yang bisa memicu karangan.
- `MemoryUpdateTool` tidak lagi menyalin teks buatan model ke `ScopedMemoryRegistry` sebagai
  rule `VALIDATED` confidence 1.0 (teks tanpa validasi itu sebelumnya ikut masuk ke system
  prompt sebagai "invariant"). Sekarang hanya tersimpan di tabel `memories` dan sampai ke
  model lewat jalur learning-context biasa yang tunduk pada Learning Mode.
- Regresi dikunci oleh `feature-tool/src/test/java/cn/lineai/tool/builtin/MemoryRecallToolTest.java`
  (query kosong → error, tanpa hit → pesan jujur, ada hit → header + daftar, limit di-clamp
  10, scope tak dikenal → all).

## 🧭 Review System Prompt

Blok yang dipertahankan (berguna, biaya token kecil):

- `system-prompt-template.txt` inti (tool usage, agent scope, tool-call loop, boundaries) — inti akurasi.
- `work-directory-template.txt` — path + batas akses; jangan dihapus.
- `tone-coding` / `tone-chat` — hanya satu yang disuntik secara kondisional; UX produk.
- `todo-usage` / `todo-state` — kecil, dan `todo_update` aktif secara default.
- `learning-context-template.txt` — hanya saat Learning Mode aktif.
- `image-understanding-tool-system.txt`, `agent-role-*`, `context-compaction-*`, `memory-extraction`, `skill-extraction` — dipakai jalur masing-masing.

Dihapus dari prompt:

- "Grounded Project Invariants & Lessons" dari `ScopedMemoryRegistry`. Rules itu ditulis
otomatis oleh `PostMortemLearningEngine` dari pasangan gagal→sukses tool, bisa memuat
potongan pesan error mentah, dan otomatis naik ke `VALIDATED` (hitCount >= 2 atau
confidence >= 0.85) tanpa review pengguna — lalu disuntikkan ke system prompt setiap
request sebagai "invariant". Ini vektor halusinasi + prompt-injection (teks error berasal
dari output tool/repo). Dihapus di `SystemPromptProvider.enrichLearningContext()`; registry
tetap dipakai untuk skor confidence internal dan test-nya tidak berubah.

Diperbaiki:

- Bagian "Code Quality" memaksa gaya TypeScript/React Native ("two-space indentation,
single quotes, semicolons, function components") ke semua proyek, bertentangan dengan aturan
"ikuti gaya proyek". Diganti netral bahasa: ikuti konvensi proyek yang terbaca dari file,
jangan memaksakan konvensi ekosistem lain.

Model identity (`{{MODEL_IDENTITY}}`) — **dipertahankan**. Tanpa blok ini model mengarang
identitas, context window, dan kemampuan; blok ini menyuruh menjawab berdasar modelId nyata
dan menyatakan ketidakpastian. Biaya ±120 token; ini pengurang halusinasi, bukan beban.

---

## 🧪 Adopsi ide context-mode (Oktober 2026)

Dari review repo `mksglu/context-mode` (MCP server Node, stdio-only, ELv2) dua ide diadopsi
tanpa menambah dependensi:

1. **Filter output di shell dulu** — `system-prompt-template.txt` (bagian Tool Usage) kini
   menyuruh model menyaring output besar di dalam shell (grep / sed -n / awk / head / tail)
   sebelum masuk konteks; `agent-role-coding-remote.txt` mendapat aturan yang sama untuk
   sub-agent yang bekerja via shell remote.
2. **Truncation yang tidak menyesatkan** — `ToolResult.truncateContent()` (`:core-model`):
   - label truncation kini menyebut jumlah karakter yang dibuang + pesan eksplisit
     "the omitted middle was NOT read; do not guess it" beserta cara mengambilnya
     (`file_read` `start_kb`/`end_kb` atau filter shell), sehingga model tidak mengarang isi
     bagian tengah yang tidak pernah dibaca;
   - idempoten: konten yang sudah berlabel truncation tidak dipotong lagi — sebelumnya
     `ToolMessageController` memotong kedua kalinya dan justru merusak label itu sendiri.

Ditunda dengan sadar (bukan dilupakan): arsip hasil tool yang dipotong + tool pencariannya.
Alasannya: mayoritas data yang dipotong masih bisa diambil ulang dari sumbernya (file range,
re-run perintah, `agent_output`), sedangkan menambah tabel + store + ToolContext + tool baru
tanpa bisa dikompilasi di sandbox berisiko meninggalkan build rusak. Jika nanti diperlukan,
desainnya: tabel arsip keyed `tool_call_id` (cap ~2 MB) + tool recall dengan filter baris.

---

## 🧭 Audit Pengalaman Agent (Oktober 2026)

Fokus: kenyamanan agent saat coding + meminimalkan halusinasi. Ditinjau: tool file/shell,
rendering tool prompt, grounding, dan jalur error.

Diperbaiki:

- **`glob` melewati semua direktori berawalan titik** (`!name.startsWith(".")`), termasuk
  `.github`, `.vscode`, `.config`, `.gitlab`. Agent yang mencari file CI/config tidak
  menemukannya lalu bisa menyimpulkan "tidak ada" — halusinasi. Sekarang hanya `.git` dan
  `node_modules` yang dilewati. Test baru: `feature-tool/.../GlobToolTest.java`.

Sudah baik (dipertahankan):

- `file_read`: framing `[FILE: path (Lines X-Y of Total)]` + nomor baris + `[EOF]`,
  `[CONTINUED: ... use start_kb=N]` untuk segmen, penolakan file >50KB dengan contoh JSON,
  deteksi biner.
- `file_edit`: pesan error actionable (no-match → refresh grounded snapshot; multi-match →
  perjelas `old_string` atau `replace_all=true`).
- `glob`: mengumumkan pemotongan `MAX_RESULTS=1000`.
- `shell_execute`: `cwd`/`timeoutMs` terdokumentasi (30s default, 300s max), exit code
  disertakan pada kegagalan terminal provider, jumlah baris disertakan saat truncate.
- Tool prompt memuat kategori, flag "needs confirmation", skema parameter penuh, dan
  penguncian format tool call; mode remote mendapat suplemen khusus.
- `agent` tool menyebut `agent_output(agent_id)` untuk output penuh (tool itu kini aktif).
- Penolakan review user memberi pesan eksplisit "re-read the file before editing again".

Diperbaiki juga (lanjutan audit yang sama):

1. **(P1) Duplikasi skema saat native tool protocol** — `ToolPromptRenderer` kini hanya
   merender nama + kategori + deskripsi saat `nativeToolProtocol=true`; JSON parameter
   dilewati karena skema authoritative sudah dikirim lewat native function calling
   (hemat ribuan token per request pada ~30 tool aktif). Teks penjelas menyebut bahwa daftar
   di prompt hanya referensi.
2. **(P2) `file_read` not-found** kini menyarankan hingga 3 nama file mirip di direktori yang
   sama (`tool_file_read_similar_files`, en/zh/ru) — mengurangi loop trial-and-error path.
3. **(P2) `list_dir`** dibatasi 500 entri per pemanggilan + pengumuman
   `… (+N more entries not shown)` sehingga model tahu ada yang tidak terlihat.

Dicatat sebagai desain, bukan bug:

- **(P3) Auto-grounding di `file_edit`** membuat guardrail "read-before-edit" praktis tidak
  pernah menolak (hanya saran saat error). Desain sadar; dicatat sebagai pretensi, bukan bug.

## 📦 Build rilis unsigned (untuk pengujian)

- Build type baru `releaseUnsigned` (`app/build.gradle.kts`): `initWith(release)` sehingga
  mewarisi R8 minify + resource shrink + kamus obfuscation yang sama, dengan
  `signingConfig = null` sehingga tidak pernah memakai debug/release key. Gate
  `validateReleaseSigning` hanya terpasang pada `assembleRelease`/`bundleRelease`, jadi tidak
  terpengaruh.
- CI (`ci.yml`) kini menjalankan `:app:assembleReleaseUnsigned` di setiap push dan mengunggah
  artifact `app-release-unsigned` (retensi 14 hari).
- Catatan: APK unsigned tidak bisa langsung di-install; tanda tangani dulu dengan `apksigner`
  atau kunci sendiri sebelum sideload.

## How to verify

```bash
./gradlew :core-security:build :feature-tool:testDebugUnitTest :app:testDebugUnitTest
# full gate before tagging
./gradlew :app:testDebugUnitTest :app:lintDebug :app:assembleDebug :app:assembleRelease
```

> The audit sandbox has no JDK / Android SDK, so the four fixes (C1, H1, H2, H3) and the
> tool activation above were not compiled or executed here — they are hand-verified against
> the surrounding code and the existing tests, and must be confirmed by the commands above.
> `MemoryRecallToolTest` and `ToolSettingsRepositoryTest` are the fastest way to check the
> memory-recall activation; `ToolRegistryTest` covers tool registration and display category.
