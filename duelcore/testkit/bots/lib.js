// Shared helpers for DuelCore bot tests. Bots are mineflayer 26.1 clients connecting through ViaBackwards.
const mineflayer = require('mineflayer')
const nbt = require('prismarine-nbt')

const HOST = process.env.MC_HOST || '127.0.0.1'
const PORT = parseInt(process.env.MC_PORT || '25569')
const t0 = Date.now()

function log (who, event, data) {
  const line = { t: ((Date.now() - t0) / 1000).toFixed(2), who, event }
  if (data !== undefined) line.data = data
  console.log(JSON.stringify(line))
}

const sleep = ms => new Promise(r => setTimeout(r, ms))

/** Converts raw prismarine-nbt shapes ({type, value} wrappers) into plain JS values. */
function unwrap (x) {
  if (!x || typeof x !== 'object' || Array.isArray(x)) return x
  if (typeof x.type === 'string' && 'value' in x && Object.keys(x).length === 2) {
    try { return nbt.simplify(x) } catch { return x.value }
  }
  const vals = Object.values(x)
  if (vals.length && vals.every(v => v && typeof v === 'object' && typeof v.type === 'string' && 'value' in v)) {
    try { return nbt.simplify({ type: 'compound', value: x }) } catch { return x }
  }
  return x
}

function plain (json) {
  // flatten a chat component (object, NBT, or JSON string) into plain text
  json = unwrap(json)
  if (json == null) return ''
  if (typeof json === 'string') {
    try { return plain(JSON.parse(json)) } catch { return json }
  }
  if (typeof json !== 'object') return String(json)
  if (Array.isArray(json)) return json.map(plain).join('')
  if (json.type === 'compound' && json.value) return plain(nbt.simplify(json))
  if (json.type === 'list' && json.value) return plain(json.value.value || [])
  let s = ''
  if (json.text !== undefined) s += plain(json.text)
  if (json[''] !== undefined) s += plain(json[''])
  if (json.translate) s += json.translate
  if (json.sprite) s += '[' + json.sprite + ']'
  if (json.extra) s += Array.isArray(json.extra) ? json.extra.map(plain).join('') : plain(json.extra)
  return s
}

