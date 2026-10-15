/**
 * 005:板子备注(以服务器为准,不能重名,见 src/remark.js)
 *   - boards.remark            备注(空串 = 没起)
 *   - boards.remark_conflict   板子离线时在板子网页改的、没被采用的备注(重名 / 不合法),后台标出来
 *   - boards.remark_conflict_at 上面那条是什么时候报上来的
 *
 * ⚠️ 不加唯一索引:查重在 remark.js 里排队做(单进程),空备注也不用特殊处理
 */
module.exports = {
  description: 'boards 加列 remark / remark_conflict / remark_conflict_at(板子备注)',
  async up(db, h, log) {
    if (await h.addColumnIfMissing('boards', 'remark',
      "VARCHAR(64) NOT NULL DEFAULT '' COMMENT '板子备注(如 大麦1号;以服务器为准,不能重名)'")) {
      log('  boards 加列 remark');
    }
    if (await h.addColumnIfMissing('boards', 'remark_conflict',
      "VARCHAR(64) NOT NULL DEFAULT '' COMMENT '板子上改的、没被采用的备注(重名 / 不合法)'")) {
      log('  boards 加列 remark_conflict');
    }
    if (await h.addColumnIfMissing('boards', 'remark_conflict_at',
      "DATETIME NULL COMMENT '没采用的备注是什么时候报上来的'")) {
      log('  boards 加列 remark_conflict_at');
    }
  },
};
