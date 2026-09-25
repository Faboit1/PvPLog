// Visual/audio checks that need packets rather than dialogs:
//  - hub flight is allowed in the hub (abilities packet) and again after a match
//  - hotbar action-bar hints: a hint when holding a hub item, an empty action bar on an empty slot
//  - queue music: a music disc starts while searching and is stopped when the match is found
//  - match found: totem pop (entity status 35) followed by a stop_sound for item.totem.use, plus other sounds
//  - rising spawn platform: the player starts below the spawn and rises; block displays are spawned around them
//  - tab: a spectator's tab entry is italic, and the footer shows how many are watching
// Exit code 0 = pass; every observation is logged either way.
//   node visuals.js [kit]
const L = require('./lib')
const kit = process.argv[2] || 'sword'

function spawned (bot) {
  return new Promise(r => bot.once('spawn', r))
}

function record (bot) {
  const r = { sounds: [], stops: [], bars: [], flags: [], status: [], tab: {}, names: {}, footer: '', boss: 0 }
  bot.dc.rec = r
  bot.once('login', () => {
    const byId = id => (bot.registry.sounds && bot.registry.sounds[id] && bot.registry.sounds[id].name) || ('#' + id)
    const soundName = h => {
      if (!h) return '?'
      if (h.soundId > 0) return byId(h.soundId)
      return (h.data && (h.data.soundName || h.data.sound)) || JSON.stringify(h).slice(0, 60)
    }
    const c = bot._client
    c.on('sound_effect', p => r.sounds.push({ t: Date.now(), name: soundName(p.sound), cat: p.soundCategory }))
    c.on('entity_sound_effect', p => r.sounds.push({ t: Date.now(), name: soundName(p.sound), cat: p.soundCategory, entity: p.entityId }))
    c.on('stop_sound', p => r.stops.push({ t: Date.now(), flags: p.flags, source: p.source, sound: p.sound }))
    c.on('boss_bar', () => { r.boss++ })
    c.on('action_bar', p => r.bars.push({ t: Date.now(), text: L.plain(p.text) }))
    c.on('abilities', p => r.flags.push({ t: Date.now(), flags: p.flags }))
    c.on('entity_status', p => { if (bot.entity && p.entityId === bot.entity.id) r.status.push({ t: Date.now(), s: p.entityStatus }) })
    c.on('playerlist_header', p => { r.footer = L.plain(p.footer) })
    c.on('player_info', p => {
      for (const e of p.data || []) {
        if (e.player && e.player.name) r.names[e.uuid] = e.player.name
        if (e.displayName !== undefined) {
          const raw = e.displayName == null ? null : JSON.stringify(e.displayName, (k, v) => typeof v === 'bigint' ? v.toString() : v)
          r.tab[r.names[e.uuid] || e.uuid] = raw
        }
      }
    })
  })
}

const canFly = r => r.flags.length > 0 && (r.flags[r.flags.length - 1].flags & 0x04) !== 0

