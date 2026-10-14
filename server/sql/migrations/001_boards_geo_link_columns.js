/**
 * 001:boards 表补公网 IP / 地理位置 / 连接方式这些列。
 *
 * 正式库里这些列以前是手工 ALTER 加的(已存在 ⇒ 这里全部跳过);
 * 按旧版 schema.sql 建的库没有它们,心跳写 boards 时会报 Unknown column。
 * 列定义和正式库 SHOW CREATE TABLE 的结果一致。
 */
module.exports = {
  description: 'boards 补 pub_ip / geo_* / link_* 列',
  async up(db, h, log) {
    const cols = [
      ['pub_ip',      "VARCHAR(45) DEFAULT NULL COMMENT '板子所在网络的公网 IP(服务端从心跳源地址取)'"],
      ['geo_country', 'VARCHAR(32) DEFAULT NULL'],
      ['geo_region',  'VARCHAR(64) DEFAULT NULL'],
      ['geo_city',    'VARCHAR(64) DEFAULT NULL'],
      ['geo_isp',     'VARCHAR(96) DEFAULT NULL'],
      ['geo_lat',     'DOUBLE DEFAULT NULL'],
      ['geo_lon',     'DOUBLE DEFAULT NULL'],
      ['geo_at',      "DATETIME DEFAULT NULL COMMENT '地理信息最后更新时间'"],
      ['link_mode',   "VARCHAR(16) DEFAULT NULL COMMENT 'App 报告:当前是怎么连的(ble/usb/wifi/组合)'"],
      ['link_hid',    "VARCHAR(64) DEFAULT NULL COMMENT 'HID 输入设备名'"],
      ['link_by',     "VARCHAR(64) DEFAULT NULL COMMENT '哪个手机报的'"],
      ['link_at',     "DATETIME DEFAULT NULL COMMENT '连接方式最后上报时间'"],
    ];
    for (const [c, def] of cols) {
      if (await h.addColumnIfMissing('boards', c, def)) log(`  boards 加列 ${c}`);
    }
  },
};
