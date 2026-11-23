import { createFlightBoard, hasHongKongArrivals } from './flights.js?v=reception-1';
import { createArrivalDesk } from './arrival-desk.js?v=reception-1';
import { createPickupDesk } from './pickup.js?v=reception-1';
import { focusTakLocation, mapEnabled, taskLocations, updateLive } from './live.js?v=flights-map-1';

const $ = (selector) => document.querySelector(selector);
const state = { bootstrap: null, data: null, eventId: '', role: 'dispatcher', selectedRef: undefined, view: 'overview', request: 0, busy: false, pendingAction: null, timer: null };
const labels = { not_connected: '待接入', local_ready: '本地可用', configured_unverified: '配置待验证' };
const pickupTime = (value) => new Intl.DateTimeFormat('zh-HK', { timeZone: 'Asia/Hong_Kong', month: '2-digit', day: '2-digit', hour: '2-digit', minute: '2-digit', hourCycle: 'h23' }).format(new Date(value)) + ' HKT';
const actionLabels = { ack_qrf_exception: '中台确认收到异常', reschedule_pickup: '调整接机时间', acknowledge: '确认接收任务', replace_resource: '调整资源分配', confirm_progress: '确认现场节点' };
const roleLabels = { dispatcher: '指挥员', staff: '工作人员' };
const statusLabels = { expired: '已过期', proposed: '待确认', executed: '已提交', committed: '系统写入成功' };
const refKey = (ref) => ref ? `${ref.ontology_id}/${ref.object_type}/${ref.id}` : '';
const eq = (a, b) => refKey(a) === refKey(b);
const create = (tag, cls, text) => { const e = document.createElement(tag); if (cls) e.className = cls; if (text !== undefined && text !== null) e.textContent = String(text); return e; };
const append = (parent, ...children) => { children.filter(Boolean).forEach((child) => parent.append(child)); return parent; };
const empty = (title, note, symbol = '◈') => append(create('div', 'empty-state'), create('div', 'empty-icon', symbol), create('strong', '', title), note ? create('p', '', note) : null);
const time = (value) => { const date = new Date(value); return Number.isNaN(date.valueOf()) ? String(value || '') : date.toLocaleTimeString('zh-CN', { hour: '2-digit', minute: '2-digit', second: '2-digit' }); };
const isDispatcher = () => state.role === 'dispatcher';
const personalSessionKey = 'ops-personal-arrival-session';
let pilotAccessCode = sessionStorage.getItem(personalSessionKey) || '';
const headers = () => pilotAccessCode
  ? { 'Content-Type': 'application/json', 'Authorization': `Bearer ${pilotAccessCode}` }
  : { 'Content-Type': 'application/json', 'X-Demo-Role': state.role, 'X-Demo-Actor': `demo-${state.role}` };
const taskFor = (ref) => state.data?.tasks.find((task) => eq(task.ref, ref));
function errorMessage(detail) { if (typeof detail === 'string') return detail; if (Array.isArray(detail)) return detail.map((item) => item.msg || JSON.stringify(item)).join('；'); return JSON.stringify(detail); }
async function api(path, options = {}) {
  const response = await fetch(path, { ...options, headers: { ...headers(), ...(options.headers || {}) } });
  if (response.status === 204) return null;
  let data;
  try { data = await response.json(); }
  catch { throw new Error(`服务返回异常（HTTP ${response.status}）`); }
  if (!response.ok) {
    const error = new Error(errorMessage(data.detail || data.error || `请求未完成（HTTP ${response.status}）`));
    error.status = response.status;
    throw error;
  }
  return data;
}
const flightBoard = createFlightBoard(api, () => state.role === 'dispatcher');
const pickupDesk = createPickupDesk({ api, context: () => state, refresh,
  openTask: (ref) => document.dispatchEvent(new CustomEvent('ops-map-open-task', { detail: refKey(ref) })) });

