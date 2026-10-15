/**
 * 004:后台远程升级固件
 *   - 新表 board_ota:每块板子一行 —— 自动升级开关、待执行的升级命令、最近一次升级的状态
 *   - boards 加列 caps:板子固件报的能力(ota-remote = 认得心跳里的远程升级命令,1.4.0 起)
 */
module.exports = {
  description: '新建 board_ota 表(远程升级)+ boards.caps',
  async up(db, h, log) {
    if (!(await h.tableExists('board_ota'))) {
      await h.ddl(`CREATE TABLE IF NOT EXISTS board_ota (
        mac          VARCHAR(32)  NOT NULL,
        auto         TINYINT(1)   NOT NULL DEFAULT 0  COMMENT '1 = 有新固件时空闲自动升级',
        want_fw_id   BIGINT       NULL     COMMENT '后台手动下发的目标固件 firmware.id(执行完 / 取消后清空)',
        want_at      DATETIME     NULL     COMMENT '手动下发时间(24 小时没执行掉就作废)',
        want_by      INT          NULL     COMMENT '谁下发的(users.id)',
        origin       VARCHAR(8)   NOT NULL DEFAULT '' COMMENT 'manual = 手动 / auto = 自动',
        from_fw      VARCHAR(32)  NOT NULL DEFAULT '' COMMENT '升级前的版本',
        to_fw        VARCHAR(32)  NOT NULL DEFAULT '' COMMENT '目标版本',
        state        VARCHAR(16)  NOT NULL DEFAULT '' COMMENT 'pending / sent / deferred / success / failed / cancelled',
        state_msg    VARCHAR(255) NOT NULL DEFAULT '' COMMENT '状态说明(推迟原因 / 失败原因)',
        state_at     DATETIME     NULL     COMMENT '状态最后变化时间',
        last_fail_fw VARCHAR(32)  NOT NULL DEFAULT '' COMMENT '自动升级失败过的版本(不再自动重试,重开自动升级才清)',
        PRIMARY KEY (mac)
      ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='板子远程升级'`);
      log('  新建表 board_ota');
    }
    if (await h.addColumnIfMissing('boards', 'caps',
      "VARCHAR(64) NOT NULL DEFAULT '' COMMENT '固件能力(ota-remote = 支持后台远程升级)'")) {
      log('  boards 加列 caps');
    }
  },
};
