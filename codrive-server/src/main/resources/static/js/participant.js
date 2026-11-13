(function () {
  'use strict';

  var $ = function (id) { return document.getElementById(id); };

  var BASE = location.pathname.replace(/\/join\/?$/, '');
  var KEY = 'codrive.participant';
  var body = document.body;

  var LABEL = {
    NAV_DESTINATION: '목적지 요청', NAV_WAYPOINT: '경유지 제안',
    HVAC_VIEW: '공조 상태 조회', HVAC_TEMPERATURE: '온도 변경', HVAC_FAN: '풍량 변경',
    LIGHT_VIEW: '조명 상태 조회', LIGHT_BRIGHTNESS: '밝기 변경', LIGHT_COLOR: '색상 변경',
    MUSIC_VIEW: '재생 상태 조회', MUSIC_CONTROL: '음악 제어'
  };
  var STATUS = {
    AVAILABLE: ['사용 가능', 'ok'], VIEW_ONLY: ['조회만 가능', 'ok'], MOCK_ONLY: ['MOCK (시뮬레이션)', 'mock'],
    PERMISSION_REQUIRED: ['차량 제어 권한이 필요합니다', 'warn'], NOT_SUPPORTED: ['이 차량에서 지원하지 않음', 'off'],
    TEMPORARILY_BLOCKED: ['지금은 사용할 수 없음', 'warn']
  };
  var CMD = {
    SENT_TO_VEHICLE: '차량으로 전송됨', APPLYING: '적용 중...', APPLIED: '적용 완료', FAILED: '실패',
    PERMISSION_DENIED: '권한 없음', NOT_SUPPORTED: '지원하지 않음', TIMED_OUT: '응답 시간 초과',
    DELIVERED: '차량에 전달됨 (결과는 확인할 수 없음)', AWAITING_DRIVER: '운전자 승인 대기 중...'
  };
  var SWATCHES = ['#FFFFFF', '#FF4D4D', '#FF9F43', '#FFD93D', '#2ED573', '#1E90FF', '#7D5FFF', '#FF6BCB'];

  var ws = null, stored = null, state = null, ended = false, retry = 0, suspended = false;
  var pollTimer = null, target = { temp: 22, fan: 3, color: SWATCHES[0] };

  function load() { try { return JSON.parse(sessionStorage.getItem(KEY)); } catch (e) { return null; } }
  function save(v) { try { sessionStorage.setItem(KEY, JSON.stringify(v)); } catch (e) {  } }
  function clear() { try { sessionStorage.removeItem(KEY); } catch (e) {  } }

  function show(id, on) { $(id).hidden = !on; }
  function views(active) {
    ['view-join', 'view-wait', 'view-norole', 'view-map', 'view-hvac', 'view-light', 'view-music', 'view-ended']
      .forEach(function (v) { show(v, v === active); });
  }
  function pill(text, cls) { var p = $('conn'); p.textContent = text; p.className = 'pill ' + cls; }
  function esc(el, text) { el.textContent = text; return el; }

  function connect() {
    if (!stored || ended) { return; }
    var proto = location.protocol === 'https:' ? 'wss://' : 'ws://';
    ws = new WebSocket(proto + location.host + BASE + '/ws/participant');
    ws.onopen = function () { ws.send(JSON.stringify({ type: 'AUTH', payload: { token: stored.token } })); };
    ws.onmessage = function (ev) {
      var m; try { m = JSON.parse(ev.data); } catch (e) { return; }
      handle(m.type, m.payload || {});
    };
    ws.onclose = function () {
      stopPolling();
      if (ended) { return; }
      pill('재연결 중...', 'pill-warn');
      retry = Math.min(retry + 1, 8);
      setTimeout(connect, Math.min(1000 * retry, 8000));
    };
  }

  function send(type, payload) {
    if (!ws || ws.readyState !== 1) { return false; }
    ws.send(JSON.stringify({ type: type, payload: payload || {} }));
    return true;
  }

  function endView(title, text) {
    ended = true;
    clear();
    stopPolling();
    views('view-ended');
    show('panel-features', false); show('panel-log', false); show('leave-box', false);
    show('banner-suspended', false); show('banner-mock', false);
    esc($('ended-title'), title);
    esc($('ended-body'), text);
    pill('연결 종료', 'pill-off');
    if (ws) { try { ws.close(); } catch (e) {  } }
  }

  function handle(type, p) {
    switch (type) {
      case 'AUTH_OK': retry = 0; pill('연결됨', 'pill-on'); apply(p); break;
      case 'STATE_UPDATE': apply(p); break;
      case 'JOIN_APPROVED': break;
      case 'JOIN_REJECTED':
        endView(p.reason === 'EXPIRED' ? '승인 대기 시간이 지났습니다.' : '참여가 거절되었습니다.',
          '차량 화면의 QR코드를 다시 스캔해 요청할 수 있습니다.');
        break;
      case 'ROLE_REVOKED':
        endView('차량에서 연결이 해제되었습니다.', '필요하면 차량 화면의 새 QR코드를 스캔해 다시 연결해주세요.');
        break;
      case 'SESSION_ENDED': endView('차량 연결이 종료되었습니다.', '차량 화면의 새로운 QR코드를 스캔해 다시 연결해주세요.'); break;
      case 'SESSION_SUSPENDED': suspended = true; show('banner-suspended', true); refreshControls(); break;
      case 'VEHICLE_RECONNECTED': suspended = false; show('banner-suspended', false); refreshControls(); break;
      case 'COMMAND_RESULT': logCommand(p); break;
      case 'COMMAND_ACCEPTED': break;
      case 'VEHICLE_STATE': showVehicleState(p); break;
      case 'ERROR': logError(p); break;
      default: break;
    }
  }

  function feature(code) {
    if (!state || !state.features) { return null; }
    for (var i = 0; i < state.features.length; i++) { if (state.features[i].code === code) { return state.features[i]; } }
    return null;
  }

  var RANGED = { HVAC_TEMPERATURE: 1, HVAC_FAN: 1, LIGHT_BRIGHTNESS: 1 };
  function hasRange(code) {
    var f = feature(code);
    return !RANGED[code] || (f && f.min != null && f.max != null);
  }
  function usable(code) {
    var f = feature(code);
    if (!f || suspended) { return false; }
    if (!hasRange(code)) { return false; }
    if (f.kind === 'VIEW') { return f.status !== 'NOT_SUPPORTED' && f.status !== 'TEMPORARILY_BLOCKED' && f.status !== 'PERMISSION_REQUIRED'; }
    return f.status === 'AVAILABLE' || f.status === 'MOCK_ONLY';
  }
  function reason(code) {
    var f = feature(code);
    if (!f) { return '공유되지 않은 기능입니다.'; }
    if (!hasRange(code)) { return '차량이 허용 값 범위를 알려주지 않아 사용할 수 없습니다.'; }
    if (f.status === 'PERMISSION_REQUIRED') { return '차량 제어 권한이 필요합니다.'; }
    if (f.status === 'NOT_SUPPORTED') { return '이 차량에서 지원하지 않는 기능입니다.'; }
    if (f.status === 'TEMPORARILY_BLOCKED') { return '지금은 사용할 수 없습니다.'; }
    if (f.status === 'VIEW_ONLY') { return '조회만 가능합니다.'; }
    return '';
  }

  function apply(s) {
    state = s;
    if (s.status === 'PENDING') { views('view-wait'); show('leave-box', false); return; }
    if (s.status === 'REJECTED' || s.status === 'CANCELLED') {
      endView('참여가 거절되었거나 취소되었습니다.', '차량 화면의 QR코드를 다시 스캔해 요청할 수 있습니다.'); return;
    }
    if (s.status === 'ENDED') { endView('차량 연결이 종료되었습니다.', '차량 화면의 새로운 QR코드를 스캔해 다시 연결해주세요.'); return; }
    suspended = s.sessionStatus === 'SUSPENDED';
    show('banner-suspended', suspended);
    show('banner-mock', s.capabilityMode === 'MOCK');
    show('leave-box', true);
    var role = s.role;
    if (role === 'NONE') { views('view-norole'); show('panel-features', false); return; }
    views(role === 'MAP' ? 'view-map' : role === 'HVAC' ? 'view-hvac' : role === 'LIGHT' ? 'view-light' : 'view-music');
    renderFeatures();
    show('panel-features', true); show('panel-log', true);
    initTargets();
    refreshControls();
    startPolling();
  }

  function renderFeatures() {
    var ul = $('feature-list'); ul.textContent = '';
    (state.features || []).forEach(function (f) {
      var li = document.createElement('li');
      var name = document.createElement('span'); name.textContent = LABEL[f.code] || f.code;
      var st = STATUS[f.status] || [f.status, 'off'];
      var badge = document.createElement('span'); badge.className = 'badge badge-' + st[1]; badge.textContent = st[0];
      li.appendChild(name); li.appendChild(badge); ul.appendChild(li);
    });
    if (!ul.children.length) { var e = document.createElement('li'); e.textContent = '공유된 기능이 없습니다.'; ul.appendChild(e); }
  }

  function setEnabled(btn, on) { btn.disabled = !on; }

  function refreshControls() {
    if (!state || state.role === 'NONE') { return; }
    setEnabled($('btn-search'), true);
    setEnabled($('btn-temp'), usable('HVAC_TEMPERATURE'));
    setEnabled($('t-minus'), usable('HVAC_TEMPERATURE')); setEnabled($('t-plus'), usable('HVAC_TEMPERATURE'));
    setEnabled($('btn-fan'), usable('HVAC_FAN'));
    setEnabled($('f-minus'), usable('HVAC_FAN')); setEnabled($('f-plus'), usable('HVAC_FAN'));
    setEnabled($('btn-bright'), usable('LIGHT_BRIGHTNESS')); setEnabled($('bright-range'), usable('LIGHT_BRIGHTNESS'));
    setEnabled($('btn-color'), usable('LIGHT_COLOR'));
    $('swatches').querySelectorAll('button').forEach(function (b) { b.disabled = !usable('LIGHT_COLOR'); });
    var notes = [];
    if (!usable('HVAC_TEMPERATURE')) { notes.push('온도: ' + reason('HVAC_TEMPERATURE')); }
    if (!usable('HVAC_FAN')) { notes.push('풍량: ' + reason('HVAC_FAN')); }
    esc($('hvac-note'), state.role === 'HVAC' ? notes.join(' ') : '');
    var ln = [];
    if (!usable('LIGHT_BRIGHTNESS')) { ln.push('밝기: ' + reason('LIGHT_BRIGHTNESS')); }
    if (!usable('LIGHT_COLOR')) { ln.push('색상: ' + reason('LIGHT_COLOR')); }
    esc($('light-note'), state.role === 'LIGHT' ? ln.join(' ') : '');
    ['btn-mu-prev', 'btn-mu-play', 'btn-mu-pause', 'btn-mu-next'].forEach(function (id) { setEnabled($(id), usable('MUSIC_CONTROL')); });
    esc($('music-note'), state.role === 'MUSIC' && !usable('MUSIC_CONTROL') ? '음악 제어: ' + reason('MUSIC_CONTROL') : '');
    renderPlaces();
  }

  function bounds(code, dmin, dmax) {
    var f = feature(code);
    return { min: f && f.min != null ? f.min : dmin, max: f && f.max != null ? f.max : dmax };
  }
  function initTargets() {
    var t = bounds('HVAC_TEMPERATURE', 16, 32), fb = bounds('HVAC_FAN', 0, 10), lb = bounds('LIGHT_BRIGHTNESS', 0, 100);
    target.temp = Math.min(Math.max(target.temp, t.min), t.max);
    target.fan = Math.min(Math.max(target.fan, fb.min), fb.max);
    $('t-target').textContent = target.temp.toFixed(1);
    $('f-target').textContent = String(target.fan);
    var r = $('bright-range'); r.min = lb.min; r.max = lb.max;
    $('bright-out').textContent = r.value;
  }

  function startPolling() {
    stopPolling();
    var domain = state.role === 'HVAC' ? 'HVAC' : state.role === 'LIGHT' ? 'LIGHT' : state.role === 'MUSIC' ? 'MUSIC' : null;
    if (!domain || !usable(domain + '_VIEW')) { return; }
    var tick = function () { if (!document.hidden) { send('VEHICLE_STATE_REQUEST', { domain: domain }); } };
    tick();
    pollTimer = setInterval(tick, 3000);
  }
  function stopPolling() { if (pollTimer) { clearInterval(pollTimer); pollTimer = null; } }

  function showVehicleState(p) {
    var v = p.values || {}, tag = p.simulated ? ' (MOCK)' : '';
    if (p.domain === 'HVAC') {
      esc($('hv-temp'), v.temperature != null ? Number(v.temperature).toFixed(1) + '°C' + tag : '-');
      esc($('hv-fan'), v.fanSpeed != null ? String(v.fanSpeed) + tag : '-');
    } else if (p.domain === 'MUSIC') {
      esc($('mu-state'), (v.playing ? '재생 중' : '정지/일시정지') + tag);
      esc($('mu-track'), v.title ? String(v.title) : '-');
    } else if (p.domain === 'LIGHT') {
      esc($('li-bright'), v.brightness != null ? String(v.brightness) + tag : '-');
      esc($('li-color'), v.color != null ? String(v.color) + tag : '-');
    }
  }

  var logs = {};
  function logCommand(p) {
    var li = logs[p.requestId];
    if (!li) {
      li = document.createElement('li'); logs[p.requestId] = li;
      $('log-list').insertBefore(li, $('log-list').firstChild);
      while ($('log-list').children.length > 8) { $('log-list').removeChild($('log-list').lastChild); }
    }
    var text = (LABEL[p.action] || p.action) + ' · ' + (CMD[p.status] || p.status);
    if (p.simulated) { text += ' [MOCK]'; }
    if (p.errorCode && p.status !== 'APPLIED') { text += ' (' + p.errorCode + ')'; }
    if (p.message) { text += ' - ' + p.message; }
    li.textContent = text;
    li.className = p.status === 'DELIVERED' ? 'mock' : p.status === 'APPLIED' ? (p.simulated ? 'mock' : 'ok') : (/FAILED|DENIED|TIMED|NOT_SUPP/.test(p.status) ? 'bad' : '');
  }
  function logError(p) {
    var li = document.createElement('li'); li.className = 'bad';
    li.textContent = '오류: ' + (p.message || p.code);
    $('log-list').insertBefore(li, $('log-list').firstChild);
    show('panel-log', true);
  }
  function command(action, params) {
    if (!send('VEHICLE_COMMAND', { action: action, params: params })) { logError({ code: '연결이 끊겨 있습니다.' }); }
  }

  var places = [];
  function renderPlaces() {
    var ul = $('place-list'); ul.textContent = '';
    places.forEach(function (pl) {
      var li = document.createElement('li');
      var t = document.createElement('div'); t.className = 'place';
      var b = document.createElement('b'); b.textContent = pl.name; t.appendChild(b);
      var a = document.createElement('small'); a.textContent = pl.address; t.appendChild(a);
      li.appendChild(t);
      var d = document.createElement('button'); d.className = 'secondary'; d.textContent = '목적지';
      d.disabled = !usable('NAV_DESTINATION');
      d.onclick = function () { command('NAV_DESTINATION', { name: pl.name, lat: pl.lat, lng: pl.lng }); };
      var w = document.createElement('button'); w.className = 'ghost'; w.textContent = '경유지';
      w.disabled = !usable('NAV_WAYPOINT');
      w.onclick = function () { command('NAV_WAYPOINT', { name: pl.name, lat: pl.lat, lng: pl.lng }); };
      li.appendChild(d); li.appendChild(w); ul.appendChild(li);
    });
    var n = [];
    if (!usable('NAV_DESTINATION')) { n.push('목적지 요청: ' + reason('NAV_DESTINATION')); }
    if (!usable('NAV_WAYPOINT')) { n.push('경유지 제안: ' + reason('NAV_WAYPOINT')); }
    if (state && state.role === 'MAP' && n.length) { esc($('place-note'), n.join(' ')); }
  }
  $('btn-search').onclick = function () {
    var q = $('place-q').value.trim(); if (!q || !stored) { return; }
    esc($('place-note'), '검색 중...');
    fetch(BASE + '/api/places/search?q=' + encodeURIComponent(q), { headers: { 'X-Participant-Token': stored.token } })
      .then(function (r) { return r.json().then(function (j) { return { ok: r.ok, j: j }; }); })
      .then(function (r) {
        if (!r.ok) { esc($('place-note'), r.j.message || '검색에 실패했습니다.'); return; }
        places = r.j.places || [];
        esc($('place-note'), r.j.enabled ? (places.length ? '' : '검색 결과가 없습니다.') : '장소 검색이 서버에서 꺼져 있습니다.');
        renderPlaces();
      }).catch(function () { esc($('place-note'), '검색에 실패했습니다.'); });
  };

  function step(kind, dir) {
    if (kind === 't') {
      var b = bounds('HVAC_TEMPERATURE', 16, 32);
      target.temp = Math.min(Math.max(target.temp + dir * 0.5, b.min), b.max);
      $('t-target').textContent = target.temp.toFixed(1);
    } else {
      var f = bounds('HVAC_FAN', 0, 10);
      target.fan = Math.min(Math.max(target.fan + dir, f.min), f.max);
      $('f-target').textContent = String(target.fan);
    }
  }
  $('t-minus').onclick = function () { step('t', -1); }; $('t-plus').onclick = function () { step('t', 1); };
  $('f-minus').onclick = function () { step('f', -1); }; $('f-plus').onclick = function () { step('f', 1); };
  $('btn-temp').onclick = function () { command('HVAC_TEMPERATURE', { celsius: target.temp }); };
  $('btn-fan').onclick = function () { command('HVAC_FAN', { level: target.fan }); };
  $('bright-range').oninput = function () { $('bright-out').textContent = this.value; };
  $('btn-bright').onclick = function () { command('LIGHT_BRIGHTNESS', { level: Number($('bright-range').value) }); };
  $('btn-mu-prev').onclick = function () { command('MUSIC_CONTROL', { command: 'PREVIOUS' }); };
  $('btn-mu-play').onclick = function () { command('MUSIC_CONTROL', { command: 'PLAY' }); };
  $('btn-mu-pause').onclick = function () { command('MUSIC_CONTROL', { command: 'PAUSE' }); };
  $('btn-mu-next').onclick = function () { command('MUSIC_CONTROL', { command: 'NEXT' }); };
  $('btn-color').onclick = function () { command('LIGHT_COLOR', { hex: target.color }); };
  SWATCHES.forEach(function (hex) {
    var b = document.createElement('button'); b.type = 'button'; b.className = 'swatch'; b.setAttribute('aria-label', hex);
    b.style.backgroundColor = hex;
    b.onclick = function () {
      target.color = hex;
      $('swatches').querySelectorAll('button').forEach(function (x) { x.classList.remove('sel'); });
      b.classList.add('sel');
    };
    $('swatches').appendChild(b);
  });
  $('swatches').firstChild.classList.add('sel');

  function joinError(msg) { esc($('join-error'), msg); }
  function startJoin() {
    var nick = $('nickname').value.trim();
    if (!nick) { joinError('닉네임을 입력해주세요.'); return; }
    var payload = { nickname: nick };
    if (body.dataset.token) { payload.token = body.dataset.token; } else { payload.code = $('code').value.trim(); }
    $('btn-join').disabled = true; joinError('');
    fetch(BASE + '/api/join/requests', { method: 'POST', headers: { 'Content-Type': 'application/json' }, body: JSON.stringify(payload) })
      .then(function (r) { return r.json().then(function (j) { return { ok: r.ok, j: j }; }); })
      .then(function (r) {
        if (!r.ok) { $('btn-join').disabled = false; joinError(r.j.message || '참여 요청에 실패했습니다.'); return; }
        stored = { token: r.j.participantToken, requestId: r.j.requestId };
        save(stored);
        views('view-wait');
        connect();
      }).catch(function () { $('btn-join').disabled = false; joinError('서버에 연결할 수 없습니다.'); });
  }
  $('btn-join').onclick = startJoin;
  $('nickname').addEventListener('keydown', function (e) { if (e.key === 'Enter') { startJoin(); } });
  $('btn-cancel').onclick = function () { send('LEAVE'); endView('참여 요청을 취소했습니다.', '다시 연결하려면 차량 화면의 QR코드를 스캔해주세요.'); };
  $('btn-leave').onclick = function () { send('LEAVE'); endView('연결을 종료했습니다.', '다시 연결하려면 차량 화면의 QR코드를 스캔해주세요.'); };

  function init() {
    stored = load();
    if (stored && stored.token) { views('view-wait'); pill('연결 중...', 'pill-warn'); connect(); return; }
    var joinable = body.dataset.joinable === 'true', hasToken = !!body.dataset.token;
    views('view-join');
    if (!hasToken) {
      show('join-code-box', true);
      esc($('join-count'), '차량 화면의 QR코드를 스캔하거나 참여 코드를 입력하세요.');
      return;
    }
    esc($('join-alias'), body.dataset.alias ? '연결할 차량: ' + body.dataset.alias : '');
    esc($('join-count'), body.dataset.alias ? '현재 참여 인원 ' + body.dataset.count + '/' + body.dataset.max + '명' : '');
    if (!joinable) {
      var R = { TOKEN_USED: '이미 사용된 QR코드입니다.', TOKEN_EXPIRED: 'QR코드가 만료되었습니다.', FULL: '이 차량은 이미 최대 인원이 연결되어 있습니다.',
        SESSION_ENDED: '종료된 연결입니다.', VEHICLE_OFFLINE: '차량 앱과 연결되어 있지 않습니다.', INVALID_TOKEN: '유효하지 않은 QR코드입니다.' };
      joinError((R[body.dataset.reason] || '연결할 수 없습니다.') + ' 차량 화면의 새 QR코드를 스캔해주세요.');
      $('btn-join').disabled = true;
    }
  }
  init();
})();