const receptionDesk = createArrivalDesk({
  api, context: () => state, refresh,
  openTask: (ref) => document.dispatchEvent(new CustomEvent('ops-map-open-task', { detail: refKey(ref) })),
  confirmTask: (task, action) => openAction({
    action_type: action.id, target_ref: task.ref, expected_version: task.version, parameters: {},
  }, task.title),
  getPhoto: async (path) => {
    const response = await fetch(path, { headers: headers(), cache: 'no-store' });
    if (!response.ok) throw new Error('读取照片失败');
    return response.blob();
  },
});

function showError(message) { $('#error-text').textContent = message; $('#error-banner').hidden = false; }
function showSuccess(message) { $('#success-banner').textContent = message; $('#success-banner').hidden = false; clearTimeout(state.timer); state.timer = setTimeout(() => { $('#success-banner').hidden = true; }, 6500); }
function setConnection(ok, message) { $('#connection-dot').className = `status-dot ${ok ? 'ready' : 'failed'}`; const label = $('#connection-label'); if (label.textContent !== message) label.textContent = message; }
function setView(view) {
  const hasFlights = hasHongKongArrivals(state.eventId);
  if (['flights', 'arrivals'].includes(view) && !hasFlights) view = 'overview';
  state.view = view;
  const showingFlights = view === 'flights';
  const showingArrivals = view === 'arrivals';
  if (state.bootstrap) updateLive(state.bootstrap, state.eventId, view, state.data);
  document.querySelectorAll('[data-view]').forEach((item) => {
    item.classList.toggle('active', item.dataset.view === view);
    if (['flights', 'arrivals'].includes(item.dataset.view)) item.hidden = !hasFlights;
  });
  document.body.classList.toggle('single-view', view !== 'overview');
  const visible = {
    overview: ['tasks', 'communication', 'actions', 'integrations', 'receipts'],
    communication: ['communication'], actions: ['actions'], receipts: ['receipts'], flights: [], arrivals: [],
  }[view];
  document.querySelectorAll('[data-panel]').forEach((panel) => {
    panel.hidden = !visible.includes(panel.dataset.panel)
      || (state.role === 'staff' && panel.dataset.panel === 'actions');
  });
  $('#stats-section').hidden = view !== 'overview';
  $('.page-heading').hidden = showingFlights || showingArrivals;
  $('#environment-label').textContent = showingFlights ? '香港机场到港 · HKT'
    : state.bootstrap?.mode === 'pilot' ? '香港试点 · 合成演练业务数据' : '本机演练 · 合成业务数据';
  $('#flight-board').hidden = !showingFlights;
  $('#workspace-grid').hidden = showingFlights || showingArrivals;
  pickupDesk.render();
  receptionDesk.render();
  if (showingFlights) flightBoard.refresh(state.eventId);
}
function renderStats() { const { summary, receipts, tasks } = state.data; $('#stat-tasks').textContent = summary.open_tasks; $('#stat-proposals').textContent = summary.pending_proposals; $('#stat-messages').textContent = summary.messages; $('#stat-receipts').textContent = receipts.length; $('#task-count').textContent = tasks.length; }
function taskMeta(label, value) { return append(create('span'), create('span', '', `${label} `), create('strong', '', value || '—')); }
function selectTask(task, uid = null) {
  state.selectedRef = task.ref;
  $('#subject-select').value = refKey(task.ref);
  document.querySelectorAll('.task-card').forEach((card) => {
    card.classList.toggle('selected', card.dataset.ref === refKey(task.ref));
  });
  if (uid) focusTakLocation(uid);
}

