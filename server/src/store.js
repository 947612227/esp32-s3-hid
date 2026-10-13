/**
 * 极简 JSON 文件存储。
 *
 * 为什么不用 SQLite:服务器上 **没有 g++**(只有 gcc),
 * better-sqlite3 / sqlite3 这类原生模块**编译不了**。
 * 而数据量很小(几个板子 + 几个手机),JSON 文件完全够用,
 * 而且零依赖、易备份、出问题能直接打开看。
 *
 * 写入用"临时文件 + rename"保证原子性 ——
 * rename 在同一文件系统上是原子的,断电也不会写出半个文件。
 */
const fs = require('fs');
const path = require('path');

const DATA_DIR = path.join(__dirname, '..', 'data');

function ensureDir(p) {
  if (!fs.existsSync(p)) fs.mkdirSync(p, { recursive: true });
}

class JsonStore {
  /**
   * @param {string} name   文件名(不含 .json)
   * @param {any}    empty  初始值
   */
  constructor(name, empty) {
    ensureDir(DATA_DIR);
    this.file = path.join(DATA_DIR, name + '.json');
    this.data = empty;
    this._load();
  }

  _load() {
    try {
      if (fs.existsSync(this.file)) {
        this.data = JSON.parse(fs.readFileSync(this.file, 'utf8'));
      } else {
        this._save();
      }
    } catch (e) {
      // 文件坏了不要让服务起不来:备份坏文件,用空值继续
      console.error(`[store] ${this.file} 解析失败,已备份并重置:`, e.message);
      try { fs.renameSync(this.file, this.file + '.bad.' + Date.now()); } catch (_) {}
      this._save();
    }
  }

  _save() {
    const tmp = this.file + '.tmp';
    fs.writeFileSync(tmp, JSON.stringify(this.data, null, 2));
    fs.renameSync(tmp, this.file);
  }

  /** 改完记得 save() */
  save() { this._save(); }
}

/** 追加一行 JSONL(用于心跳历史,避免主文件无限增长) */
function appendJsonl(name, obj) {
  ensureDir(DATA_DIR);
  fs.appendFileSync(path.join(DATA_DIR, name + '.jsonl'),
    JSON.stringify(obj) + '\n');
}

/**
 * 裁剪 JSONL,只保留最近 keep 行。
 * 每次追加后调用会有点浪费,所以按条数抽样触发。
 */
function trimJsonl(name, keep) {
  const f = path.join(DATA_DIR, name + '.jsonl');
  try {
    if (!fs.existsSync(f)) return;
    const lines = fs.readFileSync(f, 'utf8').split('\n').filter(Boolean);
    if (lines.length <= keep) return;
    const kept = lines.slice(lines.length - keep);
    const tmp = f + '.tmp';
    fs.writeFileSync(tmp, kept.join('\n') + '\n');
    fs.renameSync(tmp, f);
  } catch (e) {
    console.error('[store] trimJsonl 失败:', e.message);
  }
}

module.exports = { JsonStore, appendJsonl, trimJsonl, DATA_DIR, ensureDir };
