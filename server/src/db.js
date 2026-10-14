/**
 * MySQL 连接池 + 初始化。
 *
 * 为什么用 mysql2:它是**纯 JS** 实现(原生加速是可选的),
 * 服务器上没 g++ 也能 `npm install` 成功。
 */
const mysql = require('mysql2/promise');
const fs = require('fs');
const path = require('path');

const DB = {
  host: process.env.DB_HOST || '127.0.0.1',
  port: parseInt(process.env.DB_PORT || '3306', 10),
  user: process.env.DB_USER || 'touchpad',
  password: process.env.DB_PASS || 'zhangjia',
  database: process.env.DB_NAME || 'touchpad',
};

let pool = null;

function getPool() {
  if (!pool) {
    pool = mysql.createPool({
      ...DB,
      waitForConnections: true,
      connectionLimit: 8,
      charset: 'utf8mb4',
      timezone: 'local',
      dateStrings: false,
    });
  }
  return pool;
}

/** 查询:返回行数组 */
async function q(sql, params) {
  const [rows] = await getPool().execute(sql, params || []);
  return rows;
}

/** 查询:返回第一行 */
async function one(sql, params) {
  const rows = await q(sql, params);
  return rows.length ? rows[0] : null;
}

/** 执行:返回 {affectedRows, insertId} */
async function run(sql, params) {
  const [r] = await getPool().execute(sql, params || []);
  return r;
}

/**
 * 从 counters 表原子取下一个号(并发请求不会拿到同一个号)。
 *
 * LAST_INSERT_ID(expr) 把新值记在**当前连接**上,并随这条 UPDATE 的结果包返回(insertId),
 * 所以加一和取值是同一条语句完成的 —— 连接池里换连接也不会串号。
 * 行被 UPDATE 锁住,另一个请求要等这条提交后才能 +1。
 */
async function nextSeq(name) {
  const r = await run('UPDATE counters SET n = LAST_INSERT_ID(n + 1) WHERE name = ?', [name]);
  if (!r.affectedRows) throw new Error(`计数器 ${name} 不存在(迁移 002 没执行?)`);
  return Number(r.insertId);
}

/**
 * 建库 + 建表(幂等)。
 * 先按 DB_NAME 建库并切过去,再把 sql/schema.sql 跑一遍 —— CREATE TABLE IF NOT EXISTS 是安全的。
 * 老库缺的列由 src/migrate.js 补(boot 里紧接着调用)。
 */
async function initSchema() {
  const f = path.join(__dirname, '..', 'sql', 'schema.sql');
  const sqlText = fs.readFileSync(f, 'utf8');

  // mysql2 默认不允许一次多条语句,这里用 multipleStatements 的独立连接;
  // 不带 database 连接,库不存在时也能先建出来
  const { database, ...noDb } = DB;
  const conn = await mysql.createConnection({ ...noDb, multipleStatements: true });
  try {
    const name = conn.escapeId(database);
    await conn.query(
      `CREATE DATABASE IF NOT EXISTS ${name} CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci`);
    await conn.query(`USE ${name}`);
    await conn.query(sqlText);
  } finally {
    await conn.end();
  }
}

/** 读配置项(没有就返回 def) */
async function getSetting(k, def) {
  const r = await one('SELECT v FROM settings WHERE k = ?', [k]);
  return r ? r.v : def;
}

async function setSetting(k, v) {
  await run(
    'INSERT INTO settings (k, v) VALUES (?, ?) ON DUPLICATE KEY UPDATE v = VALUES(v)',
    [k, String(v)]
  );
}

async function close() {
  if (pool) { await pool.end(); pool = null; }
}

module.exports = { getPool, q, one, run, nextSeq, initSchema, getSetting, setSetting, close, DB };