async function main () {
  const a = L.createBot('dcbot_va')
  const b = L.createBot('dcbot_vb')
  const c = L.createBot('dcbot_vc')
  for (const bot of [a, b, c]) record(bot)
  await Promise.all([a, b, c].map(spawned))
  await L.sleep(4000)
  const ra = a.dc.rec
  const results = {}

  // hub flight
  results.hubFlight = canFly(ra)
  L.log('test', 'hub-flight', { allowed: results.hubFlight, flags: ra.flags.slice(-3) })

  // hotbar hints
  a.setQuickBarSlot(2)
  await L.sleep(800)
  const hint = ra.bars.filter(x => Date.now() - x.t < 1000 && x.text.trim()).pop()
  a.setQuickBarSlot(3)
  await L.sleep(800)
  const cleared = ra.bars.filter(x => Date.now() - x.t < 1000).pop()
  a.setQuickBarSlot(0)
  results.hint = !!hint
  results.hintCleared = !!cleared && cleared.text.trim() === ''
  L.log('test', 'hints', { hint: hint && hint.text, cleared: cleared && JSON.stringify(cleared.text) })

  // queue music
  const qFrom = Date.now()
  let ma = L.mark(a)
  a.chat('/queue ' + kit)
  await L.sleep(3000)
  const music = ra.sounds.filter(s => s.t >= qFrom && /music_disc/.test(s.name))
  results.musicStarted = music.length > 0
  L.log('test', 'queue-music', { music: music.map(s => s.name + '/' + s.cat), all: ra.sounds.filter(s => s.t >= qFrom).map(s => s.name) })

  // match found: totem pop + stop sounds + varied sounds
  const mFrom = Date.now()
  const mb = L.mark(b)
  b.chat('/queue ' + kit)
  await Promise.all([L.waitTitle(a, /Match found/i, 20000, ma.titles), L.waitTitle(b, /Match found/i, 20000, mb.titles)])
  const foundAt = Date.now()
  // rising platform: sample our height and nearby block displays
  const ys = []
  let displays = 0
  const sampler = setInterval(() => {
    if (a.entity) ys.push(+a.entity.position.y.toFixed(2))
    const n = Object.values(a.entities).filter(e => e.name === 'block_display' && a.entity && e.position.distanceTo(a.entity.position) < 6).length
    displays = Math.max(displays, n)
  }, 100)
  await L.waitTitle(a, /Fight/i, 40000, ma.titles)
  clearInterval(sampler)
  const after = ra.sounds.filter(s => s.t >= mFrom).map(s => s.name)
  const stops = ra.stops.filter(s => s.t >= mFrom)
  results.totemPop = ra.status.some(s => s.t >= mFrom && s.s === 35)
  results.totemStopped = stops.some(s => /totem/.test(String(s.sound)))
  results.musicStopped = stops.some(s => s.t >= foundAt - 1500 && (s.source === 8 || /music_disc/.test(String(s.sound)) || s.flags === 1))
  L.log('test', 'match-found-audio', { totemPop: results.totemPop, stops, sounds: after.slice(0, 20) })
  const minY = Math.min(...ys)
  const lastY = ys[ys.length - 1]
  results.rise = ys.length > 0 && lastY - minY >= 1.5
  results.displays = displays
  L.log('test', 'spawn-rise', { minY, lastY, samples: ys.length, maxDisplaysNearby: displays, first: ys.slice(0, 5), end: ys.slice(-5) })

  // spectator in tab
  const mc = L.mark(c)
  c.chat('/spectate dcbot_va')
  await L.sleep(3500)
  const specEntry = ra.tab.dcbot_vc
  results.spectatorItalic = !!specEntry && /italic/.test(specEntry)
  results.footer = ra.footer
  L.log('test', 'spectator-tab', { entry: specEntry, footer: ra.footer, cChat: c.dc.chat.slice(mc.chat) })
  c.chat('/spectate stop')
  await L.sleep(2500)
  results.spectatorRestored = !(ra.tab.dcbot_vc && /"italic":true|italic:1b/.test(ra.tab.dcbot_vc))
  L.log('test', 'spectator-tab-after', { entry: ra.tab.dcbot_vc })

  // end the match, flight back in the hub
  ma = L.mark(a)
  a.chat('/leave')
  await L.sleep(700)
  a.chat('/leave')
  await L.sleep(8000)
  results.hubFlightAfter = canFly(ra)
  results.noBossBar = ra.boss === 0
  results.clientMusicStopped = ra.stops.some(s => s.flags === 1 && s.source === 1)
  L.log('test', 'results', results)
  const must = ['noBossBar', 'clientMusicStopped', 'hubFlight', 'hint', 'hintCleared', 'musicStarted', 'musicStopped', 'totemPop', 'totemStopped', 'rise', 'spectatorItalic', 'hubFlightAfter']
  const failed = must.filter(k => !results[k])
  if (failed.length) throw new Error('failed: ' + failed.join(', '))
  L.log('test', 'pass')
  process.exit(0)
}
main().catch(e => { L.log('test', 'fail', e.message); process.exit(1) })
setTimeout(() => { L.log('test', 'fail', 'global timeout'); process.exit(1) }, 120000)
