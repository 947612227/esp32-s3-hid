# 更新日志

## 固件 1.3.0 / App 3.5(17) —— 稳定性与可维护性优化(分支 `opt/stability-20261014`,基于 `d4b105c`)

> 2026-10-14 已部署到正式服务器,分支**未合并**。一键检查:`bash scripts/check.sh`(需要板子/手机的用例另列在脚本输出里)。

### 部署记录(2026-10-14 16:13)

- 服务端:`server/` 同步到 `/root/touchpad-server`(不动 `data/`、`node_modules/`、`download/`、`package-lock.json`),
  重启后迁移 001(正式库上全部跳过)、002(新建 `counters`,`flow_ver` 种子 109)执行成功;已有 17 张表结构无变化。
  部署前备份:`/root/touchpad-backup-20261014-161315/`(`touchpad.sql` 全库 + `touchpad-server.tgz` 目录)。
- 控制中心发布:OTA 固件 1.3.0(stable)、APK 16 / 3.4;下载页 `/download/` 的三个整片烧录包与 `version.json` 换成 1.3.0。
- 测试板升到 1.3.0 后首次心跳带全量,正式库里该板 8 条 NULL 留底已补回。
- `tools/upload-apk.sh`:修"服务器上"版本读不出来(列表接口字段是 `versionCode` 等,脚本按数据库列名读)。

### 后台一键构建(App / 板子 OTA 固件)

- 后台新增「🛠 一键构建」页:选分支(默认 `opt/stability-20261014`)、填版本号 → 在服务器上编译 → 看实时日志 →
  构建记录里下载检查 → 点「🚀 发布」才写进 APK / 固件列表(手机提示更新、板子收到 OTA)。
  - App:versionCode 自动取号 = max(线上 APK, 没失败的构建) + 1;发布时线上已有更大的号会拒绝(提示重新构建)。
  - 固件:编 `ble` 环境的 OTA 包;版本号写进 `-DFW_VERSION`;同渠道已有同版本号不能建 / 发。
  - 版本号只改 `git archive` 导出的副本,`/root/esp32-s3-hid` 只读(不 checkout,别的会话在用)。
  - 新表 `builds`(迁移 003 + `schema.sql`);接口 `/api/admin/build/meta`、`/api/admin/builds*`(看:登录;建 / 发 / 停:管理员);
    审计 `build_start` / `build_publish` / `build_cancel`,上架审计注明来自哪次构建。
  - 手工上传和构建发布共用 `src/releases.js`(入库、sha256、审计、保留策略),上传接口行为不变。
- 资源控制(服务器 2 核 1.7G,还跑 MySQL):同一时间只跑一个;构建进程放进 `systemd-run --scope -p MemoryLimit=1000M`,
  `nice 10` + `ionice idle`,固件 `-j1`;超 40 分钟自动停;服务重启时停掉残留 scope 并标记失败;
  每类只留最近 5 次构建的日志和产物。
- 工具链 `server/tools/setup-build-env.sh`(装到 `/opt/tp-build/`,可重复执行):
  JDK 17(清华 Adoptium)、build-tools 35 + android-35(腾讯云镜像,`android.jar` 与 Mac 一致)、
  Python 3.11(python-build-standalone,校验 sha256)+ PlatformIO 6.1.16(阿里云 pip)、espressif32 7.1.3、
  框架 4.20017.260907、NimBLE 2.5.1(构建时锁定)、`secrets.h`(从主仓库工作区复制,600)、2G swap(swappiness 10)。
  - ⚠️ 不能用系统 Python 3.6:`tool-esptoolpy` 安装后要 pip 装 `cryptography~=46`,装不上就删掉自己的 `package.json`,
    编译报 `MissingPackageManifestError`。
  - ⚠️ 固件必须带和正式固件同一份 `secrets.h`:token 不对的固件 OTA 上去,板子心跳被拒,之后就收不到 OTA 了。
