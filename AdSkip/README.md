# AdSkip 智能跳广告助手

版本：**1.2.0**（安全与识别增强版）
包名：`com.aeginning.adskip`
技术：Java 17 / Android SDK 35 / Gradle 8.9 / Android Gradle Plugin 8.7.3 / Shizuku API 13.1.5
最低系统：Android 8.0（API 26）

## 1. 1.2.0 更新内容

### 广告识别与安全点击

- 新增 `AdConfidenceScorer`：结合跳过词强度、广告上下文、控件可见性/可用状态、目标尺寸进行 0–100 置信度评分。
- 明确的 `Skip Ad / 跳过广告` 具有更高分值；普通 `Skip / 跳过` 必须同时存在广告上下文；倒计时和危险操作词直接拒绝。
- 新增二阶段点击审核：先识别，再等待 260ms 重新获取当前窗口和控件树；标签、位置、窗口代次及两次评分不一致时不点击。
- 不跨窗口保存控件对象；第二次检查会重新获取 AccessibilityNodeInfo，避免使用过期节点。
- OCR 坐标动作在执行前再次检查窗口代次、前台包名、密码/可编辑输入控件和规则状态。
- 扫描树超过节点数/深度限制时按不完整数据处理，不使用部分控件树执行自动化。
- 对密码框和可编辑输入控件不提取文本；不完整或无法检查敏感输入状态时停用自动点击/OCR。
- 坐标点击必须位于屏幕合理区域，不覆盖状态栏/导航手势边缘，且目标尺寸受到限制。

### OCR 与性能

- OCR 采用自适应节奏：存在广告上下文时约 850ms 节流；没有广告上下文时允许窗口事件触发低频视觉探测（至少 7 秒间隔），用于发现无障碍树不可见的自绘广告。
- 截图送入 OCR 前最长边缩放至 1280 像素，减少 Bitmap 内存和识别耗时。
- 根据设备语言选择中文或拉丁文字识别器，避免每张截图都并行运行两套模型。
- 只在本地内存处理截图；截图缓冲及时关闭，Bitmap 完成识别后回收。
- 限制控件树最多 650 个节点、最大深度 14、界面上下文文本最多 16,000 字符。
- 服务销毁时取消待执行确认、关闭 OCR recognizer 并释放后台执行器；延迟回调只使用回收前保存的图像尺寸。

### 本地误判规则和统计

- SQLite 升级到版本 2，采用增量迁移，不删除现有 App 规则、DNS 规则和日志。
- 新增按 App 隔离的“误判屏蔽词”，用户可以屏蔽某个 App 中经常被误判的短语，也可以查看/删除规则。
- 屏蔽规则不会扩展可点击词库；它只会进一步阻止匹配，不能通过用户自定义文本绕开安全词库。
- 新增近 24 小时本地统计：自动点击、安全拦截、未执行、DNS 拦截和错误数量。
- 域名规则采用 2 秒短缓存和集合后缀查找，减少 DNS 高频请求时反复打开 SQLite 游标。
- 日志仍最多保存 2,000 条。无障碍识别仅记录归类后的按钮标签；DNS 拦截/上游错误日志会记录相关域名，便于排查。日志仅保存在本机，可手动清空，不保存完整屏幕文本或截图。

### DNS 过滤

- 虚拟 DNS 路由增加 IPv4 和 IPv6 地址；数据包解析支持 IPv4/IPv6 UDP DNS 查询。
- IPv6 解析处理常见扩展头，并拒绝分片/畸形或非 UDP DNS 数据包。
- 上游按顺序尝试 Cloudflare / Google 的 IPv4、IPv6 DNS 地址，并使用 `VpnService.protect()` 避免上游 Socket 回环进入自身 VPN。
- 上游 UDP 返回截断标志（TC=1）或过大的 DNS 响应时，尝试按 DNS-over-TCP 长度前缀协议重试上游查询。
- 上游 DNS Socket 使用 `protect()` 并绑定指定 DNS 服务器；响应必须匹配事务 ID、QR/opcode、域名、QTYPE 和 QCLASS，否则丢弃并尝试下一个上游。
- 新增最多 256 条的本地正向 DNS 缓存；按 QNAME/QTYPE/QCLASS、查询标志和 EDNS 附加数据区分键值，尊重 TTL（上限 300 秒），缓存返回时递减 TTL；不缓存 NXDOMAIN、SERVFAIL 或零 TTL。
- 如果所有上游失败，返回 SERVFAIL；黑名单命中返回 NXDOMAIN。
- **重要边界**：当前处理进入本地虚拟 DNS 的 UDP/53，不代理客户端主动发起的 TCP/53 连接，也不屏蔽 DoH/DoT、硬编码 IP 或应用内所有广告请求。TCP 是上游截断响应的回退通道，不等于完整的客户端 TCP DNS 代理。
- VPN 必须由用户手动启动并通过系统授权；打开 AdSkip 不会自动占用 Android 的 VPN 通道。VPN 运行时会占用 Android 单 VPN 通道。黑名单命中和上游错误的域名会记入本地日志；用户可在日志页面清空。

### Shizuku / ADB 增强模式

- 保留 1.1.0 的 Shizuku Binder 生命周期监听、权限请求、Shell/Root UID 双重校验、只读应用/Activity/AppOps 诊断和权限撤销时自动降级。
- 补齐官方 `API_V23` Manifest 权限与 V3 客户端声明；Binder 消失、权限撤销或 UserService 连接异常时关闭增强诊断连接。
- 增强诊断仍没有任意 Shell 输入框，不修改权限、不禁用应用、不读取其他应用私有数据。
- Shizuku 不可用时，普通无障碍模式、OCR 和 DNS 模块可独立工作（DNS 仍需 VPN 授权）。

