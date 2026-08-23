package cn.lineai.data.db;

import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.database.sqlite.SQLiteOpenHelper;
import android.util.Log;
import cn.lineai.data.db.migration.DatabaseMigration;
import cn.lineai.data.db.migration.Migrations;
import java.io.File;

public final class LineCodeDatabase extends SQLiteOpenHelper {
    private static final String TAG = "LineCodeDatabase";
    private static volatile LineCodeDatabase instance;

    private final Context context;
    private final LineCodeDatabaseBackup backup;

    public static LineCodeDatabase getInstance(Context context) {
        if (instance == null) {
            synchronized (LineCodeDatabase.class) {
                if (instance == null) {
                    instance = new LineCodeDatabase(context.getApplicationContext());
                }
            }
        }
        return instance;
    }

    private LineCodeDatabase(Context context) {
        super(context, LineCodeSchema.DATABASE_NAME, null, LineCodeSchema.VERSION,
                new LineCodeDatabaseErrorHandler(context));
        this.context = context;
        this.backup = new LineCodeDatabaseBackup(context);
    }

    @Override
    public void onConfigure(SQLiteDatabase db) {
        super.onConfigure(db);
        db.setForeignKeyConstraintsEnabled(true);
    }

    /**
     * 仅在数据库首次创建时调用。执行 {@link LineCodeSchema#CREATE_SQL} 一次性建出全部表
     * 与索引（代表当前 {@link LineCodeSchema#VERSION} 的完整 schema），随后写入版本元数据。
     *
     * <p>设计意图：全新安装的数据库已包含全部最新表结构，因此 {@link #onUpgrade}
     * 中的迁移类不会对全新安装重复执行——{@link #applyMigrations} 仅在
     * {@code oldVersion < newVersion} 时由 {@link #onUpgrade} 调用。迁移类中的
     * {@code CREATE TABLE IF NOT EXISTS} 语句对已存在的表是幂等的，不会破坏数据。
     */
    @Override
    public void onCreate(SQLiteDatabase db) {
        executeAll(db, LineCodeSchema.CREATE_SQL);
        executeAll(db, LineCodeSchema.HARNESS_SQL);
        executeAll(db, LineCodeSchema.MIGRATIONS_SQL);
        for (String sql : LineCodeSchema.OPTIONAL_FTS_SQL) {
            try {
                db.execSQL(sql);
            } catch (RuntimeException e) {
                Log.w(TAG, "FTS schema unavailable, continuing without it: " + sql, e);
            }
        }
        writeSchemaVersionMetadata(db, LineCodeSchema.VERSION);
    }

    /**
     * 每次打开数据库时调用。此处重复执行 {@link LineCodeSchema#CREATE_SQL} 作为幂等兜底，
     * 确保表与索引结构完整（应对数据库文件损坏、外部修改或迁移遗漏等边缘情况）。
     *
     * <p>性能影响：所有语句均使用 {@code IF NOT EXISTS}，SQLite 仅做存在性检查后即跳过，
     * 不会重写数据；但每次开库仍需解析并执行全部 DDL，在表数量增长后存在非零开销。
     * 当前表规模下开销可接受，故保留；若后续表数量大幅增加，应改为仅在迁移后执行。
     */
    @Override
    public void onOpen(SQLiteDatabase db) {
        super.onOpen(db);
        executeAll(db, LineCodeSchema.CREATE_SQL);
        executeAll(db, LineCodeSchema.HARNESS_SQL);
        for (String sql : LineCodeSchema.OPTIONAL_FTS_SQL) {
            try {
                db.execSQL(sql);
            } catch (RuntimeException e) {
                Log.w(TAG, "FTS schema unavailable on open, continuing without it: " + sql, e);
            }
        }
        // 若上次因损坏而重建了空库，则尝试从最近备份恢复聊天记录，避免永久丢失。
        maybeRestoreFromBackup(db);
        // 诊断性完整性校验：若仍损坏（且非由 needs-restore 触发的正常空库），仅记录日志，
        // 真正的恢复交由 LineCodeDatabaseErrorHandler 在下次写入时处理，避免向坏库写数据。
        if (!integrityOk(db)) {
            Log.w(TAG, "数据库完整性校验未通过，将在下次写入时由错误处理器恢复");
        }
        // 冷启动兜底备份：确保结构变更/首次打开后也存在可用备份。
        backup.saveAsync(this);
    }

    /**
     * 当存在 {@code linecode.db.needs-restore} 标志（由 {@link LineCodeDatabaseErrorHandler}
     * 在损坏时写入）且存在最近备份时，将备份数据导入当前（空）库，随后清除标志。
     * 任何异常都被吞掉，降级为保留空库，绝不崩溃。
     */
    private void maybeRestoreFromBackup(SQLiteDatabase db) {
        File dbFile = context.getDatabasePath(LineCodeSchema.DATABASE_NAME);
        if (dbFile == null) {
            return;
        }
        File flag = new File(dbFile.getAbsolutePath() + LineCodeDatabaseErrorHandler.NEEDS_RESTORE_SUFFIX);
        if (!flag.exists()) {
            return;
        }
        Log.i(TAG, "检测到 needs-restore 标志，尝试从备份恢复");
        try {
            boolean restored = backup.restoreLatest(this);
            if (restored) {
                Log.i(TAG, "已从备份恢复聊天记录");
            } else {
                Log.w(TAG, "无可用备份，保留空库");
            }
        } catch (Throwable e) {
            Log.e(TAG, "从备份恢复失败（保留空库）: " + e.getMessage(), e);
        } finally {
            if (!flag.delete()) {
                Log.w(TAG, "删除 needs-restore 标志失败: " + flag.getName());
            }
        }
    }

    private boolean integrityOk(SQLiteDatabase db) {
        try (Cursor cursor = db.rawQuery("PRAGMA integrity_check(1)", null)) {
            if (cursor.moveToFirst()) {
                return "ok".equalsIgnoreCase(cursor.getString(0));
            }
        } catch (Throwable e) {
            Log.w(TAG, "integrity_check 执行失败: " + e.getMessage());
        }
        return false;
    }

    /**
     * 供 Repository 在写入成功后触发一次异步自动备份。
     */
    public void backupAsync() {
        backup.saveAsync(this);
    }

    @Override
    public void onUpgrade(SQLiteDatabase db, int oldVersion, int newVersion) {
        if (oldVersion == newVersion) {
            return;
        }
        applyMigrations(db, oldVersion, newVersion);
        writeSchemaVersionMetadata(db, newVersion);
    }

    private void applyMigrations(SQLiteDatabase db, int fromVersion, int toVersion) {
        executeAll(db, LineCodeSchema.MIGRATIONS_SQL);
        for (DatabaseMigration migration : Migrations.all()) {
            int target = migration.getTargetVersion();
            if (target > fromVersion && target <= toVersion) {
                migration.apply(db);
                ContentValues values = new ContentValues();
                values.put("version", target);
                values.put("applied_at", System.currentTimeMillis());
                db.insertWithOnConflict("schema_migrations", null, values, SQLiteDatabase.CONFLICT_REPLACE);
            }
        }
    }

    private void writeSchemaVersionMetadata(SQLiteDatabase db, int version) {
        db.execSQL("INSERT OR REPLACE INTO metadata(key, value, updated_at) VALUES('schema_version', ?, ?)",
                new Object[] {String.valueOf(version), System.currentTimeMillis()});
    }

    private void executeAll(SQLiteDatabase db, String[] statements) {
        for (String sql : statements) {
            db.execSQL(sql);
        }
    }
}
