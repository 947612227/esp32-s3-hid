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
 * 建表(幂等)。
 * 直接把 sql/schema.sql 跑一遍 —— CREATE TABLE IF NOT EXISTS 是安全的。
 */
async function initSchema() {
  const f = path.join(__dirname, '..', 'sql', 'schema.sql');
  const sqlText = fs.readFileSync(f, 'utf8');

  // mysql2 默认不允许一次多条语句,这里用 multipleStatements 的独立连接
  const conn = await mysql.createConnection({ ...DB, multipleStatements: true });
  try {
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

module.exports = { getPool, q, one, run, initSchema, getSetting, setSetting, close, DB };
