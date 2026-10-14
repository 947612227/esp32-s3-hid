/**
 * 数据库结构迁移。
 *
 * 为什么需要:sql/schema.sql 全是 CREATE TABLE IF NOT EXISTS ——
 *   新库能建全,但**老库的表已经存在,新加的列永远加不上**。
 *   以前加列都是手工在正式库上 ALTER,换台服务器全新部署就缺表缺列、接口直接报错。
 *
 * 规则:
 *   - 迁移文件放 sql/migrations/,命名 NNN_说明.js(NNN 三位序号,按文件名顺序执行)
 *   - 每个文件导出 { description, up(db, h) };h 是下面的幂等小工具
 *   - 执行过的记在 schema_migrations 表里,只跑一次
 *   - up() 要写成**可重复执行**的(用 h.addColumnIfMissing 之类),
 *     因为正式库里很多列是以前手工加过的
 */
const fs = require('fs');
const path = require('path');
const db = require('./db');

const MIGRATIONS_DIR = path.join(__dirname, '..', 'sql', 'migrations');

/** DDL 走 query(不走预处理语句) */
async function ddl(sql) {
  await db.getPool().query(sql);
}

/** 幂等小工具:先查 information_schema,不存在才改 */
const h = {
  async tableExists(table) {
    return !!(await db.one(
      'SELECT 1 FROM information_schema.TABLES WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = ?',
      [table]));
  },
  async columnExists(table, column) {
    return !!(await db.one(
      'SELECT 1 FROM information_schema.COLUMNS WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = ? AND COLUMN_NAME = ?',
      [table, column]));
  },
  async indexExists(table, index) {
    return !!(await db.one(
      'SELECT 1 FROM information_schema.STATISTICS WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = ? AND INDEX_NAME = ?',
      [table, index]));
  },
  /** 列不存在才加;返回 true = 这次真的加了 */
  async addColumnIfMissing(table, column, definition) {
    if (await h.columnExists(table, column)) return false;
    await ddl(`ALTER TABLE \`${table}\` ADD COLUMN \`${column}\` ${definition}`);
    return true;
  },
  /** 索引不存在才加;columns 形如 "owner, ver" */
  async addIndexIfMissing(table, index, columns) {
    if (await h.indexExists(table, index)) return false;
    await ddl(`ALTER TABLE \`${table}\` ADD INDEX \`${index}\` (${columns})`);
    return true;
  },
  ddl,
};

/** 列出迁移文件(按文件名排序) */
function listMigrations() {
  if (!fs.existsSync(MIGRATIONS_DIR)) return [];
  return fs.readdirSync(MIGRATIONS_DIR)
    .filter(f => /^\d{3}_.+\.js$/.test(f))
    .sort()
    .map(f => ({ id: f.replace(/\.js$/, ''), file: path.join(MIGRATIONS_DIR, f) }));
}

/**
 * 执行所有没跑过的迁移。
 * @returns {Promise<string[]>} 这次执行了哪些迁移
 */
async function migrate(log = console.log) {
  await ddl(`CREATE TABLE IF NOT EXISTS schema_migrations (
    id         VARCHAR(128) PRIMARY KEY,
    applied_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP
  ) ENGINE=InnoDB COMMENT='已执行的结构迁移'`);

  const done = new Set((await db.q('SELECT id FROM schema_migrations')).map(r => r.id));
  const applied = [];
  for (const m of listMigrations()) {
    if (done.has(m.id)) continue;
    const mod = require(m.file);
    log(`[迁移] 执行 ${m.id}:${mod.description || ''}`);
    // ⚠️ MySQL 的 DDL 会隐式提交,包事务没有意义;所以 up() 必须可重复执行,
    //    中途失败了修好再启动,会从头重跑这个迁移
    await mod.up(db, h, log);
    await db.run('INSERT INTO schema_migrations (id) VALUES (?)', [m.id]);
    applied.push(m.id);
  }
  log(applied.length ? `[迁移] 本次执行 ${applied.length} 个` : '[迁移] 已是最新,无需执行');
  return applied;
}

module.exports = { migrate, listMigrations, h };
