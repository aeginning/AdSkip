# AdSkip 智能跳广告助手

版本：**1.3.7**（可自定义尺寸与扩展信息的状态悬浮窗）
包名：`com.aeginning.adskip`
技术：Java 17 / Android SDK 36 / Gradle 8.11.1 / Android Gradle Plugin 8.9.1 / Shizuku API 13.1.5
最低系统：Android 8.0（API 26）

## 1.3.7 可自定义尺寸与扩展信息的状态悬浮窗

- 保留 1.3.6 原有四项显示内容：广告跳过状态、已启用应用数量、DNS 过滤状态、近 24 小时自动点击次数；四项仍默认开启且位于显示列表最前。
- 扩展显示 OCR 状态、当前 DNS 规则数量、近 24 小时安全拦截次数、DNS 拦截次数；未执行与错误次数可由用户额外开启。
- 悬浮窗默认收窄至 178 dp × 190 dp，内容区改为内部可滚动区域；不会因增加状态项目而强行放大整个悬浮窗。
- 主界面新增宽度（150–320 dp）、高度（120–460 dp）和文字大小（9–16 sp）滑杆；尺寸变更实时应用并持久化。
- 根据设备屏幕尺寸限制最大窗口尺寸，防止自定义数值超出屏幕；位置和原有拖动、收起、展开、关闭功能保留。

## 1.3.6 可配置状态悬浮窗

- 新增由现有无障碍服务托管的状态悬浮窗，使用 Android `TYPE_ACCESSIBILITY_OVERLAY`，不申请 `SYSTEM_ALERT_WINDOW`，不新增常驻前台服务。
- 悬浮窗可拖动并记住屏幕位置，支持收起、展开和关闭；无障碍服务停止或被关闭时自动移除。
- 主界面提供悬浮窗总开关，以及独立显示项开关：广告跳过启用状态、已启用应用数量、DNS 过滤状态、近 24 小时自动点击次数。
- 内容从本机规则数据库及 DNS 服务状态读取，不上传数据；关闭任意显示项仅影响悬浮窗文字，不影响广告识别、DNS 规则或其他功能。

## 1.3.5 DNS 订阅网络恢复增强

- 新增 AdAway Official Hosts、1Hosts Lite、Dan Pollock Hosts 三个独立维护来源；原有 HaGeZi Multi LIGHT 与 StevenBlack Unified Hosts 保留。
- 每个 HTTP(S) 镜像有独立 12 秒尝试预算；连接超时 5 秒、读取超时 7 秒；整个更新仍有 90 秒总预算。
- 临时连接失败最多追加 3 次重试，退避间隔为 1 秒、2.5 秒、5 秒；所有来源仍受 12 MiB 下载上限和 120,000 域名解析上限保护。
- 每次镜像请求都通过 Android 当前默认 Network 建立连接；Wi-Fi/移动网络切换后，下一次镜像或重试会重新获取默认网络，不静默绕过用户的系统 VPN。
- 增加 ACCESS_NETWORK_STATE 权限，只显示 VPN/Wi-Fi/移动网络等类型及互联网验证状态，不读取 SSID、IP 或上传网络信息；失败对话框附加当前网络诊断。
- 下载文件必须通过 HTTPS、重定向主机白名单、格式和域名数量验证后，才会在 SQLite 事务内替换对应订阅；失败不触碰旧订阅或手工规则。

## 1.3.4 全量质量优化

- 将广告场景中的高风险操作上下文提取为唯一规则入口；评分器与最终安全策略共用，避免两套词表逐渐不一致。
- 严格拒绝包含非零偏移、更多分片标志或保留位异常的 IPv6 Fragment Header；仅接受字段完全为零的 Atomic Fragment。
- DNS 数据包处理异常增加按异常类型节流的本地日志，不保存完整数据包或额外屏幕信息；关闭过程中的数据库异常不会影响服务退出。
- 清理文字匹配词库中的重复 token，并移除 DNS 下载错误分类中未使用的异常引用字段；不改变匹配词覆盖或订阅行为。
- 移除已失效的嵌套 CI 工作流；唯一生效的仓库级工作流位于 `.github/workflows/android.yml`。
- 同步 README、DNS 第三方来源说明和 CI Artifact 命名，保留原始 1.2.0 源码 ZIP 作为历史快照。

## 1.3.3 DNS 下载可靠性增强（已由 1.3.5 进一步升级）

- HaGeZi Multi LIGHT 和 StevenBlack Unified Hosts 增加多个 HTTPS 镜像，先尝试每个镜像，再对临时连接故障进行限次退避重试。
- 历史版本曾使用 9 秒连接、15 秒读取超时；1.3.5 改为每镜像 12 秒预算（5 秒连接 / 7 秒读取），保留 90 秒总预算和 12 MiB 流式大小上限。
- 展示镜像切换进度、错误分类和重试入口；下载与内容校验完全通过后才以事务替换订阅，失败不改动旧规则。

## 1.3.0 功能基线

