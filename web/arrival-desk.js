const $ = (id) => document.getElementById(id);
const zone = 'Asia/Hong_Kong';
const clock = new Intl.DateTimeFormat('zh-HK', { timeZone: zone, hour: '2-digit', minute: '2-digit', hourCycle: 'h23' });
const day = new Intl.DateTimeFormat('sv-SE', { timeZone: zone, year: 'numeric', month: '2-digit', day: '2-digit' });
const flightLabels = {
  scheduled: '计划到达', estimated: '预计到达', delayed: '机场通报延误', landed: '已落地',
  at_gate: '已到达机位', cancelled: '已取消', other: '请核对机场看板',
};
const stages = { met: '机场会合', boarded: '确认登车', hotel_handoff: '酒店交接', checked_in: '确认入住' };

function node(tag, text, className) {
  const element = document.createElement(tag);
  if (text != null) element.textContent = text;
  if (className) element.className = className;
  return element;
}

function button(label, click, primary = false) {
  const element = node('button', label, 'button ' + (primary ? 'button-primary' : 'button-secondary'));
  element.type = 'button';
  element.addEventListener('click', click);
  return element;
}

function hkt(value) {
  const date = new Date(value);
  return `${day.format(date).slice(5)} ${clock.format(date)} HKT`;
}

function sameDayRange(from, to) {
  const start = new Date(from);
  const end = new Date(to);
  return day.format(start) === day.format(end)
    ? `${clock.format(start)}–${clock.format(end)} HKT`
    : `${hkt(from)} – ${hkt(to)}`;
}

function estimate(card, now) {
  const metAt = card.task.progress[0].confirmed_at;
  if (metAt) return { title: '已会合', text: hkt(metAt) };
  if (card.observation?.status === 'cancelled') return { title: '航班已取消', text: '请等调度员确认新安排' };
  const window = card.window;
  if (!window) return { title: '等待会合估计', text: '收到有效的机场到达时间后更新' };
  if (window.valid_until && Date.parse(window.valid_until) < now) {
    return { title: '机场预计时间待更新', text: `上次估计 ${sameDayRange(window.from, window.to)}` };
  }
  const mins = (value) => Math.max(0, Math.ceil((Date.parse(value) - now) / 60_000));
  const from = mins(window.from);
  const to = mins(window.to);
  const range = sameDayRange(window.from, window.to);
  if (to === 0) return { title: '已超过估算区间', text: `${range} · 等待现场确认` };
  if (from === 0) return { title: `预计 ${to} 分钟内可会合`, text: `已进入估算区间 · ${range}` };
  return { title: `预计还有 ${from}–${to} 分钟`, text: `${range} · 包含入境及行李预留` };
}

function field(parent, label, name, type = 'text', attrs = {}) {
  const control = document.createElement(type === 'select' ? 'select' : 'input');
  if (type !== 'select') control.type = type;
  control.name = name;
  Object.assign(control, attrs);
  const wrapper = node('label', null, type === 'checkbox' ? 'arrival-checkbox' : '');
  if (type === 'checkbox') wrapper.append(control, node('span', label));
  else wrapper.append(node('span', label), control);
  parent.append(wrapper);
  return control;
}

function photoData(file) {
  return createImageBitmap(file).then((image) => {
    try {
      const width = Math.min(image.width, 560);
      const canvas = document.createElement('canvas');
      canvas.width = width;
      canvas.height = Math.max(1, Math.round(width * image.height / image.width));
      if (canvas.height > 750) {
        canvas.width = Math.max(1, Math.round(canvas.width * 750 / canvas.height));
        canvas.height = 750;
      }
      const context = canvas.getContext('2d');
      context.fillStyle = 'white';
      context.fillRect(0, 0, canvas.width, canvas.height);
      context.drawImage(image, 0, 0, canvas.width, canvas.height);
      return canvas.toDataURL('image/jpeg', 0.84).split(',')[1];
    } finally {
      image.close();
    }
  });
}

