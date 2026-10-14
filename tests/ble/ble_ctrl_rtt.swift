// ============================================================================
//  蓝牙控制通道往返耗时测试(Mac 当 App 用,直连板子的 A5C1 控制服务)
//
//  用法:
//    swift tests/ble/ble_ctrl_rtt.swift [次数] [模式]
//      次数:每种命令测几次(默认 10)
//      模式:serial(默认,发一条等一条,和 App 一样)
//            burst (连发 3 条不等回包,测板子收包不乱帧)
//
//  帧格式(和 Android 端 Ble.java 一致):
//    App → 板子:[4 字节大端正文长度][4 字节 0][正文...],按 MTU 分多次写
//    板子 → App:每片都是 [4 字节大端总长][4 字节大端偏移][数据...]
// ============================================================================
import CoreBluetooth
import Foundation

let 设备名 = "ESP32S3 Touch HID"
let 控制服务 = CBUUID(string: "A5C1")
let 写特征 = CBUUID(string: "A5C2")   // App 写
let 通知特征 = CBUUID(string: "A5C3") // 板子通知

setvbuf(stdout, nil, _IONBF, 0)   // 输出不缓冲(重定向到文件时也能实时看到)
let 参数 = CommandLine.arguments
let 每种次数 = 参数.count > 1 ? Int(参数[1]) ?? 10 : 10
let 模式 = 参数.count > 2 ? 参数[2] : "serial"

final class 测试器: NSObject, CBCentralManagerDelegate, CBPeripheralDelegate {
  var 中心: CBCentralManager!
  var 板子: CBPeripheral?
  var 写口: CBCharacteristic?
  var 写队列: [Data] = []           // 待写的分片
  var 收包缓冲 = Data()
  var 收包总长 = -1
  var 发出时刻: [Double] = []       // 每条请求的发出时间(按顺序对应回包)
  var 待发命令: [String] = []
  var 结果: [String: [Double]] = [:]
  var 当前命令: [String] = []
  var 回包数 = 0
  var 坏帧数 = 0

  override init() {
    super.init()
    中心 = CBCentralManager(delegate: self, queue: nil)
  }

  func centralManagerDidUpdateState(_ c: CBCentralManager) {
    guard c.state == .poweredOn else {
      print("蓝牙状态不可用:\(c.state.rawValue)(4=关闭 3=未授权)")
      if c.state == .unauthorized || c.state == .poweredOff { exit(2) }
      return
    }
    // 先看系统里是否已经连着(Mac 绑定过板子的话会被系统 HID 占着)
    let 已连 = c.retrieveConnectedPeripherals(withServices: [CBUUID(string: "1812"), 控制服务])
    if let p = 已连.first(where: { $0.name == 设备名 }) ?? 已连.first {
      print("系统已连着板子:\(p.name ?? "?"),直接用")
      板子 = p; p.delegate = self; c.connect(p); return
    }
    print("正在扫描 \(设备名) ...")
    c.scanForPeripherals(withServices: nil)
  }

  func centralManager(_ c: CBCentralManager, didDiscover p: CBPeripheral,
                      advertisementData: [String: Any], rssi: NSNumber) {
    let 名 = (advertisementData[CBAdvertisementDataLocalNameKey] as? String) ?? p.name
    guard 名 == 设备名 else { return }
    print("找到板子 RSSI=\(rssi),连接中...")
    c.stopScan()
    板子 = p; p.delegate = self; c.connect(p)
  }

  func centralManager(_ c: CBCentralManager, didConnect p: CBPeripheral) {
    print("已连接,发现服务...")
    p.discoverServices([控制服务])
  }

  func centralManager(_ c: CBCentralManager, didFailToConnect p: CBPeripheral, error: Error?) {
    print("连接失败:\(error?.localizedDescription ?? "?")"); exit(3)
  }

  func centralManager(_ c: CBCentralManager, didDisconnectPeripheral p: CBPeripheral, error: Error?) {
    print("连接断开:\(error?.localizedDescription ?? "无错误")"); 汇总(); exit(4)
  }

  func peripheral(_ p: CBPeripheral, didDiscoverServices error: Error?) {
    guard let s = p.services?.first(where: { $0.uuid == 控制服务 }) else {
      print("没找到控制服务 A5C1"); exit(5)
    }
    p.discoverCharacteristics([写特征, 通知特征], for: s)
  }

  func peripheral(_ p: CBPeripheral, didDiscoverCharacteristicsFor s: CBService, error: Error?) {
    for ch in s.characteristics ?? [] {
      if ch.uuid == 写特征 { 写口 = ch }
      if ch.uuid == 通知特征 { p.setNotifyValue(true, for: ch) }
    }
  }

