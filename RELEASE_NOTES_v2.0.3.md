# WriteOn v2.0.3

> versionName `2.0.3` · versionCode `15` · built 2026-10-30
> Baseline `v2.0.2` · tag `v2.0.3`
> Upstream project: [3zpnix/WriteOn](https://github.com/3zpnix/WriteOn) · this fork: [Brandontake/WriteOn](https://github.com/Brandontake/WriteOn)

**English:** 2.0.3 is a stability and data-safety release, driven by a three-part code audit. Backups now carry every picture and recording and are restored through an atomic, integrity-checked swap; the editor can no longer lose unsaved text or overwrite a password-protected note; WebDAV uploads refuse to silently overwrite another device's copy. It also includes the offline LaTeX renderer, deletable home-card recordings, the paged-image-export fixes and the Material 3 surface-role cleanup first described for this version.

---

## 新增

- **LaTeX 公式（默认关闭）**：`$...$` 行内公式与 `$$...$$` 块级公式。行内公式用 `AnnotatedString` 绘制上下标；块级公式用 Compose 布局堆叠分数、根号与上下标。解析器覆盖常用子集（希腊字母、运算符、`\frac`、`\sqrt`、`^`、`_`、`\text` 等），未知命令原样显示不会静默丢失。开关在「设置 → 行为」，开启后首页卡片、编辑预览、阅读页与图片导出一致渲染；未引入 WebView、KaTeX 资源或 GPL 不兼容的第三方数学库。
- **备份包包含附件**：导出与云端备份现在把每张图片、每段录音一并打包（`attachments/<id>.<ext>`），恢复时还原到应用私有目录并重写数据库里的路径，换机后不再出现"图片/录音全部不可用"。
- **自动备份可恢复**：每日自动备份改为加密后存放在私有存储，只保留最新 7 份；设置 → 云端新增"恢复自动备份"入口（列出备份、按时间确认、解密、原子换库）。自动备份用不离开本机的密钥加密，因此是本机自愈手段；换机请用 WebDAV 或本地备份。
- **冲突副本可在应用内恢复**：WebDAV 冲突卡片新增"恢复此副本"，并新增"覆盖远端"（强制以本机为准接管主文件），避免踩进冲突后永远无法回到正常同步。
- **未保存草稿**：编辑器把正文镜像进 `SavedStateHandle`（停止输入 500ms 后写入），进程被杀后重新打开会把草稿放回（新旧笔记都支持），保存成功后清除。
- **应用锁增强**：PIN/图案重试等待按次翻倍（30s→1m→5m→15m→30m），等待时间用单调时钟计量并持久化——改系统时钟或杀进程都无法再跳过锁定。

## 修复

- **备份恢复不再截断数据库**：恢复流程用 `RandomAccessFile` 原子换库、校验 SQLite 头与 `PRAGMA quick_check`，失败时保留原库与 `.bak`，不再出现"恢复后空库"。
- **置顶/改标签不再抹掉未保存正文**：置顶、标签、回收站改为单列 UPDATE，编辑器加"本地有未保存修改"闸门，数据库发射不再把旧文本灌回输入框。
- **保存改为单事务**：笔记行与附件行（写入、复活、清理）在同一事务内提交，文件删除放在提交之后——中断不会留下指向已删文件的孤儿行。
- **WebDAV 不再静默覆盖他机备份**：首次上传（本地无 ETag）用 `If-None-Match: *`，冲突时另存 `conflict-*` 副本并提示，不覆盖远端；恢复后播种远端 ETag 而非清空。
- **恢复/导入二次确认**：WebDAV 恢复、本地恢复、自动备份恢复都先弹确认框，明说"将覆盖本机全部笔记、无法撤销"。
- **每日自动备份明文化消除**：不再把明文整库写进 `Android/data`；改为加密、私有存储、轮换 7 份，并迁移删除旧明文。
- **图片与录音的存储与删除**：新图片写入私有 `filesDir/attachments`；恢复出的录音可再次永久删除；清空正文只把笔记移入回收站，不物理删除附件。
- **保险箱不再被降级**：未解锁时保存保险箱笔记会被拒绝并提示，不再把密文静默改写成明文；`NoteCrypto` 解密失败不再把密文当文本返回。
- **备份/恢复流式化**：导出、WebDAV 上传/下载、自动备份、恢复全部流式处理，并有单条 64 MiB / 总量 2 GiB 上限，附件再多也不会 OOM。
- **音频播放器**：`prepareAsync` 不再阻塞主线程；`MediaPlayer` 在配置前先持有，异常不再泄漏解码器；`onPrepared` 加身份守卫，快速换歌不再崩溃。
- **小组件与清单**：删除不存在的 `UPDATE_WIDGETS` 权限，receiver 加 `BIND_APPWIDGET`；小组件图片先量尺寸再采样解码，亮屏刷新不再解出 48 MB 位图。
- **返回与导航**：分享冷启动后返回键不再卡死；通知深链用具体路由而非模板串；工具栏箭头改用 `onSurfaceVariant` 并补上无障碍标签。
- **通知脱敏**：配置了应用锁后，置顶笔记的通知不再在状态栏/通知栏明文显示标题与正文（改为应用名+占位符），锁屏、展开预览、截屏都不再泄露——与小组件一致。
- **剪贴板敏感标记**：复制笔记正文时给剪贴板条目加敏感标记（Android 13+），系统不再在剪贴板界面与建议条里预览正文。
- **WebDAV 明文提醒**：保存 `http://` 服务器地址时提示 Basic-auth 凭据将以明文传输，建议改用 https（不阻断，兼容自建局域网服务器）。
- **备份排除**：`shared_prefs`、DataStore、`BackupFolder/`、`attachments/` 加入 Auto Backup 与设备迁移排除，Keystore 失效不再导致启动崩溃。
- **翻译按钮**：不再把正文拼进 translate.google.com 链接（正文只走剪贴板，由用户自行粘贴），笔记内容不再外发。
- **标签页**：删除失效的 `WHERE :tag IN (tags)` 查询（比较标量与单个 JSON 列，永不匹配），标签筛选改为内存过滤，标签页终于能列出笔记。
- **闪卡**：解析失败不再被空表覆盖（保留损坏负载并拒绝空表保存），避免一次 JSON 错误清空整副卡组。
- **其他**：`LockManager` Keystore 失效降级不崩溃；便签（scratchpad）改为与笔记同密钥加密；搜索空态显示"无结果"而非欢迎语；复制/计算器按钮补无障碍标签；空态文字对比度提升。

## 数据

- **数据库版本 9 → 10**。`MIGRATION_9_10` 给 `trashed`、`pinned`、`updated_at` 建索引（列表筛选/排序列）；导出 Room schema `10.json`，迁移可离线校验。
- 备份包从"仅数据库"变为"数据库 + `attachments/` 附件目录"；恢复时按 `attachment-id` 重写 `path` 为本机路径，跨设备可用。
- 设置新增内部键 `settingsSchemaVersion`（一次性迁移用，不属 `Settings` 字段）。

## 安全

- 备份归档口令从 PBKDF2-65k + AES-CBC 升级为 **PBKDF2-120k + AES-256-GCM**（`WBK2`），并保留旧 CBC 格式可读，2.0.2 及更早的备份仍能恢复。
- 数据库归档与附件恢复经过规范化路径检查，防 zip-slip；恢复前校验 SQLite 头与 `quick_check`。
- 应用锁 Keystore 失效时降级（仅存 PBKDF2 哈希）而非启动崩溃。

## 验证

- `testDebugUnitTest` **61/61** 通过，含新增的回归测试：`DatabaseRestoreTest` 10（恢复字节保留、`.bak` 保留、损坏归档拒绝、zip-slip 拦截、超大成员拒绝、缺库条目拒绝）、`BackupArchiveCryptoTest` 8（新格式往返、流式往返、**旧 CBC 兼容**、错口令拒绝、截断/篡改不泄漏明文、空文件拒绝）、`LegacyBodyMigrationTest` 8。
- `assembleDebug`、`assembleRelease`、`lintDebug`、`lintVitalRelease`（离线）全部通过；`lint` 0 errors。
- domain 层零 presentation/data/widget 依赖（`EncryptionHelper`/`Converters`/附件注册表/widget 重绘/文件导入均已在 `core` 或 `domain` 接口之后）。

## 构建产物

- Release APK：**4,046,303 bytes**（3.86 MiB），`versionCode 15` / `versionName 2.0.3`，`minSdk 26` / `targetSdk 36`，四 ABI，`en` + `zh` + `ja`。
- 使用项目上传密钥签名：`CN=Brandontake, OU=WriteOn, O=WriteOn, C=US`；证书 SHA-256 `67a2d2a5…e9fd9244`，与 1.10 / 2.0 / 2.0.1 / 2.0.2 一致，可直接覆盖安装。
- Release APK SHA-256：`92718CB8EDE688BF4B9EADB61694699AAF6A025042E9349DCE535280C8FF0ACB`。

## 安装

下载 APK 并允许文件管理器安装。这是 2.0.2 之后的常规更新（`versionCode 15`），可覆盖安装 2.0.2 及更早版本。本应用是 fork，与上游可并存，笔记各自独立。

> 升级前建议先用「设置 → 云端 → 备份」导出一份本地备份：2.0.3 改变了备份包结构与数据库版本，一份本地副本是最稳妥的回退手段。

## 说明

- 分页导出、首页长按菜单、即时渲染、LaTeX 渲染与新的无阴影视觉效果尚未在真机上验证；遇到问题请在 GitHub Issues 反馈。
- LaTeX 渲染器是离线轻量实现，不保证覆盖完整 TeX 语法；复杂宏包、矩阵、化学式等按原文或近似文本显示。
- 自动备份使用设备绑定密钥，只能在本机恢复；换机请使用 WebDAV 或本地备份。

## License

GPL-3.0, same as upstream. Modifications © 2026 Brandontake.
