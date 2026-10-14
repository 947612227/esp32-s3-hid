/**
 * 把一个库的表结构导成"规范化文本"(列 + 索引),用于和正式库对比。
 * 不比列顺序、不比自增值 —— 只比"代码会不会因为缺表缺列报错"相关的东西。
 *
 * 用法(连接参数走环境变量,和 server/src/db.js 一致):
 *   DB_HOST=127.0.0.1 DB_PORT=3407 DB_USER=x DB_PASS=y DB_NAME=z node schema_dump.js > out.txt
 */
const mysql = require('mysql2/promise');

(async () => {
  const conn = await mysql.createConnection({
    host: process.env.DB_HOST || '127.0.0.1',
    port: parseInt(process.env.DB_PORT || '3306', 10),
    user: process.env.DB_USER,
    password: process.env.DB_PASS,
    database: process.env.DB_NAME,
  });
  const 忽略的表 = new Set(['schema_migrations']);   // 迁移记录表:新机制自带,正式库还没有

  // 1. 列:名字 / 类型 / 可空 / 默认值 / 额外属性
  const [cols] = await conn.query(
    `SELECT TABLE_NAME t, COLUMN_NAME c, COLUMN_TYPE ty, IS_NULLABLE n,
            COLUMN_DEFAULT d, EXTRA e
       FROM information_schema.COLUMNS WHERE TABLE_SCHEMA = DATABASE()
      ORDER BY TABLE_NAME, COLUMN_NAME`);
  for (const r of cols) {
    if (忽略的表.has(r.t)) continue;
    // DEFAULT_GENERATED 只是 MySQL 8 对"表达式默认值"的标记,不影响行为
    const extra = String(r.e || '').replace('DEFAULT_GENERATED', '').trim();
    console.log(`列  ${r.t}.${r.c}  ${r.ty}  ${r.n === 'YES' ? 'NULL' : 'NOT NULL'}  默认=${r.d}  ${extra}`);
  }

  // 2. 索引:按"表 + 是否唯一 + 列组合"比(索引名不比 —— 自动命名在不同建法下可能不同)
  const [idx] = await conn.query(
    `SELECT TABLE_NAME t, INDEX_NAME i, NON_UNIQUE nu,
            GROUP_CONCAT(COLUMN_NAME ORDER BY SEQ_IN_INDEX) cs
       FROM information_schema.STATISTICS WHERE TABLE_SCHEMA = DATABASE()
      GROUP BY TABLE_NAME, INDEX_NAME, NON_UNIQUE`);
  const lines = idx.filter(r => !忽略的表.has(r.t))
    .map(r => `索引 ${r.t}  ${r.i === 'PRIMARY' ? '主键' : (r.nu ? '普通' : '唯一')}(${r.cs})`)
    .sort();
  for (const l of lines) console.log(l);
  await conn.end();
})().catch(e => { console.error('导出失败:', e.message); process.exit(1); });