// Sentry's anti-bot allows one login per second per IP, and every bot connects from 127.0.0.1:
// each createBot() waits for its own slot 2.2 s after the previous one.
let nextSlot = 0
function createBot (name, opts = {}) {
  const now = Date.now()
  const wait = Math.max(0, nextSlot - now)
  nextSlot = Math.max(now, nextSlot) + 2200
  if (wait > 0) {
    // busy-wait (blocks the event loop for at most ~2.2 s, only while bots are still logging in) so createBot
    // stays synchronous for the scripts that call it
    const start = Date.now() + wait
    while (Date.now() < start) { /* wait for this bot's login slot */ }
  }
  const bot = mineflayer.createBot({ host: HOST, port: PORT, username: name, version: process.env.BOT_VERSION || '1.21.11', auth: 'offline', hideErrors: false, ...opts })
  bot.dc = { name, dialogs: [], titles: [], chat: [], matchesEnded: 0, matchState: 'hub', opponent: null }
  bot.on('login', () => log(name, 'login'))
  bot.on('spawn', () => {
    // minecraft-data 26.1 and ViaBackwards disagree on attribute registry ids (e.g. movement_speed arrives under
    // "generic.scale"), so mineflayer would read a wrong movement speed (0 in armor). Use its default speed instead.
    if (bot.physics) bot.physics.movementSpeedAttribute = 'duelcore:ignored'
    log(name, 'spawn', { pos: bot.entity.position.floored(), gm: bot.game.gameMode })
  })
  // mineflayer bug: since 1.21.9 entity_velocity carries an lpVec3 that minecraft-protocol already decodes to
  // blocks/tick, but mineflayer still scales it by 1/8000 (the old fixed-point factor), so knockback and any
  // server-set velocity reach the bot ~8000x too weak. Re-apply the decoded value after mineflayer's own handler.
  // (registered on login: mineflayer injects its plugins a tick after createBot, and ours must run after its handler)
  bot.once('login', () => {
    bot._client.on('entity_velocity', packet => {
      const e = bot.entities[packet.entityId]
      if (!e || !packet.velocity) return
      e.velocity.set(packet.velocity.x, packet.velocity.y, packet.velocity.z)
    })
  })
  // physics diagnostics for the fighter's "stuck" log: mineflayer only simulates after a position packet, and stops on
  // death, respawn, mount, configuration or when the chunk at the bot's feet is unloaded
  const phys = bot.dc.phys = { ticks: 0, positions: 0, posAt: 0, events: [] }
  const physEvent = e => { phys.events.push(e + '@' + ((Date.now() - t0) / 1000).toFixed(1)); if (phys.events.length > 8) phys.events.shift() }
  bot.on('physicsTick', () => { phys.ticks++ })
  bot.on('respawn', () => physEvent('respawn'))
  bot.on('death', () => physEvent('death'))
  bot.on('mount', () => physEvent('mount'))
  bot.once('login', () => {
    bot._client.on('position', () => { phys.positions++; phys.posAt = Date.now() })
    bot._client.on('start_configuration', () => physEvent('config'))
  })
  // A box that ends exactly flush with a block face (x/z ± 0.3 on a whole number) gets every such move set back by
  // the server: the position reaches it a hair past the face (bots join through ViaBackwards), which counts as moving
  // into the block. Stop a thousandth short of the face instead. (opts.flushGuard === false turns this off.)
  if (opts.flushGuard !== false) {
    bot.on('physicsTick', () => {
      const pos = bot.entity && bot.entity.position
      if (!pos) return
      for (const axis of ['x', 'z']) {
        const hi = pos[axis] + 0.3
        const lo = pos[axis] - 0.3
        if (Math.abs(hi - Math.round(hi)) < 1e-6) pos[axis] -= 1e-3
        else if (Math.abs(lo - Math.round(lo)) < 1e-6) pos[axis] += 1e-3
      }
    })
  }
  // Behind the proxy's LimboAuth: register on the first join, log in afterwards (BOT_AUTH_PASSWORD, from the test
  // kit's bot-env.properties). Each prompt is answered at most once every 3 s.
  const authPassword = process.env.BOT_AUTH_PASSWORD
  if (authPassword) {
    let lastAuth = 0
    bot.on('messagestr', m => {
      const now = Date.now()
      if (now - lastAuth < 3000) return
      if (/\/reg(ister)?\b/i.test(m)) {
        lastAuth = now
        bot.chat('/register ' + authPassword + ' ' + authPassword)
        log(name, 'auth', 'register')
      } else if (/\/l(ogin)?\b/i.test(m)) {
        lastAuth = now
        bot.chat('/login ' + authPassword)
        log(name, 'auth', 'login')
      }
    })
  }
  bot.on('kicked', r => log(name, 'kicked', plain(r)))
  bot.on('error', e => log(name, 'error', e.message))
  bot.on('end', r => log(name, 'end', r))
  bot.on('messagestr', m => { bot.dc.chat.push(m); if (bot.dc.chat.length > 200) bot.dc.chat.shift(); log(name, 'chat', m) })
  // player chat the server re-rendered (party chat, filtered words): real clients show the unsigned content, mineflayer
  // only the signed body, so record what a client would display too
  bot.once('login', () => {
    bot._client.on('player_chat', p => {
      if (!p.unsignedChatContent) return
      const shown = plain(p.unsignedChatContent)
      bot.dc.chat.push(shown)
      if (bot.dc.chat.length > 200) bot.dc.chat.shift()
      log(name, 'chat-shown', shown)
    })
  })
  bot.on('title', (text, type) => {
    let t
    try { t = plain(text) } catch (e) { t = JSON.stringify(text) }
    bot.dc.titles.push(t)
    log(name, 'title', { type, text: t })
  })
  // the dialog flow in arrival order: { type: 'show', index } (into bot.dc.dialogs) or { type: 'clear' }
  bot.dc.flow = []
  bot._client.on('show_dialog', packet => {
    try {
      const raw = packet.dialog && (packet.dialog.data || packet.dialog.value || packet.dialog)
      const d = raw && raw.type === 'compound' ? nbt.simplify(raw) : raw
      bot.dc.dialogs.push(d)
      bot.dc.flow.push({ type: 'show', index: bot.dc.dialogs.length - 1, at: Date.now() })
      log(name, 'dialog', { title: plain(d && d.title), buttons: buttons(d).map(b => b.label) })
    } catch (e) { log(name, 'dialog-parse-error', e.message) }
  })
  bot._client.on('clear_dialog', () => {
    bot.dc.flow.push({ type: 'clear', at: Date.now() })
    log(name, 'clear-dialog')
  })
  return bot
}

/** Waits for a clear_dialog packet after flow position `from` (bot.dc.flow.length before the action). */
async function waitClear (bot, timeoutMs = 5000, from = bot.dc.flow.length) {
  return waitFor(() => bot.dc.flow.slice(from).find(e => e.type === 'clear'), timeoutMs, 'clear_dialog')
}

