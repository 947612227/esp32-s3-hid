/**
 * 003:新建 builds 表 —— 后台「一键构建」的构建记录。
 *
 * 一条记录 = 一次构建(App 或固件)。构建成功后产物留在 data/builds/<id>/,
 * 管理员检查后点「发布」才写进 apks / firmware 表(发布后手机会提示更新、板子会收到 OTA)。
 */
module.exports = {
  description: '新建 builds 表(后台一键构建记录)',
  async up(db, h, log) {
    if (await h.tableExists('builds')) return;
    await h.ddl(`CREATE TABLE IF NOT EXISTS builds (
      id            INT          NOT NULL AUTO_INCREMENT,
      kind          VARCHAR(16)  NOT NULL COMMENT 'app = 手机 App;firmware = 板子 OTA 固件(ble 环境)',
      branch        VARCHAR(128) NOT NULL COMMENT '构建的分支(服务器主仓库里的)',
      commit_sha    VARCHAR(40)  NOT NULL DEFAULT '' COMMENT '构建时分支指向的提交',
      commit_msg    VARCHAR(255) NOT NULL DEFAULT '' COMMENT '该提交的标题',
      version_code  INT          NULL     COMMENT 'App 的 versionCode(构建时自动取号);固件为 NULL',
      version_name  VARCHAR(32)  NOT NULL COMMENT 'App 的 versionName / 固件版本号',
      channel       VARCHAR(16)  NULL     COMMENT '固件发布渠道 stable / beta;App 为 NULL',
      notes         TEXT         NULL     COMMENT '更新说明(发布时带过去)',
      status        VARCHAR(16)  NOT NULL DEFAULT 'running'
                    COMMENT 'running / success / failed / cancelled / published',
      error         VARCHAR(255) NOT NULL DEFAULT '' COMMENT '失败原因(一句话)',
      artifact      VARCHAR(255) NOT NULL DEFAULT '' COMMENT '产物文件名(data/builds/<id>/ 下);被清理后置空',
      size          INT          NULL,
      sha256        VARCHAR(64)  NOT NULL DEFAULT '',
      peak_mem_kb   INT          NULL     COMMENT '构建进程内存峰值(KB)',
      published_id  BIGINT       NULL     COMMENT '发布后对应 apks.id / firmware.id',
      created_by    INT          NULL,
      created_at    DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
      finished_at   DATETIME     NULL,
      published_at  DATETIME     NULL,
      PRIMARY KEY (id),
      KEY idx_kind_created (kind, created_at)
    ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='后台一键构建记录'`);
    log('  新建表 builds');
  },
};