### 设备诊断和 CI

- 新增兼容性报告：Android 版本/API、设备型号、OCR 截图 API 能力、无障碍状态、DNS VPN 状态、Shizuku 状态、Java 堆内存快照、已启用 App 数和本地统计。
- GitHub Actions 使用 JDK 17 和 Gradle 8.9，执行 JUnit、Android Lint、Debug APK 构建，并分别上传 APK 和测试报告。
- CI Artifact：`AdSkip-1.2.0-debug-apk` 和 `AdSkip-1.2.0-reports`。

## 2. 工程结构

```text
AdSkip/
├── .github/workflows/android.yml
├── app/
│   ├── src/main/aidl/com/aeginning/adskip/privilege/IAdSkipShellService.aidl
│   ├── src/main/java/com/aeginning/adskip/
│   │   ├── MainActivity.java
│   │   ├── db/RuleDatabase.java
│   │   ├── engine/
│   │   │   ├── AdConfidenceScorer.java
│   │   │   ├── SafetyPolicy.java
│   │   │   └── TextMatcher.java
│   │   ├── privilege/
│   │   │   ├── AdSkipShellUserService.java
│   │   │   ├── PrivilegeManager.java
│   │   │   └── PrivilegeUtils.java
│   │   └── service/
│   │       ├── AdSkipAccessibilityService.java
│   │       ├── DnsFilterVpnService.java
│   │       ├── DnsPacketCodec.java
│   │       └── DnsResponseCache.java
│   ├── src/main/res/xml/ad_accessibility_service.xml
│   └── src/test/java/com/aeginning/adskip/
│       ├── engine/AdConfidenceScorerTest.java
│       ├── engine/TextMatcherTest.java
│       ├── privilege/PrivilegeUtilsTest.java
│       ├── service/DnsPacketCodecTest.java
│       └── service/DnsResponseCacheTest.java
├── build.gradle
├── gradle.properties
└── settings.gradle
```

## 3. 构建

环境：JDK 17、Gradle 8.9、Android SDK Platform 35、Build Tools 35.0.0。

```bash
gradle --version
gradle :app:testDebugUnitTest
gradle :app:lintDebug
gradle :app:assembleDebug
```

APK 输出：`app/build/outputs/apk/debug/app-debug.apk`。这是 Debug 测试包，不是 Release 上架签名包；项目未包含正式签名密钥。

也可以把整个项目目录上传到 GitHub，在 **Actions → Android CI** 运行工作流，完成后下载 `AdSkip-1.2.0-debug-apk` Artifact。单元测试和 Lint 报告在 `AdSkip-1.2.0-reports`。

## 4. 首次使用

1. 安装 APK，打开 AdSkip。
2. 手动开启无障碍服务并阅读用途披露。
3. 先只启用 1–2 个自己有权测试的应用。
4. 观察识别日志，误判时可在“管理误判屏蔽规则”中按应用包名添加屏蔽词。
5. Android 11+ 可启用本地截图 OCR；密码/可编辑输入框或控件树不完整时会保守停止识别/点击。
6. 可选安装并启动 Shizuku，授予 AdSkip 权限后使用只读增强诊断。
7. DNS 需要先添加明确的域名规则，再阅读数据流向说明并手动授权 VPN。
8. 先使用少量自定义 DNS 域名测试正常网络，再考虑扩充规则。

## 5. 测试与限制

当前工作容器中执行了 35 个纯 Java 测试方法（使用本地轻量 JUnit 注解/断言兼容运行器），并通过 XML 与 GitHub Actions YAML 解析检查。这不等同于 Android Gradle 构建或真机测试；正式 `:app:testDebugUnitTest`、Lint 和 APK 构建由 GitHub Actions 执行。

新增置信度测试覆盖高置信度跳过词、普通词广告上下文、敏感词、倒计时（包括上下文倒计时）和两次观察一致性。DNS 测试覆盖 IPv4/IPv6 UDP 查询解析、响应地址/端口反转、IPv6 UDP 非零校验和、NXDOMAIN/SERVFAIL、截断标志、上游响应验证、TTL 提取/递减和 DNS 缓存键隔离。原有文字匹配和 Shizuku 工具测试保留。

需要注意：

- 置信度是确定性规则分数，不是经过机器学习校准的概率；高分仍不能保证广告一定可跳过。
- Android 11 以下没有本项目使用的无障碍截图 API，主要依靠控件树。
- 一些 App 使用 DRM、安全窗口、Canvas 自绘或隐藏无障碍节点，可能无法识别。
- 当前 DNS 不处理客户端 TCP/53、DoH、DoT；某些 App 可能自带加密 DNS 或硬编码 IP。当前 TCP 仅用于上游 UDP 截断后的重试。
- 不同 OEM 对后台服务、无障碍节点和 VPN 的行为存在差异。需要真机检查 Android 8、11、13、14、15、16，尤其是 Android 13 无线调试下的 Shizuku UID 2000、Binder 断开和恢复。
- 本项目当前没有在此容器完成 Android Gradle 构建或真机验证；必须以 GitHub Actions 结果和目标设备测试为准。
- 无障碍权限风险较高。分发时需遵守 Google Play Accessibility API 政策，不得错误声明 `isAccessibilityTool`；必须提供醒目用途披露、明确同意和窄范围的确定性自动化。

## 6. 数据和隐私

规则、误判屏蔽词和日志只保存在本地 SQLite / SharedPreferences。不会主动上传屏幕文本、截图、应用列表或 DNS 查询。未命中规则的 DNS 查询会被转发给界面披露的公共 DNS 上游；这些 DNS 提供商可以看到查询域名。不要把未经验证的大型黑名单导入正式设备。