/**
 * Waits for the next show_dialog after flow position `from` whose title matches, and checks that no
 * clear_dialog came before it (the new dialog replaced the open one). Returns the dialog.
 */
async function waitReplaced (bot, titleRe, timeoutMs = 5000, from = bot.dc.flow.length) {
  const ev = await waitFor(() => bot.dc.flow.slice(from).find(e => e.type === 'show' && titleRe.test(plain(bot.dc.dialogs[e.index].title))),
    timeoutMs, 'dialog ' + titleRe)
  const before = bot.dc.flow.slice(from, bot.dc.flow.indexOf(ev))
  if (before.some(e => e.type === 'clear')) throw new Error('clear_dialog before the next dialog ' + titleRe + ' (close-then-reopen)')
  return bot.dc.dialogs[ev.index]
}

/** All buttons of a dialog with their actions, as { label, id, additions }. */
function buttons (d) {
  if (!d) return []
  const list = []
  const add = b => {
    if (!b) return
    const a = b.action || {}
    list.push({ label: plain(b.label), type: a.type, id: a.id, additions: a.additions })
  }
  ;(d.actions || []).forEach(add)
  add(d.yes); add(d.no); add(d.action); add(d.exit_action)
  return list
}

/** Flat SNBT string payload ({kit:"sword",tab:"weapons"}) to an object. */
function snbt (s) {
  const out = {}
  const re = /([A-Za-z0-9_]+)\s*:\s*"((?:[^"\\]|\\.)*)"/g
  let m
  while ((m = re.exec(s)) !== null) out[m[1]] = m[2]
  return out
}

/**
 * Custom click events inside a dialog's body text (the queue menu's tabs, toggles and kit rows), as
 * { label, id, additions } like buttons(), so they can be sent with click().
 */
function bodyClicks (d) {
  const list = []
  const walk = x => {
    x = unwrap(x)
    if (!x || typeof x !== 'object') return
    if (Array.isArray(x)) { x.forEach(walk); return }
    const ce = x.click_event || x.clickEvent
    if (ce && ce.action === 'custom' && ce.id) {
      const payload = unwrap(ce.payload)
      list.push({ label: plain(x), id: ce.id, additions: typeof payload === 'string' ? snbt(payload) : (payload || {}) })
    }
    for (const [k, v] of Object.entries(x)) if (k !== 'click_event' && v && typeof v === 'object') walk(v)
  }
  walk(d && d.body)
  return list
}

/** All text of a dialog as one string (for regex checks on body contents). */
function dialogText (d) {
  return JSON.stringify(d, (k, v) => typeof v === 'bigint' ? v.toString() : v)
}

/**
 * Joins a kit's queue through the queue menu: opens it from hotbar slot 0, switches tabs until the kit is listed,
 * clicks its row and waits for the re-opened menu showing "Searching". Returns that dialog.
 */
async function queueViaMenu (bot, kit, timeoutMs = 8000) {
  let d = await openFromHotbar(bot, 0, /Queue/, timeoutMs)
  const find = dlg => bodyClicks(dlg).find(x => x.id === 'duelcore:queue/toggle' && x.additions.kit === kit)
  let row = find(d)
  for (const tab of bodyClicks(d).filter(x => x.id === 'duelcore:queue/tab')) {
    if (row) break
    const m = bot.dc.dialogs.length
    await sleep(250) // server click spam guard
    click(bot, tab)
    d = await waitDialog(bot, /Queue/, timeoutMs, m)
    row = find(d)
  }
  if (!row) throw new Error('kit ' + kit + ' not in the queue menu: ' + JSON.stringify(bodyClicks(d).map(x => x.additions)))
  const m = bot.dc.dialogs.length
  await sleep(250)
  click(bot, row)
  return waitFor(() => bot.dc.dialogs.slice(m).find(x => /Searching/.test(dialogText(x))), timeoutMs, 'queued menu for ' + kit)
}

function varint (n) {
  const out = []
  do {
    let b = n & 0x7f
    n >>>= 7
    if (n !== 0) b |= 0x80
    out.push(b)
  } while (n !== 0)
  return Buffer.from(out)
}

function mcString (s) {
  const b = Buffer.from(s, 'utf8')
  return Buffer.concat([varint(b.length), b])
}

