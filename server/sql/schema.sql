-- ============================================================================
--  触摸注入板 · 控制中心 数据库结构
--  MySQL 8.0 / utf8mb4(支持中文和 emoji)
-- ============================================================================
CREATE DATABASE IF NOT EXISTS touchpad
  CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci;
USE touchpad;

-- ---------------------------------------------------------------- 账号
CREATE TABLE IF NOT EXISTS users (
  id            INT AUTO_INCREMENT PRIMARY KEY,
  username      VARCHAR(64)  NOT NULL UNIQUE,
  password_hash VARCHAR(255) NOT NULL,          -- scrypt,不存明文
  display_name  VARCHAR(64)  DEFAULT '',
  role          VARCHAR(16)  NOT NULL DEFAULT 'viewer',   -- admin / viewer
  enabled       TINYINT(1)   NOT NULL DEFAULT 1,
  created_at    DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
  last_login    DATETIME     NULL,
  last_ip       VARCHAR(64)  NULL
) ENGINE=InnoDB;

-- 登录会话(网页 cookie 用)
CREATE TABLE IF NOT EXISTS sessions (
  token      CHAR(64)     PRIMARY KEY,
  user_id    INT          NOT NULL,
  created_at DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
  expires_at DATETIME     NOT NULL,
  ip         VARCHAR(64)  DEFAULT '',
  ua         VARCHAR(255) DEFAULT '',
  INDEX (user_id),
  INDEX (expires_at),
  CONSTRAINT fk_sess_user FOREIGN KEY (user_id) REFERENCES users(id) ON DELETE CASCADE
) ENGINE=InnoDB;

-- ---------------------------------------------------------------- 密钥/配置
CREATE TABLE IF NOT EXISTS settings (
  k          VARCHAR(64) PRIMARY KEY,
  v          TEXT,
  updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP
) ENGINE=InnoDB;

-- ---------------------------------------------------------------- 板子
CREATE TABLE IF NOT EXISTS boards (
  mac           VARCHAR(32) PRIMARY KEY,
  name          VARCHAR(64)  DEFAULT '',
  fw            VARCHAR(32)  DEFAULT '',
  ip            VARCHAR(64)  DEFAULT '',
  channel       VARCHAR(16)  DEFAULT 'stable',
  heap          INT          DEFAULT 0,
  uptime        INT          DEFAULT 0,
  hid           TINYINT(1)   DEFAULT 0,
  flows         INT          DEFAULT 0,
  running       VARCHAR(64)  DEFAULT '',
  next_name     VARCHAR(64)  DEFAULT '',
  next_at       BIGINT       DEFAULT 0,
  wifi_ssid     VARCHAR(64)  DEFAULT '',
  wifi_rssi     INT          DEFAULT 0,
  wifi_channel  INT          DEFAULT 0,
  wifi_pass_enc TEXT,                                  -- AES-256-GCM 密文
  first_seen    DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
  last_seen     DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
  INDEX (last_seen)
) ENGINE=InnoDB;

-- 板子上报过的 IP(排查"为什么连不上"很有用)
CREATE TABLE IF NOT EXISTS board_ips (
  mac  VARCHAR(32),
  ip   VARCHAR(64),
  at   DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY (mac, ip, at)
) ENGINE=InnoDB;

-- 心跳历史(定期清理)
CREATE TABLE IF NOT EXISTS heartbeats (
  id      BIGINT AUTO_INCREMENT PRIMARY KEY,
  mac     VARCHAR(32) NOT NULL,
  ts      DATETIME    NOT NULL DEFAULT CURRENT_TIMESTAMP,
  heap    INT DEFAULT 0,
  uptime  INT DEFAULT 0,
  hid     TINYINT(1) DEFAULT 0,
  flows   INT DEFAULT 0,
  running VARCHAR(64) DEFAULT '',
  INDEX (mac, ts),
  INDEX (ts)
) ENGINE=InnoDB;

-- ---------------------------------------------------------------- 手机
CREATE TABLE IF NOT EXISTS phones (
  device_id   VARCHAR(64) PRIMARY KEY,
  name        VARCHAR(64)  DEFAULT '',
  model       VARCHAR(64)  DEFAULT '',
  android     VARCHAR(16)  DEFAULT '',
  app_version VARCHAR(32)  DEFAULT '',
  wifi_ssid   VARCHAR(64)  DEFAULT '',
  wifi_rssi   INT          DEFAULT 0,
  first_seen  DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
  last_seen   DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
  INDEX (last_seen)
) ENGINE=InnoDB;

