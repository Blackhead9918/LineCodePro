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
