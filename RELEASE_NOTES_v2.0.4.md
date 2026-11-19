# WriteOn v2.0.4

> 本版 = 第六轮验收修复 + 发版门槛收尾 + 第七轮 WebDAV/无障碍加固 + 交互与数据层收尾。
> 审计对象为 2.0.3（tag `v2.0.3` → `62829dc`）之后的全部改动；`versionCode 16` / `versionName "2.0.4"`。

## 数据安全（本版重点）

- **删除不再崩溃**：撤销一次"新建"后单条查询会收到 `null` 而 NPE 的路径已修（`EncryptedNoteDao` 改 `mapNotNull`，字节码级确认 `ifnull` 后不 emit）。
- **撤销不再串字段**：标题与正文各用一个 `UndoRedoState`，撤销不会再把标题写进正文并随自动保存落库。
- **撤销栈有界**：历史上限 100 条、连续输入合并、保存后清空——长文连续输入不再有 OOM 风险。
- **新笔记认领自己的 id**：用事务返回的 `savedId` 绑定编辑器，删掉 `MAX(id)` 事后认领链路。
- **Keystore 失败不再静默清空**：密钥缺失/密文解不开时拒绝造新密钥、拒绝把空串写回密文。
- **解不开的行降级而非崩溃**：`DecryptFailureRegistry` 让单行解密失败保留该行 + 占位正文 + 首页横幅计数，所有写入路径拒绝回写该行。
- **保险箱恢复可用**：首页横幅 + 会话内解锁（`setPassword` 终于有调用者），`encrypted = 1` 的笔记不再被恒 false 的 `isVaultMode` 永久过滤；解锁时把 legacy 单次 SHA-256 的行重封为 `v2:` 格式。

## 备份与还原

- **归档带 manifest**：`WBK2` 首条 zip entry 记录 `createdAt / notes / attachments`，还原前可展示"这份备份多旧"，旧归档仍可读。
- **拒绝装入不兼容的库**：还原前校验 `user_version` 与 `room_master_table.identity_hash`，新版库/陌生 SQLite 直接拒绝。
- **单次原子替换**：`Files.move(ATOMIC_MOVE, REPLACE_EXISTING)` + 先 copy 再换，不再两次 rename。
- **`.bak` 轮转出口**：始终只留一份，下一次恢复覆盖。
- **附件对账**：恢复失败留下的无主文件、删除失败留下的无主行，由 `AttachmentArchive.reconcileAfterRestore` 收口。
- **TXT 备份幂等**：按内容去重，单条目 1 MiB 上限。
- **导出一致性快照**：notes 与 attachments 在同一个 `withTransaction` 内读取。
- **zip-slip 绝对路径修复**：`resolveEntry` 现在显式拒绝 `/`、`\` 和平台绝对路径。原先的 canonical 检查在 Linux 上会把 `/etc/passwd` 当成 `<staging>/etc/passwd`，CI 已抓到这条。

## WebDAV

- **暂存 + MOVE**：先 PUT 到 `.uploading` 临时名，`Content-MD5` + `HEAD` 长度校验，再 WebDAV `MOVE` 到主文件名。
- **条件请求**：`If-Match` / `If-None-Match: *` / `If-Unmodified-Since` 三态齐全；无 ETag 回退 `Last-Modified`，两者都没有时终止并提示。
- **远端被删不再 412 死循环**：HEAD 发现文件不存在就清掉陈旧校验器重建。
- **冲突副本有界且不互相覆盖**：副本 PUT 带 `If-None-Match: *`，412 换带序号的名字重试；状态改为最多 10 条的列表，成功后复位，UI 逐条列出。
- **重试分类**：`WebDavRetry` 按 RFC 9110 判定，4xx 终态并提示，`MAX_RUN_ATTEMPTS = 5` 封顶。
- **3xx 不再被当成功**：`followRedirects(false)`，重定向单独终态。
- **`http://` 明文按产品选择放行**：面向无 TLS 的自托管 LAN WebDAV；manifest 声明 `usesCleartextTraffic`，`Cloud.kt` 记录 Basic-auth 明文传输的取舍。重定向仍为终态，重试仍有上限。
- **超时与上限**：共享 `WebDavHttp` client——`callTimeout` 300 s、connect/read/write 30/60/180 s、下载 1 GiB 上限、落盘前 `usableSpace` 预检。
- **网络约束**：备份/恢复工作加 `NetworkType.CONNECTED`，并恢复 `ACCESS_NETWORK_STATE`。
- **结果可见**：备份成功/失败、恢复失败、冲突另存都有 Toast；备份/恢复进行中有进度卡片；凭据解不开时给 `cloud_credentials_unreadable` 专用文案。
- **口令不再被误删**：WebDAV 口令框留空（未触碰）点保存不再删除已存口令。

## 隐私与安全

- **Auto Backup 排除整个 `database` 域**：`note-list.db.bak`、`-wal`、`-shm` 不再随云备份/换机迁移出设备。
- **`FLAG_SECURE` 跟随应用锁**：配置了 PIN/图案/生物识别即强制禁止截屏与最近任务缩略图。
- **导出 TXT 剪贴板带敏感标记**：`EXTRA_IS_SENSITIVE`。
- **锁凭据被重置会明确告警**。
- **分享限流**：每分钟最多 5 次、单图 100 MB 上限，超限丢弃并记日志。

