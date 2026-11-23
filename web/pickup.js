const $ = (id) => document.getElementById(id);
const refKey = (ref) => [ref.ontology_id, ref.object_type, ref.id].join('/');
const clock = new Intl.DateTimeFormat('zh-CN', {
  timeZone: 'Asia/Hong_Kong', hour: '2-digit', minute: '2-digit', second: '2-digit', hourCycle: 'h23',
});
const pickupClock = new Intl.DateTimeFormat('zh-HK', {
  timeZone: 'Asia/Hong_Kong', month: '2-digit', day: '2-digit', hour: '2-digit', minute: '2-digit', hourCycle: 'h23',
});
const statusLabels = {
  scheduled: '计划', estimated: '预计到达', landed: '已落地', at_gate: '已到达机位',
  delayed: '机场通报延误', cancelled: '取消', other: '请核对机场看板',
};
const alertLabels = { delay: '预计延误', landed: '已落地', at_gate: '已到达机位', cancelled: '已取消' };

function element(tag, value, className) {
  const node = document.createElement(tag);
  node.textContent = value;
  if (className) node.className = className;
  return node;
}

export function createPickupDesk({ api, context, refresh, openTask }) {
  let lastRender;
  let pending;
  const bindDialog = $('pickup-bind-dialog');
  const detailDialog = $('pickup-detail-dialog');

  function present(observation) {
    if (!observation) return '等待香港机场观测';
    const title = observation.delay_minutes
      ? `预计延误 ${observation.delay_minutes} 分钟`
      : statusLabels[observation.status];
    const when = observation.actual ? `实际 ${observation.actual}`
      : observation.estimated ? `预计 ${observation.estimated}` : `计划 ${observation.scheduled || '—'}`;
    const old = !observation.actual && Date.now() - Date.parse(observation.source_time) > 600000;
    return `${old ? '上次观测 · ' : ''}${title} · ${when}`;
  }

  function details(link) {
    const state = context();
    const task = state.data?.tasks.find(({ ref }) => refKey(ref) === refKey(link.task_ref));
    if (!task) return;
    $('pickup-detail-title').textContent = task.title;
    const rows = [
      ['到港航班', `${link.flight} · ${link.date}`],
      ['机场状态', present(link.observation)],
      ['接机时间', task.pickup_at ? `${pickupClock.format(new Date(task.pickup_at))} HKT` : '待定'],
      ['负责司机', task.owner || '待指派'],
      ['当前车辆', task.resource_label || '待指派'],
    ];
    const source = link.observation?.source_time;
    if (source) rows.push(['机场数据时间', `${clock.format(new Date(source))} HKT`]);
    $('pickup-detail-fields').replaceChildren(...rows.flatMap(([label, value]) =>
      [element('dt', label), element('dd', value)]));
    $('pickup-open-task').onclick = () => {
      detailDialog.close();
      openTask(task.ref);
    };
    detailDialog.showModal();
  }

  function render() {
    const state = context();
    const data = state.data?.event?.id === state.eventId ? state.data.pickup_watch : null;
    $('pickup-desk').hidden = state.role !== 'dispatcher' || !data;
    if ($('pickup-desk').hidden) return;
    const proposals = (state.data.proposals || []).filter((proposal) =>
      proposal.engine === 'flight_dispatch' && proposal.status === 'proposed');
    const signature = JSON.stringify([data, proposals.map(({ id }) => id)]);
    if (lastRender === signature) return;
    lastRender = signature;
    const list = $('pickup-reminder-list');
    const links = $('pickup-link-list');
    list.replaceChildren();
    links.replaceChildren();
    if (!data.links.length) links.append(element('p', '在航班到港页选择航班，再关联接机任务。'));
    for (const link of data.links) {
      const task = state.data.tasks.find(({ ref }) => refKey(ref) === refKey(link.task_ref));
      if (!task) continue;
      const button = element('button', `${link.flight} · ${task.title}`, 'pickup-link');
      button.type = 'button';
      button.addEventListener('click', () => details(link));
      links.append(button);
      if (proposals.some(({ target_ref }) => refKey(target_ref) === refKey(link.task_ref))) {
        const action = element('button', '查看调度建议', 'pickup-link');
        action.type = 'button';
        action.addEventListener('click', () => document.querySelector('[data-view="actions"]').click());
        links.append(action);
      }
    }
    for (const alert of data.reminders) {
      const link = data.links.find(({ task_ref }) => refKey(task_ref) === refKey(alert.task_ref));
      if (!link) continue;
      const row = element('div', '', 'pickup-reminder');
      const button = element('button', `${link.flight} · ${alertLabels[alert.kind]}`, 'pickup-link');
      button.type = 'button';
      button.addEventListener('click', () => details(link));
      const time = clock.format(new Date(alert.source_time));
      const note = element('small', `${time} HKT · ${alert.initial_observation ? '首次观测' : '状态更新'} · ${present(alert)}`);
      row.append(button, note);
      list.append(row);
    }
  }

  document.addEventListener('ops-pickup-bind-flight', ({ detail }) => {
    const state = context();
    if (state.role !== 'dispatcher' || state.data?.event?.id !== state.eventId || !state.data?.pickup_watch) return;
    const managed = new Set(state.data.arrival_desk?.cards.map(({ id }) => id));
    const tasks = state.data.tasks.filter(({ ref }) => ref.object_type === 'TransferLeg' && !managed.has(ref.id));
    if (!tasks.length) return;
    pending = { ...detail, eventId: state.eventId, tasks };
    $('pickup-bind-title').textContent = `关联 ${detail.flight} 到接机任务`;
    $('pickup-bind-target').replaceChildren(...tasks.map((task) => new Option(task.title, refKey(task.ref))));
    $('pickup-bind-error').textContent = '';
    bindDialog.showModal();
  });

  $('pickup-bind-form').addEventListener('submit', async (event) => {
    event.preventDefault();
    const item = pending;
    if (!item || item.eventId !== context().eventId) return;
    const task = item.tasks.find(({ ref }) => refKey(ref) === $('pickup-bind-target').value);
    if (!task) return;
    const button = $('pickup-bind-save');
    button.disabled = true;
    try {
      await api(`/api/events/${item.eventId}/pickup-flight`, {
        method: 'POST', body: JSON.stringify({ task_ref: task.ref, date: item.date, flight: item.flight }),
      });
      bindDialog.close();
      await refresh();
    } catch (error) {
      $('pickup-bind-error').textContent = error.message;
    } finally {
      button.disabled = false;
    }
  });
  $('pickup-bind-cancel').addEventListener('click', () => bindDialog.close());
  return { render };
}
