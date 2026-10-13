# OTA 无线固件升级

## 结论:已支持,`tools/ota.sh` 一键升级

```bash
tools/ota.sh              # 默认 192.168.5.37
tools/ota.sh 192.168.5.42 # 指定板子 IP
```

**以后升级固件不用再插 USB。**

---

## 一、原来为什么不行

原分区表**只有一个 app 槽**:

```
nvs 20KB / otadata 8KB / app0 3MB / spiffs 1MB / coredump 64KB
                          ↑ 只有 ota_0
```

OTA 的原理是:**把新固件写进"另一个空槽",重启后从新槽启动**。
只有一个槽就没地方写。

## 二、改成双槽

```csv
nvs,        data, nvs,      0x9000,   0x5000,
otadata,    data, ota,      0xE000,   0x2000,
app0,       app,  ota_0,    0x10000,  0x300000,
app1,       app,  ota_1,    0x310000, 0x300000,   ← 新增
spiffs,     data, spiffs,   0x610000, 0x100000,
coredump,   data, coredump, 0x710000, 0x10000,
```

8MB 总容量,布局占约 7.2MB ✅

**⚠️ 关键副作用**:`spiffs` 偏移从 `0x310000` 变成 `0x610000` → **改分区表这一下会清空脚本**。

所以流程是:
1. **先备份**所有脚本(`/api/scripts` + `/api/script?index=N`)
2. 插一次 USB 烧录新分区表 + 固件
3. **恢复**脚本

> `nvs` 偏移没变(0x9000),所以 **WiFi 配置保留**。
> 分区表在 `0x8000`,和 `nvs` 不重叠,刷分区表不会擦 NVS。

## 三、OTA 接口

```cpp
s_server.on("/api/ota", HTTP_POST, handleOtaDone, handleOtaUpload);
```

用 `Update` 库:`begin` → `write` → `end(true)` → `ESP.restart()`。

```bash
curl -X POST "http://192.168.5.37/api/ota" -F "firmware=@.pio/build/ble/firmware.bin"
# {"ok":true,"message":"固件已写入,正在重启..."}
```

## 四、实测

| 项 | 结果 |
|---|---|
| 上传固件(1.0MB) | ✅ 写入 + 自动重启 |
| 重启耗时 | **约 3~23 秒** |
| **脚本是否保留** | ✅ **4 个全在**(spiffs 不在升级范围) |
| WiFi 配置 | ✅ 保留 |
| 新功能是否生效 | ✅ 备注 + 人类语言日志 |

## 五、注意事项

- **必须和板子同一网段**(板子升级期间会短暂断网)
- 升级中**别断电** —— 双槽设计能容忍写入失败(启动时校验失败会回退到旧槽),
  但断电太早可能两个槽都坏,那就只能 USB 救了
- 固件变大到超过 3MB 就放不下(现在约 1.0MB,还很宽裕)
- **OTA 不动 spiffs** → 流程、脚本永远安全