## 性能与架构

- `NoteCrypto` 密钥进程内缓存（双检锁 + 失败失效重取）。
- 备份导出按主键分页（50 行/页）流式写入，仍在同一事务内。
- 首页卡片摘要化、抽屉标签计数一次遍历、行内 `AnnotatedString` 与正则缓存、`Note` 标 `@Immutable`。
- 搜索以上一条结果为前缀时只在上次结果集内扫。
- `MediaRecorder` 移到专用单线程托管；附件文件删除移入 `Dispatchers.IO`；分享图片读取（含 `onNewIntent`）移入 IO。
- **架构**：4 个备份 Worker 与 WebDAV 辅助类移入 `data/backup`，通过 `BackupDatabase.provider(context)` 取库；旧类保留为兼容 shim，2.0.4 之前入队的 WorkManager 任务仍能解析。
- **设置去反射**：`SettingsUseCase` 改为显式 25 字段读写，`SettingsUseCaseTest` 全覆盖。

## 交互与无障碍

- **全局撤销**：保存与删除共用应用级 Snackbar，离开编辑器后仍可撤销；滑动/多选删除先完成 trash 写入再提供 Undo。
- **首帧加载态**：首页首次解密期间显示进度，不再闪空库；WebDAV/本地备份显示进度卡片。
- **预览复选框保 caret**：不再跳回 offset 0。
- 浅色主题 5 处对比度提到 AA 以上；触控目标统一 ≥48 dp。
- 左滑的置顶/标签/删除挂成 TalkBack `customActions`；长按多选补 `onLongClickLabel`。
- 语言选择器 `zh-CN` 单选态正确；格式化工具栏首/末箭头禁用、窄屏可横向滚动；许可页去硬编码英文。
- 设置页「行为」入口（Markdown、LaTeX、实时预览、仅标题的唯一入口），删除无效的 `editMode` 与工具页重复开关。
- Android 12 及以下不再每次打开编辑器都弹"需要存储权限"。

## 数据库

- **schema v11**：`attachments` 表新增 `trashed_at`、`type` 索引（媒体库与回收站过滤），`MIGRATION_10_11` 与 `11.json` 同时入库，`DatabaseRestore.CURRENT_IDENTITY_HASH` 已同步。

## 构建与 CI

- 测试基建：Robolectric 4.17 + `androidx.room.testing` + `androidx.test.core`，`app/schemas` 挂进 `mergeDebugAssets` 供 `MigrationTestHelper` 读取。
- `release_build.yml` 新增 tag 与 `versionName` 一致性断言；`nightly_build.yml` 补 `schedule`。

## 验证

- `testDebugUnitTest` **138/138**（18 个套件）：`DatabaseRestoreTest` 19、`BackupArchiveCryptoTest` 14、`LatexParserTest` 14、`NoteSegmentsTest` 13、`AttachmentReconcileTest` 9、`TxtBackupWorkerTest` 9、`LegacyBodyMigrationTest` 8、`EncryptionHelperTest` 7、`NoteImageExporterTest` 7、`DecryptFailureRegistryTest` 7、`AttachmentSyntaxTest` 7、`WebDavRetryTest` 6、`AttachmentArchiveTest` 6、`AutoBackupCryptoTest` 3、`LockResetPolicyTest` 3、`NoteDatabaseMigrationTest` 3（Robolectric + `MigrationTestHelper`，6→11）、`UndoRedoStateTest` 2、`SettingsUseCaseTest` 1。
- `lintDebug` **0 errors / 75 warnings**；`lintVitalRelease`、`assembleDebug`、`assembleRelease` 通过。
- 三语 517 常规 + 37 锁屏 = 554 个 string key（en/zh/ja），双向差集为空。
- Schema v11，`app/schemas/.../11.json` 已入库。

## 构建产物

- Release APK：**4,102,355 bytes**（3.91 MiB），`versionCode 16` / `versionName 2.0.4`，`minSdk 26` / `targetSdk 36`，四 ABI，`en` + `ja` + `zh`。
- 使用项目上传密钥签名：`CN=Brandontake, OU=WriteOn, O=WriteOn, C=US`；证书 SHA-256 `67a2d2a5…e9fd9244`，与 1.10 / 2.0 / 2.0.1 / 2.0.2 / 2.0.3 一致，可直接覆盖安装。
- Release APK SHA-256：`DCAA299BE0E0428D1086089C593F6B86AC3AA3AD7DEA831D8047AA0BAC750DB5`。

## 安装

从 [GitHub Releases](https://github.com/Brandontake/Writeon/releases) 下载 `WriteOn-v2.0.4.apk`，或用 F-Droid 客户端更新。

## 说明

- 本版把数据库从 v10 升到 **v11**（附件索引）；升级路径由 `NoteDatabaseMigrationTest` 覆盖（6→11）。
- 从 2.0.3 及更早版本升级时，旧的 WebDAV/自动备份 WorkManager 任务通过兼容 shim 继续可用。
- 仍**未做真机验证**；建议发版前冒烟：升级安装、新建-编辑-撤销、备份上传+还原、锁屏缩略图、Android 12 设备打开编辑器。

## License

GPL-3.0（见 `LICENSE`）。第三方许可见应用内 设置 → About → Licenses。