let clickPacketId = null
function customClickPacketId (bot) {
  if (clickPacketId !== null) return clickPacketId
  const md = require('minecraft-data')(bot.version)
  const mappings = md.protocol.play.toServer.types.packet[1][0].type[1].mappings
  for (const [id, name] of Object.entries(mappings)) if (name === 'custom_click_action') clickPacketId = parseInt(id, 16)
  return clickPacketId
}

/**
 * Sends the custom click a dialog button would send (inputs merged into the payload like the client does).
 * Encoded by hand: vanilla's ServerboundCustomClickActionPacket is id + a length-prefixed (<= 65536 bytes)
 * optional anonymous NBT tag, which minecraft-data 26.1 describes without the length prefix.
 */
function click (bot, button, inputs = {}) {
  const value = {}
  const additions = button.additions || {}
  for (const [k, v] of Object.entries(additions)) value[k] = nbt.string(String(v))
  for (const [k, v] of Object.entries(inputs)) {
    if (typeof v === 'boolean') value[k] = nbt.byte(v ? 1 : 0)
    else if (typeof v === 'number') value[k] = nbt.float(v)
    else value[k] = nbt.string(String(v))
  }
  const named = nbt.writeUncompressed({ type: 'compound', name: '', value }, 'big')
  const anonymous = Buffer.concat([named.subarray(0, 1), named.subarray(3)]) // drop the empty root name
  const body = Buffer.concat([varint(customClickPacketId(bot)), mcString(button.id), varint(anonymous.length), anonymous])
  log(bot.dc.name, 'click', { id: button.id, payload: Object.fromEntries(Object.entries(value).map(([k, v]) => [k, v.value])) })
  bot._client.writeRaw(body)
}

async function waitFor (pred, timeoutMs, label) {
  const start = Date.now()
  while (Date.now() - start < timeoutMs) {
    const v = pred()
    if (v) return v
    await sleep(100)
  }
  throw new Error('timeout waiting for ' + label)
}

async function waitDialog (bot, titleRe, timeoutMs = 8000, fromIndex) {
  const from = fromIndex === undefined ? bot.dc.dialogs.length : fromIndex
  return waitFor(() => bot.dc.dialogs.slice(from).reverse().find(d => titleRe.test(plain(d && d.title))), timeoutMs, 'dialog ' + titleRe)
}

async function waitChat (bot, re, timeoutMs = 10000, fromIndex) {
  const from = fromIndex === undefined ? bot.dc.chat.length : fromIndex
  return waitFor(() => bot.dc.chat.slice(from).find(m => re.test(m)), timeoutMs, 'chat ' + re)
}

async function waitTitle (bot, re, timeoutMs = 60000, fromIndex) {
  const from = fromIndex === undefined ? bot.dc.titles.length : fromIndex
  return waitFor(() => bot.dc.titles.slice(from).find(t => re.test(t)), timeoutMs, 'title ' + re)
}

/** Right-clicks a hotbar slot and waits for the dialog it opens (marks before clicking, so no race). */
async function openFromHotbar (bot, slot, titleRe, timeoutMs = 8000) {
  const mark = bot.dc.dialogs.length
  await useHotbar(bot, slot)
  return waitDialog(bot, titleRe, timeoutMs, mark)
}

/** Marks for later waits: { chat, titles, dialogs } lengths. */
function mark (bot) {
  return { chat: bot.dc.chat.length, titles: bot.dc.titles.length, dialogs: bot.dc.dialogs.length }
}

async function useHotbar (bot, slot) {
  bot.setQuickBarSlot(slot)
  await sleep(150)
  bot.activateItem()
  await sleep(100)
  bot.deactivateItem()
}

function heldName (bot, slot) {
  const it = bot.inventory.slots[bot.inventory.hotbarStart + slot]
  return it ? it.name : null
}

/**
 * Simple melee AI: walk at the nearest other player, attack on cooldown.
 * Stops when stop() is called.
 */