### 广告识别与跳过能力
- 保留原有无障碍控件树、OCR、本地误判规则、二次观察、坐标安全校验、选定 App 白名单和 Shizuku 只读诊断功能。
- 文案匹配增加 Unicode NFKC 兼容归一化及零宽字符清理，扩展中、英、日、韩文跳过/关闭广告识别和倒计时格式。
- 截图 OCR 先按系统语言选择本地模型；未找到合格候选时，再尝试另一套本地语言模型。两个模型顺序复用同一张缩放图像，不并行占用两份截图内存。
- 对“跳过 3 / Skip 3”倒计时继续等待并触发有界重扫；只有倒计时消失、广告上下文存在、两次文字/位置复核和最终安全策略均通过才允许动作。
- 危险操作词、密码/可编辑输入控件、无效/越界坐标、不可见/不可点击目标和不完整控件树仍然拒绝自动点击。

### DNS 规则库与订阅
- 手工域名规则继续独立维护，不会与大型下载订阅混在同一个文本编辑框中。
- 支持 HaGeZi Multi LIGHT、StevenBlack Unified Hosts、AdAway Official Hosts、1Hosts Lite、Dan Pollock Hosts 五个用户主动下载/更新的来源。
- 订阅通过 HTTPS 下载，按 dnsmasq / hosts 格式严格解析；拒绝不支持的规则语法、URL、IP、单标签、本地域名、反向解析区和常见公共后缀。
- 下载文件限制为 12 MiB，最多 120,000 条唯一域名；少于 1,000 条或格式/域名校验失败时，拒绝导入。
- 单镜像尝试预算 12 秒（连接 5 秒 / 读取 7 秒），整个更新最多 90 秒；临时连接错误最多追加 3 次退避重试。
- 每次请求使用 Android 当前默认 Network，网络切换后后续镜像会重新获取当前网络；不会静默绕过系统 VPN。
- 更新失败信息显示当前网络类型和互联网验证状态，不采集 SSID/IP，也不会上传网络信息。
- 每个订阅独立存储、开关、更新或删除。更新在 SQLite 事务内替换单个订阅；失败回滚，不覆盖手工规则或其他订阅；原订阅的启停状态在更新后保留。
- DNS 域名查询使用带上限的 LRU 结果缓存和索引查询；订阅元数据计数用于快速展示总量，界面显示为约数以说明跨列表重复域名。
- 增加常见公共后缀保护（如 co.uk、com.cn、com.sg），避免误把整个地区域名树加入黑名单。

### DNS 执行链与稳定性
- DNS TUN 读取线程不再等待上游网络响应；请求交给 4 个固定工作线程和 96 个有界队列并发处理，队列满时快速返回 SERVFAIL，避免无限堆积。
- TUN 响应写入增加同步保护；重复域名拦截/上游错误日志按域名节流，减少 SQLite 高频写入。
- 上游 DNS 采用轮换起始节点：Cloudflare、Google 和 Quad9 的 IPv4/IPv6 地址；单节点超时更短，并保留 UDP 截断后的 DNS-over-TCP 重试。
- 关闭 VPN 时停止新任务并关闭 TUN；服务销毁时终止读取与 DNS 工作线程。
- DNS 仍是 DNS-only VPN：处理被路由到本地接口的 UDP/53，不代理客户端 TCP/53、DoH/DoT 或硬编码 IP；不会宣称能拦截所有应用内广告。Android 同时只能有一个活动 VPN。

### 数据库与兼容性
- SQLite 从版本 4 增量升级到版本 5，新建独立订阅元数据/规则表和域名索引；旧手工域名、App 选择、误判屏蔽词和历史日志保留。
- 原有 DNS 手工规则编辑、全选 App 选择器、无障碍权限用途披露、OCR 开关、Shizuku 诊断、近 24 小时统计、日志、兼容性报告、VPN 启动/停止入口均保留。
- DNS 订阅失败时不修改本机现有规则；删除订阅需单独确认且只删除对应来源。

## 1.2.6 依赖版本同步

- 将 AndroidX Annotation 更新到 CI Lint 当前建议的 1.11.0，消除依赖版本提醒；其余自动化安全、DNS、无障碍生命周期和批量事务修复保持不变。

## 1.2.5 Lint 与 API 声明清理

- 使用 AndroidX `@RequiresApi(R)` 明确标记 Android 11 截图专用方法，并保留低版本调用守卫。
- 移除截图方法内冗余的 API 级别检查；Android 8.0–10 仍使用原有无障碍控件树路径。
- 显式声明 AndroidX Annotation 依赖，避免依赖传递引入。

## 1.2.4 自动化安全与批量保存修复

- 对“Don't skip / 不可跳过”等明确否定语句增加拒绝匹配，避免被“Skip Ad”子串误判为跳过按钮，并增加回归测试。
- 应用规则保存改为单个 SQLite 事务，失败时回滚，避免逐条写入产生部分保存和不必要的 UI 卡顿。
- DNS 黑名单只有在二次确认后才真正清空；无效规则不会覆盖当前名单。
- OCR 点击需要二次截图确认相同文字和近似位置；每个阶段重新检查 OCR 开关、前台应用和安全条件。
- 修复 API 30 截图能力在 API 26 最低版本下的 Lint 标注，并回收无障碍节点和截图 Bitmap。