document.addEventListener('ops-map-open-task', (event) => {
  const task = state.data?.tasks.find(({ ref }) => refKey(ref) === event.detail);
  if (!task) return;
  setView('overview');
  selectTask(task);
  const card = [...document.querySelectorAll('.task-card')].find((item) => item.dataset.ref === event.detail);
  card?.scrollIntoView({ block: 'center' });
  card?.focus({ preventScroll: true });
});
function renderTasks() { const list = $('#task-list'); list.replaceChildren(); if (!state.data.tasks.length) { list.append(empty('当前没有可见任务', '任务将按照活动与演示角色提供。')); return; } for (const task of state.data.tasks) { const card = create('article', `task-card${eq(task.ref, state.selectedRef) ? ' selected' : ''}`); card.dataset.ref = refKey(task.ref); card.tabIndex = 0; card.setAttribute('aria-label', `选择任务：${task.title}`); const locations = state.bootstrap && mapEnabled(state.bootstrap, state.eventId)
      ? taskLocations(task, state.eventId) : [];
    card.addEventListener('click', () => selectTask(task, locations[0]?.uid)); card.addEventListener('keydown', (e) => { if ((e.key === 'Enter' || e.key === ' ') && e.target === card) { e.preventDefault(); selectTask(task, locations[0]?.uid); } }); const heading = append(create('div'), create('div', 'task-title', task.title), create('div', 'task-subtitle', task.subject_label)); append(card, append(create('div', 'task-top'), heading, create('span', 'status-tag', task.status_label || task.status)), create('div', 'task-description', task.description)); append(card, append(create('div', 'task-meta'), taskMeta('负责人', task.owner), taskMeta('当前资源', task.resource_label), task.pickup_at ? taskMeta('接机时间', pickupTime(task.pickup_at)) : null)); const progress = create('div', 'progress-list'); for (const item of task.progress || []) { const status = ['completed', 'done', 'confirmed'].includes(item.status) ? 'done' : ['current', 'active', 'in_progress'].includes(item.status) ? 'current' : ''; progress.append(create('span', `progress-item ${status}`, item.label)); } if (progress.childNodes.length) card.append(progress); const footer = append(create('div', 'task-bottom'), create('span', 'object-ref', `${task.ref.object_type} · ${task.ref.id} · v${task.version}`)); for (const action of task.allowed_actions || []) { if (action.id !== 'acknowledge') continue; const button = create('button', 'task-action', action.label || '确认接收'); button.addEventListener('click', (e) => { e.stopPropagation(); openAction({ action_type: 'acknowledge', target_ref: task.ref, expected_version: task.version, parameters: {} }, task.title); }); footer.append(button); } if (locations.length) {
      const buttons = create('div', 'task-location-tools');
      for (const location of locations) {
        const button = create('button', 'task-action', `定位${location.kind}`);
        button.type = 'button';
        button.addEventListener('click', (event) => {
          event.stopPropagation();
          selectTask(task, location.uid);
        });
        buttons.append(button);
      }
      footer.append(buttons);
    }
    card.append(footer); list.append(card); } }
function renderSubjectOptions() { const select = $('#subject-select'); if (document.activeElement === select) return; const remembered = refKey(state.selectedRef); select.replaceChildren(new Option('未关联任务', '')); for (const task of state.data.tasks) select.append(new Option(task.title, refKey(task.ref))); select.value = remembered; }
function renderMessages() {
  const list = $('#message-list');
  list.replaceChildren();
  if (!state.data.messages.length) {
    list.append(empty('从一条现场汇报开始', '选择任务，发送事实，再生成一条待确认建议。', '◷'));
    return;
  }
  const messages = [...state.data.messages].sort((a, b) => String(b.created_at).localeCompare(String(a.created_at)));
  for (const message of messages) {
    const element = create('article', 'message');
    const author = message.speaker || (message.author === 'demo-dispatcher' ? '演示指挥员'
      : message.author === 'demo-staff' ? '演示工作人员' : message.author);
    const avatar = create('span', 'message-avatar', String(author || '员').slice(0, 1));
    append(element, append(create('div', 'message-meta'), avatar, create('span', 'message-author', author),
      create('time', 'message-time', time(message.created_at))), create('p', 'message-body', message.text));
    if (message.subject_ref) {
      const task = taskFor(message.subject_ref);
      element.append(create('div', 'message-subject', '↳ ' + (task?.title || message.subject_ref.id)));
    }
    if (message.source === 'qrf_exception') {
      const receipt = state.data.receipts.find((r) => r.action_type === 'ack_qrf_exception' && r.result?.message_id === message.id);
      const tools = create('div', 'message-tools');
      if (receipt) {
        tools.append(create('span', '', `中台已确认 · ${receipt.speaker || receipt.actor} · ${time(receipt.created_at)}`));
      } else if (isDispatcher()) {
        const button = create('button', '', '确认收到异常');
        button.addEventListener('click', async () => {
          button.disabled = true;
          const eventId = state.eventId;
          try {
            await api(`/api/events/${encodeURIComponent(eventId)}/qrf/reports/${encodeURIComponent(message.id)}/acknowledge`, { method: 'POST' });
            if (state.eventId === eventId) await refresh();
          } catch (error) {
            if (state.eventId === eventId) showError(error.message);
            button.disabled = false;
          }
        });
        tools.append(button);
      } else {
        tools.append(create('span', '', '等待中台确认'));
      }
      element.append(tools);
    } else if (isDispatcher()) {
      const tools = create('div', 'message-tools');
      if (message.subject_ref) {
        const proposal = state.data.proposals.find((p) => p.message_id === message.id);
        if (proposal) {
          tools.append(create('span', '', proposal.status === 'executed' ? '✓ 关联行动已提交' : '已有待确认建议'));
        } else {
          const button = create('button', '', '生成行动建议 · 规则演示');
          button.addEventListener('click', () => generateProposal(message, button));
          tools.append(button);
        }
      }
      if (state.data.tak) renderTakShare(tools, message);
      if (tools.childElementCount) element.append(tools);
    }
    list.append(element);
  }
}

