/**
 * 002:新建全局计数器表 counters,流程版本号 flows.ver 改从这里原子取号。
 *
 * 以前是"先 SELECT MAX(ver) 再 +1":两个请求同时保存会拿到**同一个号**,
 * 而且号只在单个 owner 内递增,拉取接口返回的却是全表 MAX(ver)。
 * 种子取现有 flows 的最大 ver ⇒ 之后发的号一定比所有旧号大,
 * 已有数据和 App 记着的 baseVer 都不受影响。
 */
module.exports = {
  description: '新建 counters 表,flow_ver 种子 = 现有最大 ver',
  async up(db, h, log) {
    if (!(await h.tableExists('counters'))) {
      await h.ddl(`CREATE TABLE IF NOT EXISTS counters (
        name VARCHAR(32) NOT NULL COMMENT '计数器名(flow_ver = 流程版本号)',
        n    BIGINT      NOT NULL DEFAULT 0 COMMENT '当前值;取号用 UPDATE ... SET n = LAST_INSERT_ID(n + 1)',
        PRIMARY KEY (name)
      ) ENGINE=InnoDB COMMENT='全局递增计数器(并发安全取号)'`);
      log('  新建表 counters');
    }
    // 可重复执行:只会把计数器往大调,不会调小
    await db.run("INSERT IGNORE INTO counters (name, n) VALUES ('flow_ver', 0)");
    await db.run(
      `UPDATE counters SET n = GREATEST(n, (SELECT COALESCE(MAX(ver), 0) FROM flows))
        WHERE name = 'flow_ver'`);
    const r = await db.one("SELECT n FROM counters WHERE name = 'flow_ver'");
    log(`  flow_ver 计数器 = ${r ? r.n : '?'}`);
  },
};