## 1.2.3 稳定性与安全修复

- DNS 规则拒绝裸顶级域、单标签主机名、IP 地址和 URL；支持合法 IDN 域名转为 ASCII/Punycode。
- DNS 规则替换前先完整校验；包含无效域名时不会覆盖现有名单。清空操作要求二次确认，并限制最多 5,000 条规则。
- 修复无障碍窗口变化事件缺少包名时没有使待执行动作失效的问题；系统中断服务时也会使待执行识别点击失效。
- DNS IPv4/IPv6 包长、UDP 长度、IPv4 保留分片位和 DNS opcode 检查更严格；NXDOMAIN / SERVFAIL 响应保留 RD/CD 标志。
- DNS 规则缓存使用单调时钟，避免系统时间调整影响缓存过期判断。

## 1.2.2 更新

- 重构主界面信息层级：品牌头部、运行概览、应用自动化、本地统计、Shizuku、OCR、DNS 和安全隐私说明分区展示。
- 统一卡片、圆角、描边、间距、字体层级、按钮尺寸和交互反馈；适配 Android 系统栏与不同屏幕高度。
- DNS 启动 / 停止、日志、诊断、应用规则和误判规则等原有入口全部保留；没有删减功能。
- 优化状态信息和长文本的可读性，长列表与主页面继续使用独立滚动区域。

## 1.2.1 更新

- DNS 黑名单新增一组可编辑的默认广告投放、广告联盟和追踪域名，包含 Google、Microsoft、Amazon、TikTok、腾讯、百度、阿里及常见移动广告 SDK / 广告交易平台。数据库从版本 2 增量升级到版本 3；升级时只补充缺失规则，不删除或覆盖用户已有域名规则。
- 应用选择对话框顶部新增“全选当前列表”；支持一次全选、取消全选，也会在逐项勾选时同步顶部状态。范围仅限当前列出的桌面启动器应用。
- 无障碍服务状态通过系统启用组件列表精确比对，回到主界面时刷新授权状态。

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
- GitHub Actions 使用 JDK 17 和 Gradle 8.11.1，执行 JUnit、Android Lint、Debug APK 构建，并分别上传 APK 和测试报告。
- CI Artifact：`AdSkip-1.3.5-debug-apk` 和 `AdSkip-1.3.5-reports`。

## 2. 工程结构

```text
AdSkip/
├── app/
│   ├── src/main/aidl/com/aeginning/adskip/privilege/IAdSkipShellService.aidl
│   ├── src/main/java/com/aeginning/adskip/
│   │   ├── MainActivity.java
│   │   ├── db/RuleDatabase.java
│   │   ├── engine/
│   │   │   ├── AdConfidenceScorer.java
│   │   │   │   ├── DnsFeedParser.java
│   │   │   ├── DomainRuleValidator.java
│   │   │   ├── SafetyPolicy.java
│   │   │   └── TextMatcher.java
│   │   ├── privilege/
│   │   │   ├── AdSkipShellUserService.java
│   │   │   ├── PrivilegeManager.java
│   │   │   └── PrivilegeUtils.java
│   │   └── service/
│   │       ├── AdSkipAccessibilityService.java
│   │       ├── DnsFeedManager.java
│   │       ├── DnsFilterVpnService.java
│   │       ├── DnsPacketCodec.java
│   │       └── DnsResponseCache.java
│   ├── src/main/res/xml/ad_accessibility_service.xml
│   └── src/test/java/com/aeginning/adskip/
│       ├── engine/AdConfidenceScorerTest.java
│       ├── engine/TextMatcherTest.java
│       ├── engine/DomainRuleValidatorTest.java
│       ├── engine/DnsFeedParserTest.java
│       ├── privilege/PrivilegeUtilsTest.java
│       ├── service/DnsPacketCodecTest.java
│       ├── service/DnsFeedManagerTest.java
│       └── service/DnsResponseCacheTest.java
├── build.gradle
├── gradle.properties
└── settings.gradle
```

## 3. 构建

环境：JDK 17、Gradle 8.11.1、Android SDK Platform 36、Build Tools 36.0.0。

```bash
gradle --version
gradle :app:testDebugUnitTest
gradle :app:lintDebug
gradle :app:assembleDebug
```

APK 输出：`app/build/outputs/apk/debug/app-debug.apk`。这是 Debug 测试包，不是 Release 上架签名包；项目未包含正式签名密钥。

也可以把整个项目目录上传到 GitHub，在 **Actions → Android CI** 运行工作流，完成后下载 `AdSkip-1.2.6-debug-apk` Artifact。单元测试和 Lint 报告在 `AdSkip-1.2.6-reports`。

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

以仓库根目录 `.github/workflows/android.yml` 的 GitHub Actions 结果为准：CI 执行完整 Android Gradle `:app:testDebugUnitTest`、`:app:lintDebug` 和 `:app:assembleDebug`，并分别上传 Debug APK 与测试/Lint 报告。CI 成功不等于已在所有 OEM 真机验证；无障碍、截图、VPN 与各广告 SDK 的实际行为仍需目标设备测试。

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
