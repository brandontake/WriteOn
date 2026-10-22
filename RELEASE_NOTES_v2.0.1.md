# WriteOn v2.0.1

> versionName `2.0.1` · versionCode `13` · built 2026-10-22
> Baseline `v2.0` · tag `v2.0.1`
> Upstream project: [3zpnix/WriteOn](https://github.com/3zpnix/WriteOn) · this fork: [Brandontake/WriteOn](https://github.com/Brandontake/WriteOn)

**English:** Experimental live attachment blocks in the editor, keyboard-aware caret scrolling, and a preview selection crash fix, plus split preview and more reliable paged image export.

## 新增

- **附件即时渲染（实验，默认关闭）**：图片、录音独占行在编辑器中渲染成与阅读页一致的块，正文底层仍然只保存普通 Markdown。块菜单可以编辑源码行、删除附件、重命名录音；点按图片可全屏查看。到 **设置 → 行为 → 附件即时渲染（实验）** 打开；`BuildConfig.FEATURE_ATTACHMENT_LIVE_RENDER` 是编译期开关。
- **平板横屏分屏预览**：笔记和渲染结果并排显示，右侧沿用预览页和图片导出的渲染路径；Markdown 关闭时仍保持单栏。
- **图片导出更可靠**：导出会等待分页窗口完成布局、预解码笔记中的图片，并在第一页意外空白时重试或报错，不再保存缺图或空白页。

## 修复

- **键盘不再遮挡光标**：光标在正文下方时，键盘弹出会把该行滚入视野；光标已经在上方可见时保持当前滚动位置，不跳动。
- **预览选择手柄不再闪退**：预览和分屏预览改为非 Lazy 的 `Column` + `verticalScroll`，滚动后所有文本布局仍然保留在组合中，选择手柄不会引用已销毁的布局。超长笔记的预览组合成本会相应增加。
- **CRLF 与输入法**：分段编辑按原样保留 CRLF；快速输入、粘贴和中文/日文输入法组合输入通过稳定的段键写回，不丢字、不串段。结构编辑（插入/删除附件、撤销/重做）后，光标会落到最近的文本段并重新聚焦。

## 验证

- `NoteSegmentsTest`：13/13 通过。
- `assembleDebug`、`assembleRelease`、`lintVitalRelease`（离线）全部通过。
- Release APK 使用项目上传密钥签名：`CN=Brandontake, OU=WriteOn, O=WriteOn, C=US`；证书 SHA-256 `67a2d2a5…9244`，与 1.10 / 2.0 一致，可直接覆盖安装。
- Release APK：3,966,935 bytes（3.78 MiB），`minSdk 26` / `targetSdk 36`，四种 ABI。
- SHA-256：`B0861F4D7C98F92A183B47763B238251205BC0540E95362DFF11CC46CB49007B`。

## 安装

下载 `writeon-v2.0.1-release.apk`，允许文件管理器安装即可。这是 fork，和上游应用可以并存，笔记各自独立。

## 说明

- 附件即时渲染仍是实验特性，默认关闭；关闭后完全走原来的编辑器路径，非实验路径也保留了键盘/光标修复。
- 受当前开发环境限制，键盘滚动、输入法组合输入、CRLF 显示和预览选择手柄尚未在真机上验证；如遇到问题请在 GitHub Issues 反馈。

## License

GPL-3.0, same as upstream. Modifications © 2026 Brandontake.