function renderTakShare(tools, message) {
  const receipt = state.data.tak.reports[message.id];
  if (receipt?.status === 'received') {
    tools.append(append(create('details', 'tak-receipt'),
      create('summary', '', 'OpenTAKServer 已收到'), create('small', '', receipt.cot_uid)));
    return;
  }
  const label = receipt?.status === 'awaiting_readback' ? '核对 TAK 回执'
    : receipt?.status === 'retryable' ? '重试 TAK 共享' : '共享到 TAK';
  const button = create('button', '', label);
  button.addEventListener('click', () => {
    if (receipt) sendTakReport(message.id, {}, button);
    else openTakReport(message);
  });
  tools.append(button);
}

function openTakReport(message) {
  if (state.busy) return;
  const dialog = $('#tak-dialog');
  dialog.dataset.messageId = message.id;
  $('#tak-recipient').textContent = state.data.tak.recipient_uid;
  const reference = state.data.tak.report_reference_point;
  $('#tak-reference').hidden = !reference;
  if (reference) $('#tak-reference').textContent = '活动参考点 ' + reference.join(', ');
  $('#tak-excerpt').value = Array.from(message.text).slice(0, 255).join('');
  $('#tak-error').hidden = true;
  dialog.showModal();
}

async function sendTakReport(messageId, body, button) {
  if (state.busy) return;
  state.busy = true;
  button.disabled = true;
  try {
    const url = '/api/events/' + encodeURIComponent(state.eventId) + '/tak/reports/' + encodeURIComponent(messageId);
    const receipt = await api(url, { method: 'POST', body: JSON.stringify(body) });
    if ($('#tak-dialog').open) $('#tak-dialog').close();
    showSuccess(receipt.status === 'received'
      ? 'OpenTAKServer 已收到。CoT UID ' + receipt.cot_uid
      : '已提交共享请求，等待 OpenTAKServer 回读。CoT UID ' + receipt.cot_uid);
  } catch (error) {
    if ($('#tak-dialog').open) {
      $('#tak-error').textContent = error.message;
      $('#tak-error').hidden = false;
    } else {
      showError(error.message);
    }
  } finally {
    await refresh({ silent: true });
    button.disabled = false;
    state.busy = false;
  }
}