function fighter (bot, opts = {}) {
  let running = true
  let lastAttack = 0
  let lastPos = null
  let lastProgress = Date.now()
  let strafeUntil = 0
  let strafeDir = 'left'
  let stuckLogs = 0
  let frozenSince = 0
  let lastUnstick = 0
  let realPos = null // progress for the diagnostics only (the strafe logic resets lastProgress)
  let realProgress = Date.now()
  let lastStuckLog = 0
  const cooldown = opts.cooldownMs || 650
  const errors = {}
  const loop = async () => {
    while (running) {
      try {
        // Arena slots are 1024 blocks apart, so the nearest other player is the opponent. (No gamemode filter: the
        // tab-list gamemode can stay "spectator" after a death cam through ViaBackwards, which made bots idle.)
        const target = bot.nearestEntity(e => e.type === 'player' && e.username !== bot.username &&
          e.position.distanceTo(bot.entity.position) < 250)
        if (!target) {
          bot.clearControlStates()
          await sleep(200)
          continue
        }
        const now = Date.now()
        const pos = bot.entity.position
        const horizontal = lastPos ? Math.hypot(pos.x - lastPos.x, pos.z - lastPos.z) : 99
        if (horizontal > 0.4) {
          lastPos = pos.clone()
          lastProgress = now
        }
        const dist = target.position.distanceTo(pos)
        await bot.lookAt(target.position.offset(0, 1.5, 0), true)
        const stuck = dist > 2.5 && now - lastProgress > 600
        if (stuck && now > strafeUntil) {
          strafeDir = Math.random() < 0.5 ? 'left' : 'right'
          strafeUntil = now + 1200
          lastProgress = now
        }
        const strafing = now < strafeUntil
        if (!realPos || Math.hypot(pos.x - realPos.x, pos.z - realPos.z) > 1.5) {
          realPos = pos.clone()
          realProgress = now
        }
        // mineflayer stops simulating after a death until the server sends a position; ask the test kit for one
        const frozen = !bot.entity.onGround && Math.abs(bot.entity.velocity.y) < 0.001
        if (frozen) frozenSince = frozenSince || now
        else frozenSince = 0
        if (frozenSince && now - frozenSince > 1500 && now - lastUnstick > 3000) {
          lastUnstick = now
          bot.chat('!unstick')
          log(bot.dc.name, 'unstick', { pos: pos.floored() })
        }
        if (now - realProgress > 4000 && dist > 2.5 && stuckLogs < 3 && now - lastStuckLog > 5000) {
          // diagnostics: what is around the bot while it can't make progress
          stuckLogs++
          lastStuckLog = now
          const around = {}
          for (const [k, o] of Object.entries({ feet: [0, 0, 0], head: [0, 1, 0], below: [0, -1, 0] })) {
            const blk = bot.blockAt(pos.offset(o[0], o[1], o[2]))
            around[k] = blk && blk.name
          }
          const yaw = bot.entity.yaw
          const ahead = pos.offset(-Math.sin(yaw), 0, -Math.cos(yaw))
          const af = bot.blockAt(ahead)
          const ah = bot.blockAt(ahead.offset(0, 1, 0))
          around.ahead = af && af.name
          around.aheadUp = ah && ah.name
          const ph = bot.dc.phys
          log(bot.dc.name, 'stuck', { pos, dist: +dist.toFixed(1), onGround: bot.entity.onGround,
            collided: bot.entity.isCollidedHorizontally, vel: bot.entity.velocity, controls: bot.controlState,
            around, target: target.position,
            phys: ph && { ticks: ph.ticks, positions: ph.positions, sincePos: ph.posAt ? Date.now() - ph.posAt : -1,
              events: ph.events, enabled: bot.physicsEnabled, feetBlock: !!bot.blockAt(pos), state: bot._client.state,
              vehicle: !!bot.vehicle, alive: bot.isAlive } })
        }
        bot.setControlState('left', !!(strafing && strafeDir === 'left'))
        bot.setControlState('right', !!(strafing && strafeDir === 'right'))
        bot.setControlState('forward', !!(dist > 2.2))
        bot.setControlState('sprint', !!(dist > 3 && !strafing))
        // jump at walls and whenever progress stalls (1-block terrain steps don't always report a collision)
        const stalled = dist > 2.5 && now - lastProgress > 300
        bot.setControlState('jump', !!bot.entity.isCollidedHorizontally || stalled || strafing || (!!opts.jump && Math.random() < 0.05))
        if (dist < 3.1 && now - lastAttack > cooldown) {
          if (opts.slot !== undefined && bot.quickBarSlot !== opts.slot) bot.setQuickBarSlot(opts.slot)
          bot.attack(target)
          lastAttack = now
        }
      } catch (e) {
        errors[e.message] = (errors[e.message] || 0) + 1
        if (errors[e.message] === 1) log(bot.dc.name, 'fighter-error', e.stack && e.stack.split('\n').slice(0, 3).join(' | '))
      }
      await sleep(50)
    }
    bot.clearControlStates()
  }
  loop()
  return { stop: () => { running = false } }
}

module.exports = { waitClear, waitReplaced, openFromHotbar, mark, createBot, log, sleep, plain, buttons, bodyClicks, dialogText, queueViaMenu, click, waitFor, waitDialog, waitChat, waitTitle, useHotbar, heldName, fighter, nbt }