  func peripheral(_ p: CBPeripheral, didUpdateNotificationStateFor ch: CBCharacteristic, error: Error?) {
    guard ch.isNotifying else { print("订阅通知失败:\(error?.localizedDescription ?? "?")"); exit(6) }
    let mtu = p.maximumWriteValueLength(for: .withoutResponse) + 3
    print("已订阅回包通知,MTU=\(mtu),模式=\(模式),每种命令 \(每种次数) 次")
    if 模式.hasPrefix("cmd=") {
      // 单条命令模式:发一次,打印完整回包(\n 写作 \\n 传进来)
      待发命令 = [String(模式.dropFirst(4)).replacingOccurrences(of: "\\n", with: "\n")]
    } else {
      // 测试命令:PING(最小)、STATUS(中等)、LIST(多片)、LOG 30(大响应,多片)
      for _ in 0..<每种次数 { 待发命令 += ["PING", "STATUS", "LIST", "LOG 30"] }
    }
    下一步()
  }

  /// 发下一条(serial)或一次连发 3 条(burst)
  func 下一步() {
    if 待发命令.isEmpty { 汇总(); exit(0) }
    let n = 模式 == "burst" ? min(3, 待发命令.count) : 1
    for _ in 0..<n { 发送(待发命令.removeFirst()) }
  }

  func 发送(_ 命令: String) {
    guard let p = 板子 else { return }
    let 正文 = Data(命令.utf8)
    var 帧 = Data([UInt8((正文.count >> 24) & 0xFF), UInt8((正文.count >> 16) & 0xFF),
                  UInt8((正文.count >> 8) & 0xFF), UInt8(正文.count & 0xFF), 0, 0, 0, 0])
    帧.append(正文)
    let 片长 = min(240, p.maximumWriteValueLength(for: .withoutResponse))
    var off = 0
    let 空闲 = 写队列.isEmpty
    while off < 帧.count {
      let n = min(片长, 帧.count - off)
      写队列.append(帧.subdata(in: off..<(off + n)))
      off += n
    }
    当前命令.append(命令)
    发出时刻.append(Date().timeIntervalSince1970)
    if 空闲 { 写一片() }
  }

  func 写一片() {
    guard let p = 板子, let w = 写口, let d = 写队列.first else { return }
    p.writeValue(d, for: w, type: .withResponse)   // 和 App 一样:等写确认再写下一片
  }

  func peripheral(_ p: CBPeripheral, didWriteValueFor ch: CBCharacteristic, error: Error?) {
    if let e = error { print("写失败:\(e.localizedDescription)") }
    if !写队列.isEmpty { 写队列.removeFirst() }
    写一片()
  }

  func peripheral(_ p: CBPeripheral, didUpdateValueFor ch: CBCharacteristic, error: Error?) {
    guard let d = ch.value, d.count >= 8 else { return }
    let b = [UInt8](d)
    let 总长 = Int(b[0]) << 24 | Int(b[1]) << 16 | Int(b[2]) << 8 | Int(b[3])
    let 偏移 = Int(b[4]) << 24 | Int(b[5]) << 16 | Int(b[6]) << 8 | Int(b[7])
    if 偏移 == 0 { 收包缓冲 = Data(); 收包总长 = 总长 }
    // 校验:偏移必须正好接在已收数据后面,否则说明丢片/乱序
    if 总长 != 收包总长 || 偏移 != 收包缓冲.count {
      坏帧数 += 1
      print("⚠️ 片头不连续:总长=\(总长) 偏移=\(偏移) 已收=\(收包缓冲.count)")
      return
    }
    收包缓冲.append(d.subdata(in: 8..<d.count))
    guard 收包缓冲.count >= 收包总长 else { return }

    // 一条回包收全
    let 耗时 = (Date().timeIntervalSince1970 - 发出时刻.removeFirst()) * 1000
    let 命令 = 当前命令.removeFirst()
    let 文本 = String(data: 收包缓冲, encoding: .utf8) ?? "<非 UTF-8>"
    let 合法 = 命令 == "PING" ? 文本 == "PONG"
      : 文本.hasPrefix("{") || 文本.hasPrefix("[") || 文本.hasPrefix("\"")
    if !合法 { 坏帧数 += 1; print("⚠️ \(命令) 回包内容异常:\(文本.prefix(80))") }
    let 键 = String(命令.split(separator: " ").first ?? "")
    结果[键, default: []].append(耗时)
    回包数 += 1
    print(String(format: "  %-7@ %7.0f ms  %5d 字节", 键 as NSString, 耗时, 收包总长))
    if 模式.hasPrefix("cmd=") { print("回包内容:\(文本)") }
    收包总长 = -1
    if 当前命令.isEmpty { 下一步() }
  }

  func 汇总() {
    print("\n===== 汇总(回包 \(回包数) 条,异常 \(坏帧数) 条)=====")
    for (k, v) in 结果.sorted(by: { $0.key < $1.key }) {
      let s = v.sorted()
      let 平均 = v.reduce(0, +) / Double(v.count)
      print(String(format: "  %-7@ 次数 %2d  平均 %6.0f ms  中位 %6.0f ms  最大 %6.0f ms",
                   k as NSString, v.count, 平均, s[s.count / 2], s.last!))
    }
  }
}

let 实例 = 测试器()
// 总超时:防止板子不回包时脚本挂死
DispatchQueue.main.asyncAfter(deadline: .now() + Double(每种次数) * 4 * 8 + 30) {
  print("⏱ 总超时,还有 \(实例.当前命令.count) 条没回包:\(实例.当前命令)")
  实例.汇总(); exit(7)
}
RunLoop.main.run()