- 实测(服务器上):
  - App 11 秒、内存峰值约 150 MB;以 17 / 3.5 构建的 APK 和正式 APK 17(Mac 编)10 个文件**逐个 CRC 一致**(含 `classes.dex`)。
  - 固件约 1.5 分钟(-j1)、内存峰值约 300 MB;1.3.0 构建产物 1287760 字节(Mac 1288000,差在编进去的源码路径长度)。
    把服务器编的 1.3.0 烧到测试板:开机、连 WiFi、心跳被控制中心接受、蓝牙自动回连、注入滑动手机收到 26 个触摸坐标事件;
    验证完已刷回 Mac 编的正式 1.3.0。
  - `tests/server/test_build.sh`(临时 MySQL + 真工具链,约 4 分钟):取号、参数校验、只读账号、并发、APK 版本号与证书、
    发布后手机检查更新 / 下载、旧号拒发、工具链损坏失败、记录保留、停止、重启打断、固件构建发布与 beta 心跳、主仓库不被改动 —— 全部通过。
  - 后台页面(Playwright 真浏览器):登录 → 一键构建页 → 开始构建 → 日志实时刷新 → 成功 → 发布 → APP 管理出现新版本,无 JS 报错。
  - 回归:`test_route_split`(旧路由逐行一致,只多登记的 8 条构建接口)、`test_schema_deploy`(正式库只新增 `builds`)、
    `test_flow_sync`、`test_flow_ver`、`test_version_prune` 全部通过。

### App 3.5(17):蓝牙控制通道一次失败后再也连不上

- 现象(2026-10-15):手机侧的蓝牙配对记录丢了,重新配对后触摸 HID 正常,但 App 的「蓝牙控制通道」一直停在
  「没扫到板子」,只能走 WiFi;点「连接蓝牙控制」也没反应。
- 原因:`Ble.connect()` 用 `connecting` 防止重复发起连接,但扫描超时、蓝牙不可用、扫描器不可用、扫描异常、
  `connectGatt` 异常这几条失败路径只回调、不清 `connecting`,之后每次 `connect()` 都直接返回。
- 修复:所有失败路径和 GATT 断开(含连接超时 status=147)统一走 `finishConnect(false)`;
  成功时回调原来会被通知两次(`pendingConnect` 和 `waiters` 各一次),去掉 `pendingConnect`,只通知一次。
- 真机验证(三星 S23 Ultra):关蓝牙 → 点「连接蓝牙控制」(扫描超时失败)→ 开蓝牙,连续几次「没扫到板子」后
  第 10 秒自动恢复「就绪(蓝牙,MTU=255)」,请求走 `BLE /api/status`。

### 测试板 USB 直连试验(未成功,已刷回蓝牙版)

- 测试板(`28:84:85:89:78:c8`)刷 USB 版 1.3.0,原生 USB 口接手机。板子串口报 `HID: 就绪`(TinyUSB `mounted()` 为真),
  但手机 `dumpsys usb` 里看到的是芯片自带的 `Espressif USB JTAG/serial debug unit`(0x303A:0x1001),
  不是固件的 `HID Touch Injector`(0x303A:0x4004);`getevent` 里也没有板子的输入设备。原因未查明。
- 2026-10-15 刷回蓝牙版 1.3.0;手机重新配对后 `getevent` 出现 `ESP32S3 Touch HID`,板子发的下滑手势能拉下通知栏。
- App `Link.detectHid()` 会误报「USB 直插」:任何 Espressif 厂商号的 USB 设备都算(包括上面的 JTAG 口),
  名字含 `touchpad` 的输入设备也算(三星自带 `sec_touchpad` 会命中)。未修改。

### 服务端:APK / 固件只保留最近 5 个版本

- 新增 `server/src/retention.js`。上传 APK 或固件成功后,以及服务启动时,各清理一次:
  - APK 按 `versionCode` 计,保留最大的 5 个;同一个 versionCode 传过多次的,只留最新那次。
  - 固件按渠道(stable / beta)分别计,按上传时间保留最近 5 个版本;被板子策略指定(`policies.target`)的固件不删。
  - 记录和 `data/apk`、`data/firmware` 下的文件一起删,每删一条写审计 `prune_apk` / `prune_firmware`。
  - 清理出错只打日志,不影响上传和启动。上传接口的响应多带一个 `pruned` 字段(删掉的 versionCode / 版本号)。