function replacementLabel(proposal) { if (proposal.parameters?.pickup_at) return pickupTime(proposal.parameters.pickup_at); const task = taskFor(proposal.target_ref); return task?.replacement_options?.find((option) => eq(option.ref, proposal.parameters?.replacement_ref))?.label || proposal.parameters?.replacement_ref?.id || '—'; }
function renderProposals() { const list = $('#proposal-list'); list.replaceChildren(); if (!state.data.proposals.length) { list.append(empty('等待一条可处理的汇报', '在团队汇报中生成建议；此版本使用规则演示，尚未调用 AI。', '↗')); return; } const proposals = [...state.data.proposals].sort((a, b) => String(b.created_at).localeCompare(String(a.created_at))); for (const proposal of proposals) { const element = create('article', `proposal-card ${proposal.status === 'executed' ? 'executed' : ''}`); append(element, append(create('div', 'proposal-top'), create('div', 'proposal-title', proposal.title), create('span', 'proposal-status', statusLabels[proposal.status] || proposal.status)), create('p', 'proposal-reason', proposal.reason), create('blockquote', 'evidence-quote', proposal.evidence_quote)); const context = create('div', 'proposal-context'); append(context, create('div', '', `关联：${taskFor(proposal.target_ref)?.title || proposal.target_ref.id}`), append(create('div'), create('span', '', proposal.action_type === 'reschedule_pickup' ? '建议时间：' : '建议资源：'), create('strong', '', replacementLabel(proposal))), create('div', '', `对象版本 v${proposal.expected_version} · ${proposal.source_time ? '机场观测 ' + pickupTime(proposal.source_time) : '来源 ' + proposal.message_id}`)); element.append(context); const footer = append(create('div', 'proposal-footer'), create('small', '', proposal.engine === 'flight_dispatch' ? '机场观测 · 调度规则' : '团队汇报 · 调度规则')); if (proposal.status === 'proposed' && isDispatcher()) { const button = create('button', 'button button-primary', '查看并确认'); button.addEventListener('click', () => openAction({ action_type: proposal.action_type, target_ref: proposal.target_ref, expected_version: proposal.expected_version, parameters: proposal.parameters, proposal_id: proposal.id }, proposal.title, proposal)); footer.append(button); } else footer.append(create('span', 'proposal-status', proposal.status === 'executed' ? '✓ 查看执行回执' : proposal.status === 'expired' ? '请以最新建议为准' : '需要指挥员确认')); element.append(footer); list.append(element); } }
function renderReceipts() { const list = $('#receipt-list'); list.replaceChildren(); if (!state.data.receipts.length) { list.append(empty('暂无执行回执', '', '≡')); return; } for (const receipt of [...state.data.receipts].sort((a, b) => String(b.created_at).localeCompare(String(a.created_at)))) { const element = create('article', 'receipt'); const label = receipt.result?.progress_label || actionLabels[receipt.action_type] || receipt.action_type; append(element, create('strong', '', `${label} · ${statusLabels[receipt.status] || receipt.status}`), create('p', '', `${time(receipt.created_at)} · ${receipt.actor}
${receipt.target_ref.id} · ${receipt.id}`)); const detail = create('details'); append(detail, create('summary', '', '查看回执内容'), create('pre', '', JSON.stringify(receipt.result, null, 2))); element.append(detail); list.append(element); } }
function renderIntegrations() {
  const list = $('#integration-list');
  list.replaceChildren();
  const integrations = state.data?.integrations || state.bootstrap?.integrations || [];
  for (const integration of integrations) {
    const item = create('div', 'integration');
    const pendingBridge = integration.id === 'tak' && integration.status === 'not_connected'
      && mapEnabled(state.bootstrap, state.eventId);
    const label = pendingBridge ? 'Ops ↔ TAK 业务数据' : integration.label;
    const detail = pendingBridge ? '香港演练任务可定位车辆和人员，并可从地图返回任务。正式设备绑定待配置。' : integration.detail;
    append(item, append(create('div'), create('strong', '', label), create('small', '', detail)),
      create('span', `connection-tag ${integration.status === 'local_ready' || pendingBridge ? 'ready' : ''}`, pendingBridge ? '香港演练' : labels[integration.status] || integration.status));
    list.append(item);
  }
}
function render() { if (!state.data) return; renderStats(); renderTasks(); renderSubjectOptions(); renderMessages(); renderProposals(); renderReceipts(); renderIntegrations(); const ontology = state.data.ontology; $('#ontology-context').textContent = `${ontology.label} · ${ontology.id} · Schema ${ontology.schema_version} · Binding ${ontology.binding_version}`; document.body.classList.toggle('staff-mode', !isDispatcher()); $('#tasks-heading').textContent = isDispatcher() ? '服务任务' : '我的工作任务'; $('#page-title').textContent = isDispatcher() ? '让现场的每一步，都有回音。' : '今天的任务，一步一步完成。'; $('#page-subtitle').textContent = isDispatcher() ? '汇报、建议、确认与回执，沿同一条业务链路协作。' : '接收任务、报告现场事实，查看指挥台的执行回执。'; $('#role-avatar').textContent = isDispatcher() ? '指' : '员'; document.querySelectorAll('[data-role]').forEach((e) => e.classList.toggle('selected', e.dataset.role === state.role)); document.querySelectorAll('[data-view="actions"]').forEach((e) => { e.hidden = !isDispatcher(); }); setView(state.view); }
let silentRefreshRunning = false;
let workspaceSnapshot = null;