CREATE TABLE IF NOT EXISTS phone_apps (
  device_id VARCHAR(64)  NOT NULL,
  pkg       VARCHAR(128) NOT NULL,
  name      VARCHAR(128) DEFAULT '',
  is_system TINYINT(1)   DEFAULT 0,
  at        DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY (device_id, pkg),
  INDEX (device_id)
) ENGINE=InnoDB;

-- ---------------------------------------------------------------- 固件 / APK
CREATE TABLE IF NOT EXISTS firmware (
  id         BIGINT AUTO_INCREMENT PRIMARY KEY,
  version    VARCHAR(32)  NOT NULL,
  channel    VARCHAR(16)  NOT NULL DEFAULT 'stable',
  file       VARCHAR(128) NOT NULL,
  size       BIGINT       NOT NULL DEFAULT 0,
  sha256     CHAR(64)     NOT NULL DEFAULT '',
  notes      TEXT,
  created_at DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
  created_by INT          NULL,
  INDEX (channel, created_at)
) ENGINE=InnoDB;

CREATE TABLE IF NOT EXISTS apks (
  id           BIGINT AUTO_INCREMENT PRIMARY KEY,
  version_code INT          NOT NULL,
  version_name VARCHAR(32)  DEFAULT '',
  file         VARCHAR(128) NOT NULL,
  size         BIGINT       NOT NULL DEFAULT 0,
  sha256       CHAR(64)     NOT NULL DEFAULT '',
  notes        TEXT,
  created_at   DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
  created_by   INT          NULL,
  INDEX (version_code)
) ENGINE=InnoDB;

-- ---------------------------------------------------------------- 黑白名单
CREATE TABLE IF NOT EXISTS policies (
  mac    VARCHAR(32) PRIMARY KEY,
  mode   VARCHAR(16) NOT NULL,        -- allow / deny / none
  target VARCHAR(32) NULL,
  at     DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP
) ENGINE=InnoDB;

-- ---------------------------------------------------------------- 审计
CREATE TABLE IF NOT EXISTS audit_log (
  id      BIGINT AUTO_INCREMENT PRIMARY KEY,
  at      DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
  user_id INT          NULL,
  action  VARCHAR(64)  NOT NULL,
  target  VARCHAR(128) DEFAULT '',
  detail  TEXT,
  ip      VARCHAR(64)  DEFAULT '',
  INDEX (at)
) ENGINE=InnoDB;

-- ---------------------------------------------------------------- 流程 / 定时
-- 板子上有哪些流程(心跳时上报名字)
CREATE TABLE IF NOT EXISTS board_flows (
  mac   VARCHAR(32) NOT NULL,
  name  VARCHAR(64) NOT NULL,
  text  MEDIUMTEXT  NULL,          -- 流程内容(服务端留底,可恢复误删)
  at    DATETIME    NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  PRIMARY KEY (mac, name)
) ENGINE=InnoDB;

-- 流程的历史版本(每次内容变化存一条,只留最近 10 版)
CREATE TABLE IF NOT EXISTS flow_history (
  id      BIGINT AUTO_INCREMENT PRIMARY KEY,
  mac     VARCHAR(32) NOT NULL,
  name    VARCHAR(64) NOT NULL,
  text    MEDIUMTEXT  NULL,
  saved_at DATETIME   NOT NULL DEFAULT CURRENT_TIMESTAMP,
  INDEX (mac, name, saved_at)
) ENGINE=InnoDB;

-- App 的定时任务
-- ⚠️ 定时是存在**手机 App** 里的(SharedPreferences),板子根本不知道,
--    所以必须由 App 主动上报,服务端才能显示"下次什么时候执行"。
CREATE TABLE IF NOT EXISTS schedules (
  id           BIGINT AUTO_INCREMENT PRIMARY KEY,
  board_mac    VARCHAR(32) NOT NULL,
  device_id    VARCHAR(64) DEFAULT '',
  flow_name    VARCHAR(64) NOT NULL,
  fire_at      DATETIME    NULL,          -- 指定时刻模式
  delay_ms     BIGINT      DEFAULT 0,     -- 倒计时模式
  mode         VARCHAR(16) DEFAULT 'off', -- off / at / delay
  enabled      TINYINT(1)  DEFAULT 0,
  repeat_count INT         DEFAULT 1,
  updated_at   DATETIME    NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  UNIQUE KEY uk_sched (board_mac, flow_name),
  INDEX (board_mac),
  INDEX (fire_at)
) ENGINE=InnoDB;