- 测试 `tests/server/test_version_prune.sh`(临时 MySQL):上传后清理、重复 versionCode、按渠道计、策略指定的固件保留、启动时清理历史数据、文件与记录一一对应、检查更新和下载照常。
- `tests/server/test_schema_deploy.sh`:正式库已部署过本分支时(已有 `counters`),改为期望迁移"不新增任何表"。
- 2026-10-14 16:47 部署到正式服务器(部署前备份 `/root/touchpad-backup-20261014-164741-prune/`,含全库和 `data/apk`、`data/firmware`)。
  启动时清掉 APK 10 个(versionCode 3~11,以及 13 较早那次上传),剩 16、15、14、13、12;固件只有 1.3.0、1.2.0 两个,未删。`data/apk` 从 2.2M 降到 776K。

### 固件

- **BLE 控制通道(优化1)**
  - notify 改为带数据发送,只发给已订阅的连接;发送缓冲满时退避重试。去掉了控制响应分片之间的 `delay(30)`。
  - 控制通道改为每轮主循环都处理,往返耗时从约 5000ms 降到 30~200ms。
  - 收包改为待处理队列加互斥锁,连发 3 条不再乱帧。
  - 看门狗:HID 已订阅,或 60 秒内有控制流量,都算健康连接,空闲的好连接不再被踢。
  - `SAVE` 入队前先同步校验语法,有错误直接返回给 App。
- **流程结束不再阻塞 5 秒(优化2)**:"黄灯至少亮 5 秒"改由 LED 模块非阻塞补足,`run()` 立刻返回,两次连跑的间隔从至少 5 秒降到约 1.5 秒。
- **流程执行中推迟心跳(优化3)**:同步心跳会拉长 wait、打乱点击时机。现在执行中先不发,结束后立即补发;最多推迟 150 秒。
- **循环上限与死循环检测(优化5)**
  - `repeat` 次数改为 1~65535,以前 `repeat 300` 会被截成 44。
  - 死循环检测由"总步数 >1024"改为"连续 2000 步没有点击/等待/通知"。
  - 执行中每 20ms 让出一次网络/蓝牙处理,没有 wait 的循环也能被停止。
  - 示例「重复演示」补上了 `endloop`;旧固件写坏的同名文件开机时自动修复。
  - Serial 缓冲大小改到 `begin()` 之前设置(之后设无效)。
- **心跳去冗余(优化6)**:流程名和内容做摘要,没变化就不带,心跳从 3309 字节降到 217 字节;开机、内容变化、每 30 分钟、服务端要求时才带全量。
- **启用 PSRAM(优化7)**
  - 脚本表移入 PSRAM,上限放宽到 32 个流程 × 128 步;没有 PSRAM 的板子自动退回内部 RAM,容量 4 个。
  - 静态 RAM 从 160044 字节降到 79348 字节。
  - 步数超限、流程数满时明确报错,以前是默默截断或不载入。
- **执行中改/删流程的保护(优化8)**
  - `run()` 对步数和名字做快照,执行中其它流程被改写时不再按错误的步数跑。
  - 正在执行的流程:删除、保存、恢复出厂都会被拒绝(409);改/删别的流程照常允许。
  - 定时循环改为按流程名查找,删掉排在前面的流程不会再跑错。
- **等待精度(优化9)**
  - 评估了"流程执行独立 FreeRTOS 任务",结论是不拆:日志、触摸、LED 等状态都没加锁,拆开风险大,现有协作式执行已够用。
  - 修复 `idleWait` 固定睡 5ms 的问题:`128 × wait 1` 从 800ms 降到 246ms。
- **解析器拆为纯 C++(优化15)**
  - 解析器拆到 `src/common/script_parser.cpp`,常量拆到 `touch_limits.h`,可以在电脑上单独测试。
  - 刷机前后,板子上全部流程的解析结果逐字节一致。
- **修复:标号/goto 超上限被静默处理(优化17 发现)**:以前第 17 个标号会被直接丢弃,第 17 个 `goto` 会静默跳到第 1 步。现在保存时报错,例如"第 33 行:标号太多(每个流程最多 16 个)"。

### 服务端

