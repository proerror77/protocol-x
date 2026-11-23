(() => {
  const AIRPORT = 'Chek Lap Kok';
  const METADATA = {
    wind: 'hko_rcd_1634953844424_88011',
    visibility: 'hko_rcd_1634872644049_34294',
    rain: 'hko_rcd_1666097185091_14408',
  };
  const modes = {
    airport: '机场风与能见度', wind: '分区风况', visibility: '分区能见度', rain: '过去一小时雨量',
  };
  const HKO = 'https://www.hko.gov.hk/en/aviat/amt/wxobsfc.htm';
  const RADAR = 'https://www.hko.gov.hk/en/wxinfo/radars/radar.htm';
  const clock = new Intl.DateTimeFormat('zh-CN', {
    timeZone: 'Asia/Hong_Kong', hour: '2-digit', minute: '2-digit', hourCycle: 'h23',
  });
  const sources = new Map();
  let mounted;

  function element(tag, cls, text) {
    const node = document.createElement(tag);
    if (cls) node.className = cls;
    if (text != null) node.textContent = text;
    return node;
  }

  async function get(url, type) {
    const response = await fetch(url, {
      credentials: 'omit', referrerPolicy: 'no-referrer', signal: AbortSignal.timeout(9000),
    });
    if (!response.ok) throw new Error(String(response.status));
    return response[type]();
  }

  function geography(type) {
    if (!sources.has(type)) {
      const url = `https://portal.csdi.gov.hk/server/rest/services/common/${METADATA[type]}/FeatureServer/0/query?f=geojson&outFields=*&where=1%3D1&outSR=4326`;
      sources.set(type, get(url, 'json').then(({ features }) => new Map(features.flatMap((item) => {
        const { geometry, properties } = item;
        if (geometry?.type !== 'Point') return [];
        const [lon, lat] = geometry.coordinates;
        if (!Number.isFinite(lat) || !Number.isFinite(lon) || lat < 22 || lat > 23 || lon < 113 || lon > 115) return [];
        const key = type === 'rain' ? properties.automaticWeatherStationID : properties.AutomaticWeatherStation_en;
        if (!key) return [];
        const name = properties.AutomaticWeatherStation_sc || properties.automaticWeatherStation_sc || key;
        return [[key, { coordinates: [lat, lon], name }]];
      }))).catch((error) => {
        sources.delete(type);
        throw error;
      }));
    }
    return sources.get(type);
  }

  function number(value) {
    if (!/^\d+(?:\.\d+)?$/.test(String(value ?? '').trim())) return null;
    return Number(value);
  }

  function csvRows(csv) {
    return csv.trim().split(/\r?\n/).slice(1).map((line) => (
      [...line.matchAll(/(?:^|,)(?:"((?:[^"]|"")*)"|([^,]*))/g)]
        .map((entry) => (entry[1]?.replaceAll('""', '"') ?? entry[2]).trim())
    ));
  }

  function hkt(raw) {
    const match = /^(\d{4})(\d{2})(\d{2})(\d{2})(\d{2})$/.exec(raw || '');
    return match ? Date.parse(`${match[1]}-${match[2]}-${match[3]}T${match[4]}:${match[5]}:00+08:00`) : NaN;
  }

  async function stations(type) {
    if (type === 'rain') {
      const [geo, sample] = await Promise.all([geography(type),
        get('https://data.weather.gov.hk/weatherAPI/opendata/hourlyRainfall.php?lang=en', 'json')]);
      const time = Date.parse(sample.obsTime);
      return sample.hourlyRainfall.flatMap((item) => {
        const station = geo.get(item.automaticWeatherStationID);
        const amount = number(item.value);
        return station && amount != null ? [{ ...station, key: item.automaticWeatherStationID,
          stamp: time, value: `${amount} mm`, detail: '过去一小时雨量', wet: amount > 0 }] : [];
      });
    }
    const [geo, text] = await Promise.all([geography(type), get(`/golf-tak-admin/weather/${type}`, 'text')]);
    return csvRows(text).flatMap(([timestamp, key, value, average, gust]) => {
      const station = geo.get(key);
      if (!station || !value) return [];
      const stamp = hkt(timestamp);
      if (type === 'visibility') {
        return /^\d+(?:\.\d+)?\s*(m|km)$/i.test(value)
          ? [{ ...station, key, stamp, value, detail: '十分钟平均能见度' }] : [];
      }
      const speed = number(average);
      const direction = { North: '北', East: '东', South: '南', West: '西', Northeast: '东北',
        Northwest: '西北', Southeast: '东南', Southwest: '西南', Variable: '不定' }[value] || value;
      return speed == null ? [] : [{ ...station, key, stamp, value: `${speed} km/h`,
        detail: `十分钟平均风速 · 风向 ${direction}${number(gust) !== null ? ` · 最高阵风 ${gust} km/h` : ''}` }];
    });
  }

  async function readings(mode) {
    if (mode !== 'airport') return stations(mode);
    const replies = await Promise.allSettled([stations('wind'), stations('visibility')]);
    const observations = replies.flatMap((result) => result.status === 'fulfilled'
      ? result.value.filter(({ key, stamp }) => key === AIRPORT && Number.isFinite(stamp)) : []);
    if (!observations.length) return [];
    return [{ ...observations[0], name: 'HKG · 赤鱲角', stationReadings: observations,
      stamp: Math.min(...observations.map(({ stamp }) => stamp)),
      value: observations.map(({ detail, value }) => `${detail.includes('能见度') ? '能见度' : '风'} ${value}`).join(' · ') }];
  }

  function popup(reading, mode) {
    const block = element('div', 'golf-weather-popup');
    block.append(element('strong', '', reading.name));
    for (const observed of reading.stationReadings || [reading]) {
      block.append(element('div', '', `${observed.detail} · ${observed.value}`),
        element('small', '', `观测 ${clock.format(observed.stamp)}（香港时间）`));
    }
    const link = element('a', '', mode === 'rain' ? '香港天文台天气雷达 ↗' : '香港天文台航空气象 ↗');
    link.href = mode === 'rain' ? RADAR : HKO;
    link.target = '_blank';
    link.rel = 'noopener';
    block.append(link);
    return block;
  }

  function render(state) {
    state.layer.clearLayers();
    if (!state.mode) {
      state.status.textContent = '已隐藏';
      return;
    }
    const data = state.snapshots.get(state.mode) || [];
    for (const item of data) {
      const icon = state.leaflet.divIcon({
        className: `golf-weather-marker${item.wet ? ' golf-weather-wet' : ''}`,
        html: element('span', '', state.mode === 'airport' ? `HKG · ${item.value}` : item.value),
        iconSize: [1, 1], iconAnchor: [0, 0],
      });
      state.leaflet.marker(item.coordinates, { icon, title: item.name, zIndexOffset: 220 })
        .bindPopup(popup(item, state.mode)).addTo(state.layer);
    }
    if (!data.length) {
      state.status.textContent = state.failed.has(state.mode) ? '气象数据暂不可用' : '读取香港天文台…';
      return;
    }
    const oldest = Math.min(...data.map(({ stamp }) => stamp));
    const old = Date.now() - oldest > 30 * 60000 || state.failed.has(state.mode);
    state.status.textContent = `${old ? '上次观测' : '观测'} ${clock.format(oldest)} · 香港天文台`;
  }

  async function refresh(state) {
    const mode = state.mode;
    if (document.hidden || !mode || state.inFlight.has(mode)) return;
    state.inFlight.add(mode);
    try {
      const now = Date.now();
      const data = (await readings(mode)).filter(({ stamp }) => Number.isFinite(stamp) && stamp <= now + 60000);
      if (!data.length) throw new Error('no station observations');
      state.snapshots.set(mode, data);
      state.failed.delete(mode);
    } catch {
      state.failed.add(mode);
    } finally {
      state.inFlight.delete(mode);
      if (mounted === state && state.mode === mode) render(state);
    }
  }

  window.addEventListener('golf-tak-map-mounted', ({ detail: { map, leaflet } }) => {
    if (mounted?.map === map) return;
    const status = element('span', 'golf-weather-status', '读取香港天文台…');
    const layer = leaflet.layerGroup().addTo(map);
    const state = { map, leaflet, status, layer, mode: 'airport',
      snapshots: new Map(), failed: new Set(), inFlight: new Set() };
    const control = leaflet.control({ position: 'bottomleft' });
    control.onAdd = () => {
      const box = element('div', 'golf-weather-control');
      const select = element('select');
      select.setAttribute('aria-label', '香港气象层');
      select.add(new Option('关闭气象层', ''));
      for (const [value, label] of Object.entries(modes)) select.add(new Option(label, value));
      select.value = state.mode;
      select.addEventListener('change', () => {
        state.mode = select.value;
        render(state);
        refresh(state);
      });
      box.append(select, status);
      leaflet.DomEvent.disableClickPropagation(box);
      leaflet.DomEvent.disableScrollPropagation(box);
      return box;
    };
    control.addTo(map);
    mounted = state;
    const timer = setInterval(() => refresh(state), 300000);
    const resume = () => refresh(state);
    document.addEventListener('visibilitychange', resume);
    state.destroy = () => {
      clearInterval(timer);
      document.removeEventListener('visibilitychange', resume);
      control.remove();
      layer.remove();
    };
    refresh(state);
  });

  window.addEventListener('golf-tak-map-unmounted', ({ detail }) => {
    if (mounted?.map !== detail.map) return;
    mounted.destroy();
    mounted = null;
  });
})();