async function refresh({ silent = false } = {}) {
  if (!state.eventId || (silent && silentRefreshRunning)) return;
  if (silent) silentRefreshRunning = true;
  const request = ++state.request;
  const eventId = state.eventId;
  const role = state.role;
  if (!silent) $('#refresh-button').disabled = true;
  try {
    const data = await api(`/api/events/${encodeURIComponent(eventId)}/workspace`, {
      signal: AbortSignal.timeout(8000),
      cache: 'no-store',
    });
    if (request !== state.request || eventId !== state.eventId || role !== state.role) return;
    const snapshot = JSON.stringify({ eventId, role, data });
    if (snapshot !== workspaceSnapshot) {
      workspaceSnapshot = snapshot;
      state.data = data;
      if (state.selectedRef === undefined) state.selectedRef = data.tasks[0]?.ref || null;
      else if (state.selectedRef && !data.tasks.some((task) => eq(task.ref, state.selectedRef))) state.selectedRef = null;
      render();
    }
    setConnection(true, '业务自动同步');
    $('#last-updated').textContent = `校验于 ${time(new Date())}`;
  } catch (error) {
    if (request === state.request) {
      setConnection(false, '业务连接中断 · 自动重试');
      if (!silent) showError(error.message);
    }
  } finally {
    if (silent) silentRefreshRunning = false;
    if (request === state.request) $('#refresh-button').disabled = false;
  }
}
async function generateProposal(message, button) { if (state.busy) return; const eventId = state.eventId; state.busy = true; button.disabled = true; button.textContent = '正在生成…'; try { await api(`/api/events/${encodeURIComponent(eventId)}/proposals`, { method: 'POST', body: JSON.stringify({ message_id: message.id }) }); if (eventId === state.eventId) { showSuccess('行动建议已生成，尚未执行。请检查来源、目标与替换资源。'); await refresh(); } } catch (e) { showError(e.message); button.disabled = false; button.textContent = '生成行动建议 · 规则演示'; } finally { state.busy = false; } }
function closeDialog() { $('#action-dialog').close(); state.pendingAction = null; }
function openAction(payload, title, proposal) { const task = taskFor(payload.target_ref); state.pendingAction = { eventId: state.eventId, role: state.role, payload: { ...payload, idempotency_key: globalThis.crypto?.randomUUID?.() || `demo-${Date.now()}-${Math.random().toString(36).slice(2)}` } }; $('#dialog-title').textContent = actionLabels[payload.action_type] || '确认行动'; $('#dialog-description').textContent = title; const details = $('#dialog-details'); details.replaceChildren(); const rows = [['任务', task?.title || payload.target_ref.id], ['执行身份', `${roleLabels[state.role]}（演示）`], ['预期版本', `v${payload.expected_version}`], ['业务对象', refKey(payload.target_ref)]]; if (proposal) {
  const isTime = proposal.action_type === 'reschedule_pickup';
  const changes = isTime ? [['当前接机时间', task?.pickup_at ? pickupTime(task.pickup_at) : '待定'], ['建议接机时间', replacementLabel(proposal)]] : [['建议资源', replacementLabel(proposal)]];
  rows.splice(1, 0, ...changes, ['来源证据', proposal.evidence_quote]);
} for (const [label, value] of rows) append(details, create('dt', '', label), create('dd', '', value)); $('#dialog-error').hidden = true; $('#dialog-confirm').disabled = false; $('#dialog-confirm').textContent = '确认执行'; $('#action-dialog').showModal(); }
async function confirmAction() { const pending = state.pendingAction; if (!pending || state.busy) return; if (pending.eventId !== state.eventId || pending.role !== state.role) { closeDialog(); showError('活动或身份已变化，请重新检查行动。'); return; } state.busy = true; $('#dialog-confirm').disabled = true; $('#dialog-confirm').textContent = '正在提交…'; try { const receipt = await api(`/api/events/${encodeURIComponent(pending.eventId)}/actions`, { method: 'POST', body: JSON.stringify(pending.payload) }); closeDialog(); showSuccess(`系统写入成功，回执 ${receipt.id}。终端送达与现场执行回执待接入；尚不代表交接完成。`); await refresh(); } catch (e) { $('#dialog-error').textContent = e.message; $('#dialog-error').hidden = false; $('#dialog-confirm').disabled = false; $('#dialog-confirm').textContent = '重试同一请求'; await refresh({ silent: true }); } finally { state.busy = false; } }
$('#message-form').addEventListener('submit', async (e) => { e.preventDefault(); if (state.busy) return; const text = $('#message-text').value.trim(); if (!text) return; const subject = state.data?.tasks.find((t) => refKey(t.ref) === $('#subject-select').value)?.ref || null; const eventId = state.eventId; state.busy = true; $('#send-message').disabled = true; try { await api(`/api/events/${encodeURIComponent(eventId)}/messages`, { method: 'POST', body: JSON.stringify({ text, subject_ref: subject }) }); $('#message-text').value = ''; showSuccess('汇报已保存。业务状态尚未改变，可由指挥员生成建议并确认。'); await refresh(); } catch (error) { showError(error.message); } finally { state.busy = false; $('#send-message').disabled = false; } });
$('#subject-select').addEventListener('blur', () => { if (state.data) renderSubjectOptions(); });
$('#subject-select').addEventListener('change', (e) => { state.selectedRef = state.data?.tasks.find((task) => refKey(task.ref) === e.target.value)?.ref || null; renderTasks(); });
$('#fill-sample').addEventListener('click', () => { if (state.data?.tasks.length && !$('#subject-select').value) selectTask(state.data.tasks[0]); $('#message-text').value = '现场汇报：当前资源故障，暂时不可用，请协调备用资源。人员交接尚未完成。'; $('#message-text').focus(); });
$('#event-select').addEventListener('change', async (e) => { if (state.busy) { e.target.value = state.eventId; showError('请等待当前请求完成后再切换活动。'); return; } closeDialog(); state.eventId = e.target.value; state.selectedRef = undefined; receptionDesk.render(); $('#message-text').value = ''; $('#error-banner').hidden = true; $('#success-banner').hidden = true; await refresh(); });
document.querySelectorAll('[data-role]').forEach((e) => e.addEventListener('click', async () => { if (state.busy || state.role === e.dataset.role) return; closeDialog(); state.role = e.dataset.role; state.selectedRef = undefined; if (!isDispatcher() && state.view === 'actions') state.view = 'overview'; $('#error-banner').hidden = true; await refresh(); }));
document.querySelectorAll('[data-view]').forEach((e) => e.addEventListener('click', () => setView(e.dataset.view)));
$('#refresh-button').addEventListener('click', () => state.bootstrap ? refresh() : start());
$('#dismiss-error').addEventListener('click', () => { $('#error-banner').hidden = true; });
$('#dialog-cancel').addEventListener('click', closeDialog);
$('#dialog-confirm').addEventListener('click', confirmAction);
$('#tak-cancel').addEventListener('click', () => { if (!state.busy) $('#tak-dialog').close(); });
$('#tak-dialog').addEventListener('cancel', (event) => { if (state.busy) event.preventDefault(); });
$('#tak-share-form').addEventListener('submit', (event) => {
  event.preventDefault();
  const dialog = $('#tak-dialog');
  sendTakReport(dialog.dataset.messageId, { excerpt: $('#tak-excerpt').value }, $('#tak-submit'));
});
$('#action-dialog').addEventListener('cancel', () => { state.pendingAction = null; });
$('#action-dialog').addEventListener('close', () => { if (!state.busy) state.pendingAction = null; });
$('#pilot-login').addEventListener('cancel', (event) => event.preventDefault());
$('#pilot-signout').addEventListener('click', async () => {
  sessionStorage.removeItem(personalSessionKey);
  if (pilotAccessCode.startsWith('pxd-')) {
    try { await api('/api/field/logout', { method: 'POST', signal: AbortSignal.timeout(5000) }); }
    catch { /* Clear the local credential even if the service is disconnected. */ }
  }
  location.reload();
});
$('#pilot-login-form').addEventListener('submit', async (event) => {
  event.preventDefault();
  const code = $('#pilot-code').value.trim();
  pilotAccessCode = code;
  $('#pilot-connect').disabled = true;
  try {
    if ($('#pilot-bind-person').checked) {
      const bound = await api('/api/field/bind', { method: 'POST', body: JSON.stringify({ code }) });
      pilotAccessCode = bound.session_token;
      sessionStorage.setItem(personalSessionKey, pilotAccessCode);
    }
    await start();
  } catch (err) {
    $('#pilot-login-error').textContent = err.message;
    $('#pilot-login-error').hidden = false;
  } finally {
    $('#pilot-connect').disabled = false;
    $('#pilot-code').value = '';
  }
});

