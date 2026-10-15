/* ============================================================================
 *  触摸注入板 · 管理后台(Vue 3,无构建工具)
 *
 *  为什么用 CDN 版而不是 vite + SFC:
 *    ① 服务器是 CentOS 7 + Node 16 —— vite 5 要 Node 18+,会被卡住
 *    ② 多一条构建链就多一处会坏的地方,而这个后台只有内部几个人用
 *    ③ vue.global.prod.js 154KB,托管在 /vendor/ 下,不依赖外网 CDN
 *
 *  ⇒ 模板写在 index.html 里,Vue 用运行时编译器编译 ✅
 * ========================================================================== */
(function () {
  'use strict';
  const { createApp, ref, reactive, computed, onMounted, onUnmounted } = Vue;

  // ---------------------------------------------------------------- 工具

  /** 时间戳/字符串 → 毫秒。
   *  ⚠️ 后端已经统一转毫秒了,这里防的是万一(MySQL DATETIME 混合时) */
  function toMs(t) {
    if (!t) return 0;
    if (typeof t === 'number') return t;
    const n = Date.parse(t);
    return isNaN(n) ? 0 : n;
  }
  function fmtTime(ms) {
    ms = toMs(ms);
    if (!ms) return '—';
    const d = new Date(ms), p = n => String(n).padStart(2, '0');
    return `${p(d.getMonth() + 1)}-${p(d.getDate())} ${p(d.getHours())}:${p(d.getMinutes())}`;
  }
  function ago(ms) {
    ms = toMs(ms);
    if (!ms) return '—';
    const s = Math.floor((Date.now() - ms) / 1000);
    if (s < 0) return '刚刚';
    if (s < 60) return s + ' 秒前';
    if (s < 3600) return Math.floor(s / 60) + ' 分钟前';
    if (s < 86400) return Math.floor(s / 3600) + ' 小时前';
    return Math.floor(s / 86400) + ' 天前';
  }
  function size(n) {
    if (!n) return '—';
    return n > 1048576 ? (n / 1048576).toFixed(2) + ' MB' : (n / 1024).toFixed(0) + ' KB';
  }
  function linkName(m) {
    return {
      ble: '🔵 蓝牙', usb: '🔌 USB', wifi: '📶 WiFi',
      'ble+wifi': '🔵 蓝牙 + 📶 WiFi', 'usb+wifi': '🔌 USB + 📶 WiFi',
      none: '⚪ 未连接',
    }[m] || (m || '未知');
  }

  // ---------------------------------------------------------------- 主应用

  const MENUS = [
    { k: 'home',     i: '🏠', n: '主页' },
    { k: 'control',  i: '🎛', n: '控制中心' },
    { k: 'devices',  i: '📱', n: '设备管理' },
    { k: 'flows',    i: '📄', n: '流程管理' },
    { k: 'users',    i: '👤', n: '用户管理' },
    { k: 'apps',     i: '📦', n: 'APP 管理' },
    { k: 'firmware', i: '💾', n: '固件管理' },
    { k: 'build',    i: '🛠', n: '一键构建' },
    { k: 'usb',      i: '🔌', n: 'USB 刷机' },
    { k: 'logs',     i: '📋', n: '日志系统' },
    { k: 'stats',    i: '📊', n: '数据统计' },
  ];

  createApp({
    setup() {
      // ---- 全局状态 ----
      const page    = ref('home');
      const errMsg  = ref('');
      const clock   = ref('');
      const bootAt  = window.TP_BOOT_AT || 0;
      const ver     = window.TP_VER || '—';
      const built   = window.TP_BUILT || '—';
      const ME      = window.TP_USER || null;

      const menus = MENUS;
      const curTitle = computed(() => (menus.find(m => m.k === page.value) || {}).n || '主页');

      // ---- 数据 ----
      const summary = reactive({ boards: 0, online: 0, phones: 0, firmware: 0, apk: 0 });
      const boards  = ref([]);
      const phones  = ref([]);
      const apks    = ref([]);
      const fws     = ref([]);
      const users   = ref([]);
      const stats   = ref([]);
      const statsSummary = ref('');
      const events  = ref([]);
      const audit   = ref([]);
      const flows   = ref([]);
      const flowOwners = ref([]);
      const flowFilter = ref('');

      // 分页状态(审计 / 事件)
      const pg = reactive({
        audit:  { page: 1, size: 50, total: 0, pages: 1 },
        events: { page: 1, size: 50, total: 0, pages: 1 },
      });

      // 上传表单
      const apkForm = reactive({ code: '', name: '', notes: '', file: null });
      const fwForm  = reactive({ version: '', channel: 'stable', notes: '', file: null });
      const newUser = reactive({ name: '', pass: '', role: 'viewer', display: '' });

      // 弹窗
      // 弹窗:板子详情 / 流程备份 / 升级策略 / 灯
      const dlg      = reactive({ show: false, board: null, policy: null, mac: '' });
      const flowDlg  = reactive({ show: false, mac: '', flows: [], history: [] });
      const polDlg   = reactive({ show: false, mac: '', mode: 'none', target: '' });
      const ledDlg   = reactive({ show: false, mac: '' });

      // 一键构建:元信息(环境 / 分支 / 下一个 versionCode)、记录、两张表单、日志弹窗
      const bmeta   = reactive({ env: null, branches: [], branchError: '', defaultBranch: '',
                                 nextVersionCode: 0, latest: { apk: null, firmware: {} }, running: null,
                                 repo: '' });
      const builds  = ref([]);
      const bform   = reactive({
        app:      { branch: '', versionName: '', notes: '' },
        firmware: { branch: '', versionName: '', channel: 'stable', notes: '' },
      });
      const logDlg  = reactive({ show: false, id: 0, text: '', offset: 0, status: '', follow: true });
      let logTimer = null;

      // 远程升级:控制中心勾选的板子、选固件的弹窗
      const sel     = reactive({});               // mac → true(勾选)
      const otaDlg  = reactive({ show: false, macs: [], fwId: '', result: [] });

      // USB 刷机页:浏览器能力 + 选的固件 / 模式 + 进度日志
      const usb = reactive({
        secure: !!window.isSecureContext,
        hasSerial: !!navigator.serial,
        origin: location.origin,
        src: '', mode: 'keep', running: false,
        pct: 0, stage: '', log: '', result: '',
      });

      /** 打开灯对话框(模板里直接改 ledDlg 也行,但集中一处更清楚) */
      function openLed(mac) { ledDlg.mac = mac; ledDlg.show = true; }

      // 上传进度(简单文案)
      const busy = ref('');

      // ---------------------------------------------------------------- 请求

      /**
       * 统一请求。
       *
       * ⚠️ 出错**必须让人看见** —— 原来接口 404 时页面一直显示"加载中…",
       *    看不出任何问题,排查了很久。
       */
      async function api(url, opt) {
        opt = opt || {};
        opt.credentials = 'same-origin';
        let r;
        try {
          r = await fetch(url, opt);
        } catch (e) {
          errMsg.value = '网络请求失败:' + url + ' — ' + e.message;
          throw e;
        }
        if (r.status === 401) { location.href = '/admin/login'; throw new Error('未登录'); }
        const ct = r.headers.get('content-type') || '';
        const body = ct.includes('json') ? await r.json() : await r.text();
        if (!r.ok) {
          const m = (body && body.message) || ('HTTP ' + r.status);
          errMsg.value = url + ' → ' + m;
          throw new Error(m);
        }
        errMsg.value = '';
        return body;
      }

      // ---------------------------------------------------------------- 加载

      async function loadSummary() {
        const d = await api('/api/admin/summary');
        Object.assign(summary, {
          boards: d.boards || 0, online: d.online || 0, phones: d.phones || 0,
          firmware: d.firmware || 0, apk: d.apk || 0,
        });
      }

      async function loadBoards() {
        const d = await api('/api/admin/boards');
        boards.value = d.boards || [];
      }

      async function loadPhones() {
        const d = await api('/api/admin/phones');
        phones.value = d.phones || [];
      }

      async function loadApk()  { apks.value = (await api('/api/admin/apk')).list || []; }
      async function loadFw()   { fws.value  = (await api('/api/admin/firmware')).list || []; }
      async function loadUsers(){ users.value = (await api('/api/admin/users')).users || []; }

      /** 流程管理:服务器上的权威流程(按 owner 分组) */
      async function loadFlows() {
        const d = await api('/api/admin/flows' + (flowFilter.value
          ? '?owner=' + encodeURIComponent(flowFilter.value) : ''));
        flows.value = d.flows || [];
        flowOwners.value = d.owners || [];
      }

      /** 流程状态:优先用 status 字段,老数据回退到 deleted */
      function flowStatus(f) {
        if (f.deleted) return 'deleted';
        return f.status || 'enabled';
      }
      function flowStatusText(f) {
        return { enabled: '启用', disabled: '禁用', deleted: '已删除' }[flowStatus(f)] || '—';
      }
      function flowStatusClass(f) {
        return flowStatus(f) === 'enabled' ? 'on' : 'off';
      }

      /** 改流程状态(启用 / 禁用 / 删除) */
      async function setFlowStatus(owner, name, status) {
        const label = { enabled: '启用', disabled: '禁用', deleted: '删除' }[status] || status;
        if (status !== 'enabled' && !confirm(`把「${name}」设为「${label}」?\n\n`
            + (status === 'disabled'
                ? '禁用后客户端**拉不到**它,板子上那份会被移除(服务器保留记录)。'
                : '删除后客户端也会移除它。'))) return;
        await api('/api/admin/flows/status', {
          method: 'POST',
          headers: { 'Content-Type': 'application/json' },
          body: JSON.stringify({ owner, name, status }),
        }).catch(e => { alert('失败:' + e.message); throw e; });
        await loadFlows();
      }

      async function loadStats() {
        const d = await api('/api/admin/event-stats?days=30');
        stats.value = d.stats || [];
        const t = stats.value.reduce((s, x) => s + (x.count || 0), 0);
        statsSummary.value = `共 ${t} 次成功`;
      }

      async function loadEvents() {
        const st = pg.events;
        const d = await api(`/api/admin/events?page=${st.page}&size=${st.size}`);
        if (st.page > (d.pages || 1)) { st.page = d.pages || 1; return loadEvents(); }
        events.value = d.events || d.list || [];
        st.total = d.total || 0; st.pages = d.pages || 1;
      }

      async function loadAudit() {
        const st = pg.audit;
        const d = await api(`/api/admin/audit?page=${st.page}&size=${st.size}`);
        if (st.page > (d.pages || 1)) { st.page = d.pages || 1; return loadAudit(); }
        audit.value = d.list || [];
        st.total = d.total || 0; st.pages = d.pages || 1;
      }

      // ---------------------------------------------------------------- 一键构建

      async function loadBuildMeta() {
        const d = await api('/api/admin/build/meta');
        Object.assign(bmeta, {
          env: d.env, branches: d.branches || [], branchError: d.branchError || '',
          defaultBranch: d.defaultBranch || '', nextVersionCode: d.nextVersionCode || 0,
          latest: d.latest || { apk: null, firmware: {} }, running: d.running, repo: d.repo || '',
        });
        // 分支默认选优化分支;已经选了的不覆盖(10 秒刷新一次,别把人家选的冲掉)
        for (const k of ['app', 'firmware']) {
          if (!bform[k].branch || !bmeta.branches.some(b => b.name === bform[k].branch)) {
            bform[k].branch = bmeta.defaultBranch;
          }
        }
      }
      async function loadBuilds() {
        const d = await api('/api/admin/builds');
        builds.value = d.list || [];
        bmeta.running = d.running;
      }
      function branchOf(name) { return bmeta.branches.find(b => b.name === name) || null; }
      function envReady(k) { return !!(bmeta.env && bmeta.env[k] && bmeta.env[k].ready); }

      const BUILD_STATUS = {
        running: '⏳ 构建中', success: '✅ 成功(未发布)', failed: '❌ 失败',
        cancelled: '⏹ 已取消', publishing: '⏳ 发布中', published: '🚀 已发布',
      };
      function buildStatusText(s) { return BUILD_STATUS[s] || s; }
      /** 用时:跑完的用结束时间,在跑的用现在 */
      function buildSecs(b) {
        const end = b.finishedAt || (b.status === 'running' ? Date.now() : 0);
        if (!end || !b.createdAt) return '—';
        const s = Math.max(0, Math.round((end - b.createdAt) / 1000));
        return s >= 60 ? Math.floor(s / 60) + ' 分 ' + (s % 60) + ' 秒' : s + ' 秒';
      }

      async function startBuild(kind) {
        const f = bform[kind];
        if (!f.branch) return alert('先选分支');
        if (!f.versionName.trim()) return alert(kind === 'app' ? '要填版本名(如 3.6)' : '要填固件版本号(如 1.3.1)');
        const what = kind === 'app'
          ? `App ${f.versionName}(versionCode ${bmeta.nextVersionCode},自动取号)`
          : `固件 ${f.versionName}(${f.channel} 渠道)`;
        if (!confirm(`在服务器上构建 ${what}\n分支:${f.branch}\n\n`
          + `构建完先放在构建记录里,检查没问题再点「发布」才会下发。`
          + (kind === 'firmware' ? '\n固件首次构建要十几分钟,之后只编改动的文件。' : ''))) return;
        const d = await api('/api/admin/builds', {
          method: 'POST',
          headers: { 'Content-Type': 'application/json' },
          body: JSON.stringify({ kind, branch: f.branch, versionName: f.versionName.trim(),
                                 channel: f.channel, notes: f.notes }),
        }).catch(e => { alert('没能开始构建:' + e.message); throw e; });
        f.versionName = ''; f.notes = '';
        await Promise.all([loadBuilds(), loadBuildMeta()]);
        openLog(d.id);
      }

      async function publishBuild(b) {
        const what = b.kind === 'app'
          ? `App ${b.versionName}(versionCode ${b.versionCode})\n\n发布后手机打开 App 时会提示更新。`
          : `固件 ${b.versionName} 到 ${b.channel} 渠道\n\n发布后:这个渠道的板子在 App 里会提示更新;`
            + `开了「自动升级」的板子会在空闲时自己升级。`;
        if (!confirm('发布 ' + what + '\n旧版本按保留策略只留最近 5 个。')) return;
        await api('/api/admin/builds/' + b.id + '/publish', { method: 'POST' })
          .catch(e => { alert('发布失败:' + e.message); throw e; });
        await loadBuilds();
        alert('已发布,可以在「' + (b.kind === 'app' ? 'APP 管理' : '固件管理') + '」里看到');
      }

      async function cancelBuild(b) {
        if (!confirm('停止构建 #' + b.id + '?')) return;
        await api('/api/admin/builds/' + b.id + '/cancel', { method: 'POST' })
          .catch(e => { alert('停止失败:' + e.message); throw e; });
        setTimeout(loadBuilds, 1500);
      }

      /** 日志弹窗:每 2 秒追加读一次,构建结束后再读最后一次就停 */
      function openLog(id) {
        Object.assign(logDlg, { show: true, id, text: '', offset: 0, status: '', follow: true });
        clearTimeout(logTimer);
        pollLog();
      }
      async function pollLog() {
        if (!logDlg.show) return;
        const id = logDlg.id;
        let d;
        try { d = await api(`/api/admin/builds/${id}/log?offset=${logDlg.offset}`); } catch (e) { d = null; }
        if (!logDlg.show || logDlg.id !== id) return;
        if (d) {
          if (d.text) {
            logDlg.text += d.text;
            if (logDlg.text.length > 2000000) logDlg.text = logDlg.text.slice(-1500000);
          }
          logDlg.offset = d.offset;
          logDlg.status = d.status;
          if (logDlg.follow) Vue.nextTick(() => {
            const el = document.getElementById('buildLog');
            if (el) el.scrollTop = el.scrollHeight;
          });
          // 还有没读完的(一次最多 256KB)就马上接着读
          if (d.offset < d.size) { logTimer = setTimeout(pollLog, 50); return; }
          if (d.done) { loadBuilds(); return; }
        }
        logTimer = setTimeout(pollLog, 2000);
      }
      function closeLog() { logDlg.show = false; clearTimeout(logTimer); }

      /** 只加载当前页要用的 —— 别一次拉全部 ✅ */
      function loadPage(p) {
        const jobs = [];
        if (p === 'home')     jobs.push(loadSummary());
        if (p === 'control')  jobs.push(loadBoards(), loadSummary());
        if (p === 'devices')  jobs.push(loadPhones());
        if (p === 'users')    jobs.push(loadUsers());
        if (p === 'apps')     jobs.push(loadApk());
        if (p === 'firmware') jobs.push(loadFw());
        if (p === 'build')    jobs.push(loadBuildMeta(), loadBuilds());
        // 刷机进行中别刷新列表(下拉框选项变了会让人误会)
        if (p === 'usb' && !usb.running) jobs.push(loadFw(), loadBuilds());
        if (p === 'flows')    jobs.push(loadFlows());
        if (p === 'logs')     jobs.push(loadAudit());
        if (p === 'stats')    jobs.push(loadStats(), loadEvents());
        return Promise.all(jobs.map(x => Promise.resolve(x).catch(e => console.error(e))));
      }
      function loadAll() { return loadPage(page.value); }

      // ---------------------------------------------------------------- 路由

      function go(k, push) {
        if (!menus.some(m => m.k === k)) k = 'home';
        page.value = k;
        if (push !== false && location.hash !== '#' + k) location.hash = k;
        loadPage(k);
      }

      // ---------------------------------------------------------------- 操作

      /** 点灯:blink / off / auto / color:r,g,b */
      async function fireLed(mac, what) {
        let body;
        if (what === 'blink')     body = { mac, mode: 'blink' };
        else if (what === 'off')  body = { mac, mode: 'off' };
        else if (what === 'auto') body = { mac, mode: 'auto' };
        else {
          const p = what.replace('color:', '').split(',').map(Number);
          body = { mac, mode: 'color', r: p[0], g: p[1], b: p[2], ms: 0 };
        }
        const r = await api('/api/admin/led', {
          method: 'POST',
          headers: { 'Content-Type': 'application/json' },
          body: JSON.stringify(body),
        }).catch(e => { errMsg.value = '点灯失败:' + e.message; });
        ledDlg.show = false;
        if (r && r.ok) alert(r.message);
      }

      /** 板子详情(含策略)。⚠️ 真实接口是 /api/admin/board/:mac,返回 {board, policy} */
      async function showBoard(mac) {
        const d = await api('/api/admin/board/' + encodeURIComponent(mac));
        dlg.board = d.board || {};
        dlg.policy = d.policy || null;
        dlg.mac = mac;
        dlg.show = true;
      }

      /** 流程备份 —— 服务器留底(接口是 /api/admin/flow-backup?mac=) */
      async function showFlows(mac) {
        const d = await api('/api/admin/flow-backup?mac=' + encodeURIComponent(mac));
        flowDlg.mac = mac;
        flowDlg.flows = d.flows || [];
        flowDlg.history = d.history || [];
        flowDlg.show = true;
      }

      /**
       * 固件**升级策略**。
       *
       * ⚠️ 不是"限制跑多久/几次" —— 那是另一回事。
       *    这里管的是 OTA:allow=允许升级 / deny=永不升级 / none=清除(跟随通道)。
       */
      function setPolicy(mac) {
        polDlg.mac = mac;
        polDlg.mode = 'allow';
        polDlg.target = '';
        polDlg.show = true;
      }

      async function savePolicy() {
        await api('/api/admin/policy', {
          method: 'POST',
          headers: { 'Content-Type': 'application/json' },
          body: JSON.stringify({
            mac: polDlg.mac, mode: polDlg.mode,
            target: polDlg.target || null,
          }),
        });
        polDlg.show = false;
        await loadBoards();
      }

      async function uploadApk() {
        if (!apkForm.file) return alert('先选 APK 文件');
        if (!apkForm.code) return alert('要填 versionCode');
        const fd = new FormData();
        fd.append('file', apkForm.file);
        fd.append('versionCode', apkForm.code);
        fd.append('versionName', apkForm.name || '');
        fd.append('notes', apkForm.notes || '');
        busy.value = '上传 APK 中…';
        try {
          await api('/api/admin/apk', { method: 'POST', body: fd });
          apkForm.file = null; apkForm.code = ''; apkForm.name = ''; apkForm.notes = '';
          await loadApk();
          alert('上传成功');
        } finally { busy.value = ''; }
      }

      async function uploadFw() {
        if (!fwForm.file) return alert('先选固件文件');
        if (!fwForm.version) return alert('要填版本号');
        const fd = new FormData();
        fd.append('file', fwForm.file);
        fd.append('version', fwForm.version);
        fd.append('channel', fwForm.channel || 'stable');
        fd.append('notes', fwForm.notes || '');
        busy.value = '上传固件中…';
        try {
          await api('/api/admin/firmware', { method: 'POST', body: fd });
          fwForm.file = null; fwForm.version = ''; fwForm.notes = '';
          await loadFw();
          alert('上传成功');
        } finally { busy.value = ''; }
      }

      async function delFw(id) {
        if (!confirm('确定删除这个固件?')) return;
        await api('/api/admin/firmware/' + id, { method: 'DELETE' });
        await loadFw();
      }

      async function addUser() {
        if (!newUser.name || !newUser.pass) return alert('用户名和密码必填');
        await api('/api/admin/users', {
          method: 'POST',
          headers: { 'Content-Type': 'application/json' },
          body: JSON.stringify({
            username: newUser.name, password: newUser.pass,
            role: newUser.role, display_name: newUser.display || newUser.name,
          }),
        }).catch(e => { alert('添加失败:' + e.message); throw e; });
        newUser.name = ''; newUser.pass = ''; newUser.display = '';
        await loadUsers();
      }

      async function chpw(u) {
        const p = prompt('给 ' + u.username + ' 设新密码:');
        if (!p) return;
        await api('/api/admin/users/' + u.id + '/password', {
          method: 'POST',
          headers: { 'Content-Type': 'application/json' },
          body: JSON.stringify({ password: p }),
        });
        alert('已改密码');
      }

      async function toggleUser(u) {
        // ⚠️ 真实接口是 POST /api/admin/users/:id/enabled(不是 PATCH /users/:id)
        await api('/api/admin/users/' + u.id + '/enabled', {
          method: 'POST',
          headers: { 'Content-Type': 'application/json' },
          body: JSON.stringify({ enabled: !u.enabled }),
        }).catch(e => { alert('操作失败:' + e.message); throw e; });
        await loadUsers();
      }

      async function delUser(u) {
        if (!confirm('确定删除用户 ' + u.username + ' ?')) return;
        await api('/api/admin/users/' + u.id, { method: 'DELETE' });
        await loadUsers();
      }

      // ---------------------------------------------------------------- 远程升级

      const selMacs = computed(() => boards.value.filter(b => sel[b.mac]).map(b => b.mac));
      function selAll(on) { for (const b of boards.value) sel[b.mac] = !!on; }

      /** 升级状态的颜色:成功绿 / 失败红 / 进行中黄 */
      function otaClass(o) {
        if (!o || !o.state) return '';
        return { success: 'ok', failed: 'err', cancelled: '' }[o.state] ?? 'warn';
      }

      /** 打开「升级固件」弹窗(macs:一块或多块) */
      async function openOta(macs) {
        if (!macs.length) return alert('先勾选板子');
        await loadFw();
        Object.assign(otaDlg, { show: true, macs: macs.slice(), fwId: fws.value.length ? fws.value[0].id : '', result: [] });
      }
      async function sendOta() {
        if (!otaDlg.fwId) return alert('先选固件版本');
        const f = fws.value.find(x => x.id === otaDlg.fwId);
        if (!confirm(`给 ${otaDlg.macs.length} 块板子升级到 ${f ? f.version : otaDlg.fwId}?\n\n`
          + `板子下次心跳(最多 2 分钟)收到命令;正在跑流程或 15 分钟内有定时任务时会推迟,空闲了再升。\n`
          + `板子下载完会核对 sha256,不一致不刷。`)) return;
        const d = await api('/api/admin/ota', {
          method: 'POST', headers: { 'Content-Type': 'application/json' },
          body: JSON.stringify({ macs: otaDlg.macs, fwId: otaDlg.fwId }),
        }).catch(e => { alert('下发失败:' + e.message); throw e; });
        otaDlg.result = d.results || [];
        await loadBoards();
      }
      async function cancelOta(mac) {
        if (!confirm('取消 ' + mac + ' 的升级?')) return;
        const d = await api('/api/admin/ota/cancel', {
          method: 'POST', headers: { 'Content-Type': 'application/json' }, body: JSON.stringify({ mac }),
        });
        alert(d.message);
        await loadBoards();
      }
      async function setOtaAuto(macs, on) {
        if (!macs.length) return alert('先勾选板子');
        if (on && !confirm(`给 ${macs.length} 块板子打开自动升级?\n\n`
          + `以后这个渠道发布新固件(或升级策略指定了版本),板子空闲时会自己升级。`)) return;
        await api('/api/admin/ota/auto', {
          method: 'POST', headers: { 'Content-Type': 'application/json' },
          body: JSON.stringify({ macs, auto: on }),
        }).catch(e => { alert('设置失败:' + e.message); throw e; });
        await loadBoards();
      }

      // ---------------------------------------------------------------- 备注

      /** 名字 + 备注,如「ESP32-S3_098C(大麦1号)」 */
      function boardLabel(name, remark) {
        return (name || '') + (remark ? `(${remark})` : '');
      }
      /** 改备注:服务器查重,板子下次心跳(或 App 代报)时同步 */
      async function editRemark(b) {
        const v = prompt(`给 ${b.name || b.mac} 起个备注(最多 20 个字,不能和别的板子重名;留空 = 清掉):`,
                         b.remark || '');
        if (v === null) return;
        const d = await api(`/api/admin/boards/${encodeURIComponent(b.mac)}/remark`, {
          method: 'POST', headers: { 'Content-Type': 'application/json' },
          body: JSON.stringify({ remark: v }),
        }).catch(e => { alert('没改成:' + e.message); throw e; });
        alert(d.message);
        await loadBoards();
        if (dlg.show && dlg.mac === b.mac) await showBoard(b.mac);
      }

      // ---------------------------------------------------------------- USB 刷机

      /** 可整片刷的来源:已发布固件(带刷机文件的)+ 构建成功还没发布的固件 */
      const usbSources = computed(() => {
        const out = [];
        for (const f of fws.value) {
          if (f.usbFlash) out.push({ src: 'fw-' + f.id, label: `${f.version}(${f.channel},已发布 ${fmtTime(f.createdAt)})` });
        }
        for (const b of builds.value) {
          if (b.kind === 'firmware' && b.status === 'success' && b.usbFlash) {
            out.push({ src: 'build-' + b.id, label: `${b.versionName}(构建 #${b.id},未发布)` });
          }
        }
        return out;
      });

      function usbLog(s) {
        usb.log += s + '\n';
        Vue.nextTick(() => { const el = document.getElementById('usbLog'); if (el) el.scrollTop = el.scrollHeight; });
      }

      async function usbFlash() {
        if (!usb.src) return alert('先选要刷的固件');
        const erase = usb.mode === 'erase';
        if (erase && !confirm('「全新板子」会先整片擦除:板子上的 WiFi 配置、流程、定时任务全部清空。\n确定?')) return;
        // ⚠️ requestPort 必须紧跟着点击调用(浏览器要求用户手势),所以放在任何 await 网络请求之前
        let port;
        try {
          port = await navigator.serial.requestPort();
        } catch (e) {
          return usbLog('没有选串口:' + e.message);
        }
        Object.assign(usb, { running: true, pct: 0, stage: '', log: '', result: '' });
        try {
          usbLog('加载刷机工具…');
          const esptool = await import('/admin/static/vendor/esptool-js/bundle.js');
          const uf = await import('/admin/static/usbflash.js');
          usbLog('下载固件文件…');
          const plan = await uf.fetchFlashPlan(usb.src, u => fetch(u, { credentials: 'same-origin' }));
          usbLog(`固件 ${plan.label}:` + plan.files.map(f => `${f.name}@0x${f.address.toString(16)} ${size(f.data.length)}`).join(' / '));
          const r = await uf.flashBoard({
            esptool, port, plan, eraseAll: erase, log: usbLog,
            onProgress: (done, total, name) => {
              usb.pct = total ? Math.floor(done * 100 / total) : 0;
              usb.stage = name;
            },
          });
          usb.result = `✅ 刷好了:${plan.version}` + (r.mac ? `(MAC ${r.mac})` : '')
            + (erase ? '。板子会先连固件里默认的 WiFi,连不上就开热点等 App 配网;来心跳后出现在控制中心。'
                     : '。板子重启后会照常连 WiFi 来心跳。');
          usbLog(usb.result);
        } catch (e) {
          usb.result = '❌ 失败:' + (e && e.message || e);
          usbLog(usb.result);
        } finally {
          usb.running = false;
        }
      }

      // ---------------------------------------------------------------- 分页

      function pgGo(key, p) {
        const st = pg[key];
        st.page = Math.max(1, Math.min(p, st.pages || 1));
        if (key === 'audit') loadAudit(); else loadEvents();
      }
      function pgInfo(key) {
        const st = pg[key];
        const from = st.total ? (st.page - 1) * st.size + 1 : 0;
        const to = Math.min(st.page * st.size, st.total);
        return { from, to };
      }

      // ---------------------------------------------------------------- 时钟

      let timer = null;
      onMounted(() => {
        const tick = () => { clock.value = new Date().toLocaleTimeString('zh-CN'); };
        tick();
        timer = setInterval(tick, 1000);
        go(location.hash.slice(1) || 'home', false);
        window.addEventListener('hashchange', () => go(location.hash.slice(1), false));
        // 每 10 秒刷新当前页
        timer2 = setInterval(() => loadPage(page.value), 10000);
      });
      let timer2 = null;
      onUnmounted(() => { clearInterval(timer); clearInterval(timer2); clearTimeout(logTimer); });

      return {
        // 状态
        page, menus, curTitle, errMsg, clock, bootAt, ver, built, ME,
        summary, boards, phones, apks, fws, users, stats, statsSummary,
        events, audit, pg, apkForm, fwForm, newUser, dlg, flowDlg, polDlg, ledDlg, busy,
        flows, flowOwners, flowFilter,
        bmeta, builds, bform, logDlg,
        sel, selMacs, otaDlg, usb, usbSources,
        // 方法
        selAll, otaClass, openOta, sendOta, cancelOta, setOtaAuto, usbFlash,
        boardLabel, editRemark,
        go, loadAll, loadPage, loadBoards, loadPhones, loadApk, loadFw,
        loadUsers, loadStats, loadEvents, loadAudit, loadSummary, loadFlows,
        setFlowStatus, flowStatus, flowStatusText, flowStatusClass,
        fireLed, showBoard, showFlows, setPolicy, savePolicy, openLed,
        uploadApk, uploadFw, delFw,
        addUser, chpw, toggleUser, delUser,
        pgGo, pgInfo,
        loadBuildMeta, loadBuilds, branchOf, envReady, buildStatusText, buildSecs,
        startBuild, publishBuild, cancelBuild, openLog, closeLog,
        // 工具
        fmtTime, ago, size, linkName,
      };
    },
  }).mount('#app');
})();
