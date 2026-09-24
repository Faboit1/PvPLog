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

function createBot (name, opts = {}) {
  const bot = mineflayer.createBot({ host: HOST, port: PORT, username: name, version: process.env.BOT_VERSION || '1.21.11', auth: 'offline', hideErrors: false, ...opts })
  bot.dc = { name, dialogs: [], titles: [], chat: [], matchesEnded: 0, matchState: 'hub', opponent: null }
  bot.on('login', () => log(name, 'login'))
  bot.on('spawn', () => {
    // minecraft-data 26.1 and ViaBackwards disagree on attribute registry ids (e.g. movement_speed arrives under
    // "generic.scale"), so mineflayer would read a wrong movement speed (0 in armor). Use its default speed instead.
    if (bot.physics) bot.physics.movementSpeedAttribute = 'duelcore:ignored'
    log(name, 'spawn', { pos: bot.entity.position.floored(), gm: bot.game.gameMode })
  })
  bot.on('kicked', r => log(name, 'kicked', plain(r)))
  bot.on('error', e => log(name, 'error', e.message))
  bot.on('end', r => log(name, 'end', r))
  bot.on('messagestr', m => { bot.dc.chat.push(m); if (bot.dc.chat.length > 200) bot.dc.chat.shift(); log(name, 'chat', m) })
  bot.on('title', (text, type) => {
    let t
    try { t = plain(text) } catch (e) { t = JSON.stringify(text) }
    bot.dc.titles.push(t)
    log(name, 'title', { type, text: t })
  })
  bot._client.on('show_dialog', packet => {
    try {
      const raw = packet.dialog && (packet.dialog.data || packet.dialog.value || packet.dialog)
      const d = raw && raw.type === 'compound' ? nbt.simplify(raw) : raw
      bot.dc.dialogs.push(d)
      log(name, 'dialog', { title: plain(d && d.title), buttons: buttons(d).map(b => b.label) })
    } catch (e) { log(name, 'dialog-parse-error', e.message) }
  })
  return bot
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
 * Simple melee AI: walk at the nearest other player that is not in spectator mode, attack on cooldown.
 * Stops when stop() is called.
 */
function fighter (bot, opts = {}) {
  let running = true
  let lastAttack = 0
  let lastPos = null
  let lastProgress = Date.now()
  let strafeUntil = 0
  let strafeDir = 'left'
  const cooldown = opts.cooldownMs || 650
  const errors = {}
  const loop = async () => {
    while (running) {
      try {
        const target = bot.nearestEntity(e => e.type === 'player' && e.username !== bot.username &&
          e.position.distanceTo(bot.entity.position) < 60 && !(bot.players[e.username] && bot.players[e.username].gamemode === 3))
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
        bot.setControlState('left', !!(strafing && strafeDir === 'left'))
        bot.setControlState('right', !!(strafing && strafeDir === 'right'))
        bot.setControlState('forward', !!(dist > 2.2))
        bot.setControlState('sprint', !!(dist > 3 && !strafing))
        bot.setControlState('jump', !strafing && (!!bot.entity.isCollidedHorizontally || (!!opts.jump && Math.random() < 0.05)))
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

module.exports = { openFromHotbar, mark, createBot, log, sleep, plain, buttons, click, waitFor, waitDialog, waitChat, waitTitle, useHotbar, heldName, fighter, nbt }