async function start() {
  try {
    state.bootstrap = await api('/api/bootstrap');
    const pilot = state.bootstrap.mode === 'pilot';
    $('#environment-label').textContent = pilot ? '香港试点 · 合成演练业务数据' : '本机演练 · 合成业务数据';
    $('.role-switch').hidden = pilot;
    $('#pilot-signout').hidden = !pilot;
    if (pilot) {
      state.role = state.bootstrap.identity.role;
      $('#pilot-login').close();
    }
    const select = $('#event-select');
    select.replaceChildren();
    for (const event of state.bootstrap.events) select.append(new Option(event.title, event.id));
    const requested = new URLSearchParams(location.search).get('event');
    state.eventId = state.bootstrap.events.some(({ id }) => id === requested)
      ? requested : state.bootstrap.ptt_event_id || state.bootstrap.events[0]?.id || '';
    select.value = state.eventId;
    $('#flight-tak-admin').hidden = location.origin !== 'http://127.0.0.1:18080';
    const requestedView = new URLSearchParams(location.search).get('view');
    if (requestedView === 'flights') setView('flights');
    else if (requestedView === 'arrivals' || location.pathname === '/arrivals') setView('arrivals');
    if (!state.eventId) { showError('当前没有可用活动'); return; }
    renderIntegrations();
    await refresh();
  } catch (error) {
    if (error.status === 401) {
      const message = $('#pilot-login-error');
      message.hidden = !pilotAccessCode;
      message.textContent = error.message;
      pilotAccessCode = '';
      sessionStorage.removeItem(personalSessionKey);
      if (!$('#pilot-login').open) $('#pilot-login').showModal();
      setConnection(false, '等待试点访问码');
      return;
    }
    showError(error.message);
    setConnection(false, '启动连接失败');
  }
}
function refreshInBackground() {
  if (!state.busy && !document.hidden) {
    refresh({ silent: true });
    if (state.view === 'flights') flightBoard.refresh(state.eventId);
  }
}
setInterval(refreshInBackground, 4000);
document.addEventListener('visibilitychange', refreshInBackground);
window.addEventListener('online', refreshInBackground);
start();
