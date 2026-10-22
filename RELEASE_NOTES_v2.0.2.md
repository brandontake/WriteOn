# WriteOn v2.0.2

> versionName `2.0.2` · versionCode `14` · built 2026-10-22
> Baseline `v2.0.1` · tag `v2.0.2`
> Upstream project: [3zpnix/WriteOn](https://github.com/3zpnix/WriteOn) · this fork: [Brandontake/WriteOn](https://github.com/Brandontake/WriteOn)

**English:** This 2.0.2 build adds a Library section for every image and recording, an attachment recycle bin with Notes / Images / Audio tabs, delete and save actions inside rendered previews, and live attachment blocks on by default — on top of the drawer tag-delete confirmation and the paged-image-export fix.

## 新增

- **资料库（抽屉顶级入口）**：抽屉新增「资料库」分区，包含「图片」「音频」两行。页面汇总所有便签中的对应附件（只展示附件本身，不展示便签文字）：图片使用自适应网格，点按全屏查看；音频使用播放卡。长按图片可选择「保存到文件夹」或「删除」。删除会同步移除所属便签中的那一行，并把附件放进回收站。保险箱便签和已在回收站中的便签不会出现在资料库。
- **附件回收站**：回收站改为三个标签页——便签 / 图片 / 音频。图片与音频长按可选择「恢复」或「永久删除」；恢复会把附件行追加回原便签末尾，永久删除才真正移除文件。旧版的便签多选、搜索、全选行为保持不变。
- **渲染预览中的附件操作**：渲染后的图片长按新增「保存到文件夹」「删除」，点按可全屏查看；音频卡片菜单新增红色「删除」。两处删除都会先确认，并把附件移入回收站，而不是直接删除文件。
- **标签删除确认**：抽屉（首页左上角入口，含“全部笔记”“置顶笔记”“回收站”）中标签行的 × 不再直接删除，而是先弹出 Material 3 确认对话框：删除图标与破坏性按钮使用错误色，正文点名要删除的标签。待删除状态用 `rememberSaveable` 保存，旋转屏幕或进程恢复后不会丢。

## 修复

- **多图导出不再丢失第一页内容**：此前导出在图片“解码进缓存”后就开始截屏，但此时笔记里的 `AsyncImage` 可能还没绘制；空白校验只认整页空白（标题和时间戳已经画上），缺图的第一页因此被保存。现在 `MarkdownText` 通过可选的 `onImageLoaded` 回调上报每张图片加载成功或失败，导出会等所有图片就绪后才开始截屏（5 秒超时兜底，坏文件不会卡住导出）；分页数量在第一次截屏前随每次测量持续重算，避免图片撑开高度之前锁定的页数漏掉内容。
- **附件即时渲染默认生效**：`liveAttachmentPreview` 的默认值改为 `true`，此前从没在设置里动过该开关的用户，升级后编辑器会直接把图片和录音显示成块；显式关闭过的用户保持关闭。`BuildConfig.FEATURE_ATTACHMENT_LIVE_RENDER` 仍作为编译期总开关。
- **附件删除可撤销**：从分段编辑器或渲染预览删除后，若在保存前撤销、让附件行重新出现，保存时会自动把对应的行移出回收站，不会留下“行在、图没了”的坏链。

## 数据

- 数据库版本 8 → 9。`MIGRATION_8_9` 为 `attachments` 表新增 `trashed_at` 列，旧数据全部视为未删除；新增 Room schema `9.json`。
- 便签保存时的附件清理会跳过已进回收站的行，因此文件会保留到用户真正清空回收站。

## 验证

- `NoteSegmentsTest`：13/13 通过。
- `assembleDebug`、`assembleRelease`、`lintVitalRelease`（离线）全部通过。
- Release APK 使用项目上传密钥签名：`CN=Brandontake, OU=WriteOn, O=WriteOn, C=US`；证书 SHA-256 `67a2d2a5…e9fd9244`，与 1.10 / 2.0 / 2.0.1 一致，可直接覆盖安装。
- Release APK：4,001,851 bytes（3.82 MiB），`minSdk 26` / `targetSdk 36`，四种 ABI，`en` + `zh` + `ja`。
- SHA-256：`9E44B95587B98418D848622F78877F5DAA98DD557BDFB14C9B6588083857C4AA`。

## 安装

下载 `writeon-v2.0.2-release.apk`，允许文件管理器安装即可。这是 fork，和上游应用可以并存，笔记各自独立。

## 说明

- 受当前开发环境限制，资料库、回收站新标签页、长按菜单、默认开启的即时渲染以及标签确认弹窗动画尚未在真机上验证；如遇到问题请在 GitHub Issues 反馈。

## License

GPL-3.0, same as upstream. Modifications © 2026 Brandontake.