export function createArrivalDesk({ api, getPhoto, context, refresh, openTask, confirmTask }) {
  const desk = $('arrival-desk');
  const editor = $('arrival-editor');
  const team = $('arrival-team');
  const list = $('arrival-cards');
  let lastSignature;
  let pendingUploads = null;
  let contextKey;
  const photoUrls = new Map();

  function path(eventId, id) {
    return `/api/events/${encodeURIComponent(eventId)}/arrival-plans/${encodeURIComponent(id)}`;
  }

  async function loadPhoto(img, url, revision) {
    const key = `${url}?v=${encodeURIComponent(revision)}`;
    if (!photoUrls.has(key)) {
      photoUrls.set(key, getPhoto(key).then((blob) => URL.createObjectURL(blob)).catch((error) => {
        photoUrls.delete(key);
        throw error;
      }));
    }
    try {
      const src = await photoUrls.get(key);
      if (img.isConnected) {
        img.src = src;
        img.hidden = false;
      }
    } catch {
      if (img.isConnected) img.alt = '照片暂时无法读取';
    }
  }

  function photoPath(eventId, id, playerId) {
    return `${path(eventId, id)}/players/${encodeURIComponent(playerId)}/photo`;
  }

  function displayCard(card, state) {
    const article = node('article', null, 'arrival-card');
    const title = node('div', null, 'arrival-card-title');
    const labels = node('div');
    labels.append(node('h3', card.players.map(({ name }) => name).join('、')),
      node('span', card.synthetic ? '演练资料' : '活动资料', 'arrival-origin'));
    title.append(labels, button('打开任务', () => openTask(card.task.ref)));
    article.append(title);

    const people = node('div', null, 'arrival-portraits');
    for (const player of card.players) {
      const figure = node('figure');
      const portrait = node('div', player.name.slice(0, 1), 'arrival-portrait');
      if (card.photos[player.id]) {
        const img = node('img');
        img.alt = `${player.name}的接机识别照`;
        img.hidden = true;
        portrait.append(img);
        loadPhoto(img, photoPath(state.eventId, card.id, player.id), card.photos[player.id]);
      }
      figure.append(portrait, node('figcaption', player.name),
        node('small', card.photos[player.id] ? '' : '待补照片'));
      people.append(figure);
    }
    article.append(people);

    const observation = card.observation;
    const flight = node('div', null, 'arrival-flight');
    flight.append(node('strong', `${card.flight} · ${card.flight_date}`));
    const status = observation ? flightLabels[observation.status] : '等候香港机场数据';
    const shown = observation?.actual || observation?.estimated || observation?.scheduled;
    flight.append(node('span', [status, shown, observation?.arrivals_hall ? `到港厅 ${observation.arrivals_hall}` : '',
      observation?.baggage_belt ? `转盘 ${observation.baggage_belt}` : ''].filter(Boolean).join(' · ')));
    if (observation?.delay_minutes) flight.append(node('small', `预计延误 ${observation.delay_minutes} 分钟`, 'arrival-warning'));
    if (observation?.source_time) flight.append(node('small', `香港机场更新于 ${hkt(observation.source_time)}`));
    article.append(flight);

    const countdown = node('div', null, 'arrival-countdown');
    countdown.dataset.arrivalCountdown = card.id;
    countdown.append(node('strong'), node('span'));
    article.append(countdown);

    const hotel = card.hotel;
    const details = node('dl', null, 'arrival-facts');
    const facts = [
      ['会合点', card.meeting_point],
      ['调度确认接机', card.task.pickup_at ? hkt(card.task.pickup_at) : '待确认'],
      ['接机员', card.greeter_name],
      ['司机及车辆', `${card.driver_name} · ${card.task.resource_label}`],
      ['酒店', `${hotel.name} · ${hotel.confirmed ? '订房已确认' : '订房待确认'}`],
      ['住宿日期', `${hotel.check_in} 至 ${hotel.check_out}`],
      ['酒店地址', hotel.address],
      ['酒店交接说明', hotel.instruction],
      ['接待备注', card.notes],
    ];
    for (const [label, value] of facts.filter(([, value]) => value)) {
      details.append(node('dt', label), node('dd', value));
    }
    article.append(details);
    const late = node('p', '', 'arrival-warning');
    late.dataset.arrivalLate = card.id;
    article.append(late);
    const progress = node('ol', null, 'arrival-stages');
    for (const step of card.task.progress) {
      const item = node('li', step.label, step.status);
      if (step.confirmed_at) item.append(node('small', hkt(step.confirmed_at)));
      progress.append(item);
    }
    article.append(progress);
    if (card.task.progress[3].status === 'current' && !hotel.confirmed) {
      article.append(node('p', '请先确认酒店预订，再记录入住。', 'arrival-warning'));
    }
    const controls = node('div', null, 'arrival-controls');
    const error = node('p', '', 'arrival-warning');
    error.setAttribute('role', 'alert');
    if (state.role === 'dispatcher') controls.append(button('编辑安排', () => edit(state, card)));
    const accept = card.task.allowed_actions.find(({ id }) => id === 'acknowledge');
    if (accept) controls.append(button(accept.label, () => confirmTask(card.task, accept)));
    if (card.next_stage) {
      const confirm = button(`记录${stages[card.next_stage]}`, async () => {
        if (!window.confirm(`确认现场已完成“${stages[card.next_stage]}”？`)) return;
        confirm.disabled = true;
        error.textContent = '';
        try {
          await api(`${path(state.eventId, card.id)}/stages`, {
            method: 'POST', body: JSON.stringify({ expected_version: card.version, stage: card.next_stage }),
          });
          await refresh();
        } catch (err) {
          error.textContent = err.message;
          confirm.disabled = false;
        }
      }, true);
      controls.append(confirm);
    }
    article.append(controls, error);

    const history = node('details', null, 'arrival-history');
    history.dataset.historyId = card.id;
    history.append(node('summary', '接待时间线'));
    const events = node('ol');
    for (const entry of card.history) {
      events.append(node('li', `${hkt(entry.occurred_at)} · ${entry.label} · ${entry.actor}${entry.detail ? ' · ' + entry.detail : ''}`));
    }
    history.append(events);
    article.append(history);
    return article;
  }

  function updateCountdowns() {
    if (desk.hidden) return;
    const now = Date.now();
    for (const card of context().data?.arrival_desk?.cards || []) {
      const target = list.querySelector(`[data-arrival-countdown="${card.id}"]`);
      if (!target) continue;
      const value = estimate(card, now);
      target.firstElementChild.textContent = value.title;
      target.lastElementChild.textContent = value.text;
      const late = list.querySelector(`[data-arrival-late="${card.id}"]`);
      const risk = card.hotel_deadline;
      const stale = card.window?.valid_until && Date.parse(card.window.valid_until) < now;
      late.hidden = !risk || stale || card.task.progress[2].status === 'done';
      if (!late.hidden) {
        late.textContent = `${risk.certain ? '估计将晚于' : '可能晚于'}酒店最晚入住时间 ${hkt(risk.deadline)}，请调度员协调留房。`;
      }
    }
  }

  function render() {
    const state = context();
    const data = state.data?.event?.id === state.eventId ? state.data.arrival_desk : null;
    const key = `${state.eventId}:${state.role}`;
    if (key !== contextKey) {
      editor.close();
      team.close();
      for (const promise of photoUrls.values()) promise.then(URL.revokeObjectURL).catch(() => {});
      photoUrls.clear();
      list.replaceChildren();
      lastSignature = undefined;
      contextKey = key;
    }
    desk.hidden = !data || !['overview', 'arrivals'].includes(state.view);
    $('arrival-new').hidden = state.role !== 'dispatcher';
    $('arrival-team-open').hidden = state.role !== 'dispatcher' || !state.bootstrap?.field_binding_enabled;
    if (desk.hidden) return;
    const signature = JSON.stringify([key, data]);
    if (signature !== lastSignature) {
      const opened = new Set([...list.querySelectorAll('details[open]')].map((el) => el.dataset.historyId));
      list.replaceChildren(...data.cards.map((card) => displayCard(card, state)));
      for (const details of list.querySelectorAll('details')) details.open = opened.has(details.dataset.historyId);
      $('arrival-empty').hidden = data.cards.length > 0;
      $('arrival-empty').textContent = state.role === 'dispatcher' ? '当前活动还没有球员接待安排。' : '当前没有分配给你的接待任务。';
      const notice = $('arrival-notice');
      const notifications = data.cards.flatMap((card) => card.history
        .filter(({ kind }) => ['delay', 'delayed', 'landed', 'at_gate', 'cancelled', 'hotel_late'].includes(kind))
        .map((entry) => ({ ...entry, flight: card.flight, active: card.task.progress[2].status !== 'done' })));
      const latest = notifications.filter((entry) => entry.active && Date.now() - Date.parse(entry.occurred_at) < 60 * 60_000)
        .sort((a, b) => Date.parse(b.occurred_at) - Date.parse(a.occurred_at))[0];
      const message = latest ? `${latest.flight} · ${latest.label} · ${hkt(latest.occurred_at)}` : '';
      if (notice.textContent !== message) notice.textContent = message;
      notice.hidden = !message;
      lastSignature = signature;
    }
    updateCountdowns();
  }

  function edit(state, card = null, flight = null) {
    const form = $('arrival-form');
    const formId = card?.id || crypto.randomUUID();
    const fields = $('arrival-form-fields');
    const roster = $('arrival-roster');
    fields.replaceChildren();
    roster.replaceChildren();
    $('arrival-editor-title').textContent = card ? '编辑球员接待' : '安排球员接待';
    $('arrival-edit-error').textContent = '';
    const date = day.format(new Date());
    const data = state.data.arrival_desk;
    const assignment = node('div', null, 'arrival-form-grid');
    fields.append(assignment);
    field(assignment, '香港到港日期', 'flight_date', 'date', { required: true, value: card?.flight_date || flight?.date || date });
    field(assignment, '主航班号', 'flight', 'text', { required: true, maxLength: 16, value: card?.flight || flight?.flight || '' });
    field(assignment, '机场会合点', 'meeting_point', 'text', { required: true, maxLength: 120, value: card?.meeting_point || '' });
    const planned = card?.task.pickup_at ? new Date(card.task.pickup_at) : null;
    field(assignment, '调度确认接机时间（香港）', 'pickup_at', 'datetime-local', { value: planned ? `${day.format(planned)}T${clock.format(planned)}` : '' });
    for (const [name, label, current] of [['greeter', '接机员', card?.greeter], ['driver', '司机', card?.task.owner]]) {
      const select = field(assignment, label, name, 'select', { required: true });
      select.append(new Option('请选择', ''), ...data.people.map((person) => new Option(person.name, person.actor)));
      select.value = current || '';
    }
    const vehicles = field(assignment, '车辆', 'vehicle_id', 'select', { required: true });
    vehicles.append(new Option('请选择', ''));
    for (const vehicle of data.vehicles) {
      const option = new Option(`${vehicle.name} · ${vehicle.seats} 座`, vehicle.id);
      option.disabled = !vehicle.available && vehicle.id !== card?.task.resource_ref?.id;
      vehicles.append(option);
    }
    vehicles.value = card?.task.resource_ref?.id || '';
    const stay = node('div', null, 'arrival-form-grid');
    fields.append(node('h3', '酒店安排'), stay);
    const hotel = card?.hotel;
    field(stay, '酒店名称', 'hotel_name', 'text', { required: true, maxLength: 120, value: hotel?.name || '' });
    field(stay, '酒店地址', 'hotel_address', 'text', { required: true, maxLength: 120, value: hotel?.address || '' });
    field(stay, '入住日期', 'check_in', 'date', { required: true, value: hotel?.check_in || card?.flight_date || flight?.date || date });
    field(stay, '退房日期', 'check_out', 'date', { required: true, value: hotel?.check_out || '' });
    field(stay, '酒店最晚入住时间（香港）', 'latest_check_in', 'time', { value: hotel?.latest_check_in?.slice(0, 5) || '' });
    field(stay, '预计到酒店车程（分钟）', 'transfer_minutes', 'number', { required: true, min: 5, max: 240, value: hotel?.transfer_minutes || 45 });
    field(stay, '酒店交接说明', 'instruction', 'text', { maxLength: 240, value: hotel?.instruction || '' });
    field(stay, '订房已确认', 'confirmed', 'checkbox', { checked: hotel?.confirmed || false });
    field(stay, '接待备注', 'notes', 'text', { maxLength: 300, value: card?.notes || '' });
    const demo = field(stay, '合成演练资料', 'synthetic', 'checkbox',
      { checked: card?.synthetic ?? !state.bootstrap?.identity?.name, disabled: Boolean(card) || !state.bootstrap?.identity?.name });
    if (!state.bootstrap?.identity?.name) demo.title = '请绑定个人调度员后录入真实球员资料';

    function addPlayer(player = null) {
      if (roster.children.length >= 12) return;
      const row = node('div', null, 'arrival-player-edit');
      row.dataset.id = player?.id || crypto.randomUUID();
      field(row, '球员姓名', 'player_name', 'text', { required: true, maxLength: 120, value: player?.name || '' });
      field(row, card?.photos[player?.id] ? '替换照片' : '球员照片', 'photo', 'file', { accept: 'image/jpeg,image/png,image/webp' });
      if (card?.photos[player?.id]) field(row, '移除照片', 'remove_photo', 'checkbox');
      const remove = button('移除球员', () => {
        if (roster.children.length > 1) row.remove();
      });
      row.append(remove);
      roster.append(row);
    }
    if (card) card.players.forEach(addPlayer);
    else addPlayer();
    $('arrival-add-player').onclick = () => addPlayer();
    form.onsubmit = async (event) => {
      event.preventDefault();
      if (state.eventId !== context().eventId || state.role !== context().role) return;
      const submit = $('arrival-save');
      const error = $('arrival-edit-error');
      error.textContent = '';
      submit.disabled = true;
      try {
        const values = Object.fromEntries(new FormData(form));
        const players = [...roster.children].map((row) => ({
          id: row.dataset.id, name: row.querySelector('[name=player_name]').value,
        }));
        const uploads = [];
        for (const row of roster.children) {
          const file = row.querySelector('[name=photo]').files[0];
          if (file && file.size > 12_000_000) throw new Error('请选择 12 MB 以内的照片。');
          if (file || row.querySelector('[name=remove_photo]')?.checked) {
            uploads.push({ id: row.dataset.id, data: file ? await photoData(file) : null });
          }
        }
        const id = formId;
        const body = {
          expected_version: card?.version || 0, expected_task_version: card?.task.version || 0,
          players, flight_date: values.flight_date, flight: values.flight, meeting_point: values.meeting_point,
          driver: values.driver, greeter: values.greeter, vehicle_id: values.vehicle_id, notes: values.notes,
          pickup_at: values.pickup_at ? `${values.pickup_at}:00+08:00` : null, synthetic: demo.checked,
          hotel: {
            name: values.hotel_name, address: values.hotel_address, check_in: values.check_in, check_out: values.check_out,
            latest_check_in: values.latest_check_in || null, confirmed: Boolean(values.confirmed), instruction: values.instruction,
            transfer_minutes: Number(values.transfer_minutes),
          },
        };
        await api(path(state.eventId, id), { method: 'PUT', body: JSON.stringify(body) });
        pendingUploads = { eventId: state.eventId, id, uploads };
        await finishUploads();
      } catch (err) {
        error.textContent = pendingUploads ? `接待已保存，照片尚未全部保存。 ${err.message}` : err.message;
      } finally {
        submit.disabled = false;
      }
    };
    editor.showModal();
  }

  async function finishUploads() {
    const pending = pendingUploads;
    const form = $('arrival-form');
    $('arrival-save').textContent = '重试保存照片';
    form.onsubmit = async (event) => {
      event.preventDefault();
      $('arrival-save').disabled = true;
      try { await finishUploads(); }
      catch (error) { $('arrival-edit-error').textContent = error.message; }
      finally { $('arrival-save').disabled = false; }
    };
    while (pending.uploads.length) {
      const current = pending.uploads[0];
      await api(photoPath(pending.eventId, pending.id, current.id), { method: 'PUT', body: JSON.stringify({ data: current.data }) });
      pending.uploads.shift();
    }
    pendingUploads = null;
    editor.close();
    await refresh();
  }

  editor.addEventListener('close', () => {
    $('arrival-save').textContent = '保存接待安排';
    pendingUploads = null;
  });
  $('arrival-editor-cancel').onclick = () => editor.close();
  $('arrival-new').onclick = () => edit(context());
  document.addEventListener('ops-arrival-create', ({ detail }) => {
    const state = context();
    if (state.role === 'dispatcher' && state.data?.event?.id === state.eventId && state.data.arrival_desk) edit(state, null, detail);
  });

  function showInvitation(result) {
    $('arrival-team-code').value = result.binding_code;
    $('arrival-team-result').hidden = false;
  }

  let loginRoster = [];
  const selectedPerson = () => loginRoster.find((person) => person.actor === $('arrival-team-person').value);
  const fillLoginEmail = () => { $('arrival-team-email').value = selectedPerson()?.login_email ?? ''; };
  $('arrival-team-person').onchange = fillLoginEmail;

  async function loadLoginRoster(state) {
    const { people } = await api(`/api/field/events/${encodeURIComponent(state.eventId)}/directory`);
    if (!team.open || state.eventId !== context().eventId) return;
    loginRoster = people;
    $('arrival-team-person').replaceChildren(...people.map((person) => new Option(person.name, person.actor)));
    $('arrival-team-reissue').hidden = !people.length;
    fillLoginEmail();
  }

  $('arrival-team-open').onclick = async () => {
    $('arrival-team-form').reset();
    $('arrival-team-result').hidden = true;
    $('arrival-team-error').textContent = '';
    $('arrival-team-status').textContent = '';
    $('arrival-team-reissue').hidden = true;
    team.showModal();
    const state = context();
    const email = state.bootstrap?.email_enrollment === true;
    $('arrival-team-save').textContent = email ? '添加人员' : '生成个人绑定码';
    $('arrival-team-new-email').hidden = !email;
    $('arrival-team-login-email').hidden = !email;
    $('arrival-team-invite').hidden = email;
    try {
      await loadLoginRoster(state);
    } catch (err) {
      $('arrival-team-error').textContent = err.message;
    }
  };
  $('arrival-team-approve-email').onclick = async (event) => {
    const person = selectedPerson();
    const input = $('arrival-team-email');
    if (!person || !input.reportValidity()) return;
    const button = event.currentTarget;
    const eventId = context().eventId;
    button.disabled = true;
    $('arrival-team-status').textContent = '';
    $('arrival-team-error').textContent = '';
    try {
      const path = `/api/field/events/${encodeURIComponent(eventId)}/people/${encodeURIComponent(person.actor)}/login-email`;
      const result = await api(path, { method: 'POST', body: JSON.stringify({ email: input.value.trim() || null }) });
      person.login_email = result.login_email;
      if (selectedPerson() === person) fillLoginEmail();
      $('arrival-team-status').textContent = result.login_email ? '已批准登录邮箱' : '已撤销登录邮箱批准';
    } catch (err) {
      $('arrival-team-error').textContent = err.message;
    } finally {
      button.disabled = false;
    }
  };
  $('arrival-team-invite').onclick = async (event) => {
    const button = event.currentTarget;
    button.disabled = true;
    const state = context();
    const person = $('arrival-team-person').value;
    try {
      const url = `/api/field/events/${encodeURIComponent(state.eventId)}/people/${encodeURIComponent(person)}/invite`;
      showInvitation(await api(url, { method: 'POST' }));
      $('arrival-team-error').textContent = '';
    } catch (err) {
      $('arrival-team-error').textContent = err.message;
    } finally {
      button.disabled = false;
    }
  };
  $('arrival-team-close').onclick = () => {
    $('arrival-team-code').value = '';
    team.close();
  };
  $('arrival-team-form').onsubmit = async (event) => {
    event.preventDefault();
    const form = event.target;
    const state = context();
    const values = Object.fromEntries(new FormData(form));
    const root = `/api/field/events/${encodeURIComponent(state.eventId)}`;
    const submit = $('arrival-team-save');
    const error = $('arrival-team-error');
    const email = state.bootstrap?.email_enrollment === true;
    $('arrival-team-status').textContent = '';
    submit.disabled = true;
    try {
      let { channels } = await api(`${root}/directory`);
      let group = channels.find(({ kind, name }) => kind === 'group' && name === '接待组');
      if (!group) group = await api(`${root}/channels`, { method: 'POST', body: JSON.stringify({ name: '接待组' }) });
      const body = {
        name: values.name, callsign: values.callsign, role: values.role, channel_ids: [group.id],
      };
      if (email && values.login_email.trim()) body.login_email = values.login_email.trim();
      const result = await api(`${root}/people`, { method: 'POST', body: JSON.stringify(body) });
      if (email) $('arrival-team-status').textContent = '已添加接待人员';
      else showInvitation(result);
      $('arrival-team-form').reset();
      error.textContent = '';
      await loadLoginRoster(state);
      await refresh();
    } catch (err) {
      error.textContent = err.message;
    } finally {
      submit.disabled = false;
    }
  };
  setInterval(updateCountdowns, 15_000);
  return { render };
}
