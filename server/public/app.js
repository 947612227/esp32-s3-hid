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

      /** 只加载当前页要用的 —— 别一次拉全部 ✅ */
      function loadPage(p) {
        const jobs = [];
        if (p === 'home')     jobs.push(loadSummary());
        if (p === 'control')  jobs.push(loadBoards(), loadSummary());
        if (p === 'devices')  jobs.push(loadPhones());
        if (p === 'users')    jobs.push(loadUsers());
        if (p === 'apps')     jobs.push(loadApk());
        if (p === 'firmware') jobs.push(loadFw());
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
      onUnmounted(() => { clearInterval(timer); clearInterval(timer2); });

      return {
        // 状态
        page, menus, curTitle, errMsg, clock, bootAt, ver, built, ME,
        summary, boards, phones, apks, fws, users, stats, statsSummary,
        events, audit, pg, apkForm, fwForm, newUser, dlg, flowDlg, polDlg, ledDlg, busy,
        flows, flowOwners, flowFilter,
        // 方法
        go, loadAll, loadPage, loadBoards, loadPhones, loadApk, loadFw,
        loadUsers, loadStats, loadEvents, loadAudit, loadSummary, loadFlows,
        setFlowStatus, flowStatus, flowStatusText, flowStatusClass,
        fireLed, showBoard, showFlows, setPolicy, savePolicy, openLed,
        uploadApk, uploadFw, delFw,
        addUser, chpw, toggleUser, delUser,
        pgGo, pgInfo,
        // 工具
        fmtTime, ago, size, linkName,
      };
    },
  }).mount('#app');
})();
