# WriteOn v2.0.2

> versionName `2.0.2` · versionCode `14` · built 2026-10-22
> Baseline `v2.0.1` · tag `v2.0.2`
> Upstream project: [3zpnix/WriteOn](https://github.com/3zpnix/WriteOn) · this fork: [Brandontake/WriteOn](https://github.com/Brandontake/WriteOn)

**English:** Deleting a tag from the drawer now asks for confirmation, and paged image export no longer loses the first page's content.

## 新增

- **标签删除确认**：抽屉（首页左上角入口，含“全部笔记”“置顶笔记”“回收站”）中标签行的 × 不再直接删除，而是先弹出 Material 3 确认对话框：删除图标与破坏性按钮使用错误色，正文点名要删除的标签。待删除状态用 `rememberSaveable` 保存，旋转屏幕或进程恢复后不会丢。

## 修复

- **多图导出不再丢失第一页内容**：此前导出在图片“解码进缓存”后就开始截屏，但此时笔记里的 `AsyncImage` 可能还没绘制；空白校验只认整页空白（标题和时间戳已经画上），缺图的第一页因此被保存。现在 `MarkdownText` 通过可选的 `onImageLoaded` 回调上报每张图片加载成功或失败，导出会等所有图片就绪后才开始截屏（5 秒超时兜底，坏文件不会卡住导出）；分页数量在第一次截屏前随每次测量持续重算，避免图片撑开高度之前锁定的页数漏掉内容。

## 验证

- `NoteSegmentsTest`：13/13 通过。
- `assembleDebug`、`assembleRelease`、`lintVitalRelease`（离线）全部通过。
- Release APK 使用项目上传密钥签名：`CN=Brandontake, OU=WriteOn, O=WriteOn, C=US`；证书 SHA-256 `67a2d2a5…e9fd9244`，与 1.10 / 2.0 / 2.0.1 一致，可直接覆盖安装。
- Release APK：3,967,487 bytes（3.78 MiB），`minSdk 26` / `targetSdk 36`，四种 ABI，`en` + `zh` + `ja`。
- SHA-256：`E3977E2EC2674BBCCCCDDD72644D79F3E6314F60FDB8C234B956C49D7F03B1E4`。

## 安装

下载 `writeon-v2.0.2-release.apk`，允许文件管理器安装即可。这是 fork，和上游应用可以并存，笔记各自独立。

## 说明

- 受当前开发环境限制，标签确认弹窗动画与多图导出第一页带图尚未在真机上验证；如遇到问题请在 GitHub Issues 反馈。

## License

GPL-3.0, same as upstream. Modifications © 2026 Brandontake.