- **schema 与迁移(优化4)**
  - `schema.sql` 补齐到正式库结构,包括 `flows`、`board_events` 表和 `boards` 的多个列。
  - 去掉写死的 `USE touchpad`,改为按 `DB_NAME` 建库。
  - 新增 `src/migrate.js` 和 `sql/migrations/`,迁移按顺序只执行一次;在正式库结构上,001 迁移全部跳过。
- **流程留底按差异同步(优化6)**
  - `board_flows` 从"整表删光重插"改为按差异增/删/改。
  - App 代报时只带名字、不带内容,不再把留底内容覆盖成 NULL。
  - 留底里有内容为 NULL 的行(旧服务端写坏的)时,心跳响应 `needFlows:true`,让板子补报全量(优化17 补充)。
- **拆路由(优化11a)**:`index.js` 从 1553 行拆成入口加 6 个模块,行为不变。51 条路由逐行一致,63 条请求回放的结果也全部一致。
- **流程版本号原子取号(优化11b,迁移 002)**:新增 `counters` 表。以前用 `MAX(ver)+1` 取号,40 个并发保存只拿到 3 个不同的号;现在号全部不同且连续。
- **App 鉴权 / HTTPS(优化11c)**:只评估、不实施(鉴权暂不需要),方案写在 `docs/控制中心接入.md` 第六节。

### Android App(优化12)

- `Board` 改为全 App 共用实例,共用地址、传输方式、在途合并表和短缓存。
- **修复**:回调为 null 的请求根本没有发出去。编辑页改名后,删除旧名的请求从未生效,板子上会留下旧名的流程;停止脚本时的 `scheduleStop(null)` 也没发出去。
- **修复**:写操作也被合并,同路径并发时前一个回调会被覆盖。现在写操作不合并,并且写前写后都会作废读缓存。
- 主界面拆出 `GestureTab`、`LogsTab` 两个页签。其余页签和主界面耦合太重(流程页 68 处、定时调度页 86 处外部引用),暂不拆。

### adb-only 工具(优化13)

- 新增 `HidDriver`:点击、长按、滑动走板子的 `/api/gesture`,HOME/BACK/最近任务走 `/api/control`。电脑连不到板子时,可用 `--relay` 经手机转发。
- `click.py` 新增 `--driver adb|hid` 参数;README 中 swipe/long 的命令行示例改为使用 `--ms`。

### 测试与工具(优化15/16)

- `tests/native/`:解析器单元测试,共 83 条断言,开启 ASan 和 UBSan。
- `scripts/check.sh`:一键运行解析器测试、固件 ble/usb 编译、HidDriver 单元测试、App 编译、服务端 JS 语法检查和 shell 语法检查。
- 新增的真机测试:`tests/ble/*.sh`、`tests/app/test_board_shared.sh`、`tests/adb_only/test_hid_driver_device.sh`。
- 新增的服务器测试:`tests/server/*.sh`,使用临时 MySQL,不碰线上库。
- `test_route_split.sh`:拆分之后有意改动的行为登记为"预期差异"(请求、旧响应、新响应、原因);不在表里的差异,以及登记了却没出现的差异,都算失败。

### 文档(优化17)

- README:
  - 修正 `repeat` 的写法(必须与 `endloop` 配对);补充 `break`、`notify`;
  - 补充上限说明(128 步、32 个流程、标号/goto 各 16 个);
  - 修正 goto 保护的描述、BLE 报告长度(60 字节加 1 字节 Report ID)、文件目录。
- `docs/waitfor演示流程.md`:"单个流程最多 32 步"标注为已过时。

### ⚠️ 已知问题 / 注意事项

- **正式库的流程留底曾经是空的(已补回)**:旧版服务端收到 App 只带流程名的代报时,会把 `board_flows.text` 写成 NULL,测试板 8 条留底全部为 NULL。部署后测试板首次心跳带全量,已补回;另一块板子(ac:a7:04:26:5e:40)的 7 条留底没有 NULL。
- **测试中误清空过一次板子流程(已恢复)**:验证优化8 时,开发中的引擎 bug 让流程 0 步瞬间结束,"执行中拒绝恢复出厂"的用例真的执行了恢复出厂。已用测试前备份的 LittleFS 分区恢复,备份在本机 `~/workspace/board-backup-20261014/`。之后用例改为先确认流程真的在执行,才发破坏性请求。
