// Kit editor through the real UI, with window clicks (mineflayer bot.clickWindow):
//  1. The Kit Editor hotbar item opens the kit picker and its kit click opens the editor (bot B); /kit edit <kit>
//     opens the 6-row editor (bot A); the hub hotbar is put aside while it is open.
//  2. Two items are swapped (or, for a one-item kit, the item is moved) and one item goes to an empty hotbar slot;
//     the server's window must show exactly that, and the title gets the unsaved "•".
//  3. Shift-click, number key, offhand key, drop / ctrl-drop, double click, a drag over two slots, a click outside
//     the window with an item on the cursor, clicks in the own inventory and on locked slots change nothing, and no
//     kit item ever reaches the bot's inventory or the ground. Right click picks up the whole stack.
//  4. Save: chat confirmation, the editor closes, the hub hotbar is back. Re-opening shows the saved layout.
//  5. /duel between the two bots: A gets the kit in the saved slots, B (no layout) in the default slots.
//     Then A moves an item again and accepts a duel with the editor still open: the editor closes, the new
//     arrangement is saved ("layout was saved") and used in that match.
//  6. After the matches: Clear layout goes back to the default.
// Exit code 0 = pass.
//   node kiteditor.js [kit] [botA] [botB]
const L = require('./lib')
const kit = process.argv[2] || 'sword'
const A = process.argv[3] || 'dcbot_ke_a'
const B = process.argv[4] || 'dcbot_ke_b'

const SIZE = 54 // editor chest; the bot's own inventory follows (54-80 main, 81-89 hotbar)
const OFFHAND = 40
const SAVE = 45
const CLEAR = 50
const CANCEL = 53
/** Editor slot of an inventory position (0-8 hotbar, 9-35 inventory, 36 offhand). */
const editorSlot = pos => pos === 36 ? OFFHAND : pos < 9 ? 27 + pos : pos - 9
/** mineflayer player-inventory slot of a position (hotbar 36-44, inventory 9-35, offhand 45). */
const invSlot = pos => pos === 36 ? 45 : pos < 9 ? 36 + pos : pos
const EDITABLE = [...Array(37).keys()].map(editorSlot)

function spawned (bot) {
  return new Promise(r => bot.once('spawn', r))
}

function fail (msg) {
  throw new Error(msg)
}

function desc (item) {
  return item ? item.name + 'x' + item.count : null
}

/** The editor's 37 editable slots as { editorSlot: 'namexcount' }. */
function arrangement (w) {
  const out = {}
  for (const s of EDITABLE) out[s] = desc(w.slots[s])
  return out
}

function same (x, y) {
  return JSON.stringify(x) === JSON.stringify(y)
}

/** Opens the editor with /kit edit, or with {@code open} (e.g. a picker click). */
async function openEditor (bot, kitId, open) {
  const opened = new Promise((resolve, reject) => {
    const t = setTimeout(() => reject(new Error('editor did not open')), 8000)
    bot.once('windowOpen', w => { clearTimeout(t); resolve(w) })
  })
  if (open) open()
  else bot.chat('/kit edit ' + kitId)
  const w = await opened
  await L.sleep(700) // window items
  const title = L.plain(w.title)
  L.log(bot.dc.name, 'editor-open', { title, slots: w.slots.length })
  if (!/Editing/.test(title)) fail('editor title: ' + title)
  if (w.slots.length < SIZE + 36) fail('editor is not a 6-row chest: ' + w.slots.length)
  return w
}

/** The open window (re-read: a title change re-opens it with the same id). */
function win (bot) {
  const w = bot.currentWindow
  if (!w) fail('no window open')
  return w
}

/** A plain click through mineflayer (it predicts the result like a real client). */
async function clickSlot (bot, slot, button = 0) {
  try {
    await bot.clickWindow(slot, button, 0)
  } catch (e) {
    L.log(bot.dc.name, 'click-warning', { slot, button, error: e.message })
  }
  await L.sleep(350)
}

/**
 * Any container click sent as a raw packet (mineflayer refuses to send drags, double clicks and the offhand key):
 * mode 1 shift, 2 number key (button = hotbar slot, 40 = offhand key), 4 drop, 5 drag, 6 double click. No changed
 * slots are claimed and the state id is stale, so the server answers with a full resync of what it really has.
 */
async function rawClick (bot, slot, button, mode) {
  const w = win(bot)
  const Item = require('prismarine-item')(bot.registry)
  bot._client.write('window_click', {
    windowId: w.id,
    stateId: -1,
    slot,
    mouseButton: button,
    mode,
    changedSlots: [],
    cursorItem: Item.toNotch(w.selectedItem)
  })
  L.log(bot.dc.name, 'raw-click', { slot, button, mode })
  await L.sleep(350)
}

/** Nothing of the kit outside the editable slots: the bot's own inventory part of the window and the cursor. */
function assertNoLeak (bot, label) {
  const w = win(bot)
  const own = []
  for (let s = SIZE; s < w.slots.length; s++) if (w.slots[s]) own.push(s + ':' + desc(w.slots[s]))
  if (own.length) fail(label + ': items in the own inventory while editing: ' + own)
  if (w.selectedItem) fail(label + ': item left on the cursor: ' + desc(w.selectedItem))
  const drops = Object.values(bot.entities).filter(e => e.name === 'item' && e.position.distanceTo(bot.entity.position) < 8)
  if (drops.length) fail(label + ': items on the ground: ' + drops.length)
}

async function assertUnchanged (bot, expected, label) {
  await L.sleep(300)
  assertNoLeak(bot, label)
  const now = arrangement(win(bot))
  if (!same(now, expected)) fail(label + ' changed the editor:\n' + JSON.stringify(now) + '\nexpected\n' + JSON.stringify(expected))
  L.log('test', 'blocked', label)
}

function hubHotbar (bot) {
  return [0, 1, 2, 3, 4, 5, 6, 7, 8].map(s => L.heldName(bot, s))
}

async function main () {
  const a = L.createBot(A)
  const b = L.createBot(B)
  await Promise.all([spawned(a), spawned(b)])
  await L.sleep(3500)
  for (const bot of [a, b]) bot.chat('/party leave')
  await L.sleep(800)
  const hub = hubHotbar(a)
  L.log('test', 'hub-hotbar', hub)
  if (hub[3] !== 'anvil') fail('no Kit Editor item in hotbar slot 4: ' + hub)

  // B: the Kit Editor hotbar item opens the kit picker; clicking the kit there opens its editor
  const picker = await L.openFromHotbar(b, 3, /Kit Editor/)
  const chip = L.bodyClicks(picker).find(x => x.id === 'duelcore:kiteditor/open' && x.additions.kit === kit)
  L.log('test', 'picker', { kits: L.bodyClicks(picker).map(x => x.additions.kit) })
  if (!chip) fail('kit ' + kit + ' is not in the picker')
  let mb = L.mark(b)
  await openEditor(b, kit, () => L.click(b, chip))
  // B starts from the default layout
  await clickSlot(b, CLEAR) // start from the default whatever an earlier run saved
  await L.waitChat(b, /Cleared your|already using the default/, 5000, mb.chat)
  await L.sleep(300)
  const defaults = arrangement(win(b))
  mb = L.mark(b)
  await clickSlot(b, CLEAR)
  await L.waitChat(b, /already using the default/, 5000, mb.chat) // nothing saved any more
  await clickSlot(b, CANCEL)
  await L.sleep(800)
  if (b.currentWindow) fail('Cancel did not close the editor')

  // 1. open, hub hotbar put aside
  let ma = L.mark(a)
  let w = await openEditor(a, kit)
  await clickSlot(a, CLEAR) // start from the default whatever an earlier run saved
  await L.waitChat(a, /Cleared your|already using the default/, 5000, ma.chat)
  w = win(a)
  assertNoLeak(a, 'open')
  const start = arrangement(w)
  if (!same(start, defaults)) fail('A and B see different default layouts')
  const filled = EDITABLE.filter(s => w.slots[s])
  L.log('test', 'kit-items', filled.map(s => s + ':' + desc(w.slots[s])))
  if (filled.length === 0) fail('the kit has no items')

  // 2. moves: swap two items (pick x, swap onto y, put down on x), move one into a free hotbar slot (or, when the
  //    kit fills every slot, swap another pair)
  const expected = { ...start }
  const swap = async (layout, x, y) => {
    await clickSlot(a, x) // pick up x
    await clickSlot(a, y) // swap: y on the cursor, x in y's slot
    await clickSlot(a, x) // put y down in x's slot
    const t = layout[x]
    layout[x] = layout[y]
    layout[y] = t
    L.log('test', 'swapped', { x, y })
  }
  if (filled.length >= 2) await swap(expected, filled[0], filled[1])
  const hotbarSlots = [0, 1, 2, 3, 4, 5, 6, 7, 8].map(editorSlot)
  const from = filled[0]
  const to = hotbarSlots.find(s => !expected[s] && s !== from) ?? EDITABLE.find(s => !expected[s])
  if (to !== undefined) {
    await clickSlot(a, from)
    await clickSlot(a, to)
    expected[to] = expected[from]
    expected[from] = null
    L.log('test', 'moved', { from, to })
  } else if (filled.length >= 2) {
    await swap(expected, filled[0], filled[filled.length - 1])
  }
  await L.sleep(500)
  w = win(a)
  if (!same(arrangement(w), expected)) fail('moves not applied:\n' + JSON.stringify(arrangement(w)) + '\nexpected\n' + JSON.stringify(expected))
  const title = L.plain(w.title)
  if (!/•/.test(title)) fail('no unsaved marker in the title: ' + title)
  assertNoLeak(a, 'after moves')
  L.log('test', 'moves-ok', { title })

  // 3. everything that must not work
  const item = Number(Object.keys(expected).find(s => expected[s]))
  const free = EDITABLE.filter(s => !expected[s])
  const empty = free[0] // undefined when the kit fills every slot
  await rawClick(a, item, 0, 1)
  await assertUnchanged(a, expected, 'shift-click')
  await rawClick(a, item, 2, 2)
  await assertUnchanged(a, expected, 'number key 3')
  if (empty !== undefined) {
    await rawClick(a, empty, 0, 2)
    await assertUnchanged(a, expected, 'number key onto an empty slot')
  }
  await rawClick(a, item, 40, 2)
  await assertUnchanged(a, expected, 'offhand key')
  await rawClick(a, item, 0, 4)
  await assertUnchanged(a, expected, 'drop (Q)')
  await rawClick(a, item, 1, 4)
  await assertUnchanged(a, expected, 'ctrl-drop')
  await rawClick(a, SIZE + 27 + 3, 0, 0) // the bot's own hotbar slot 4
  await assertUnchanged(a, expected, 'own inventory click')
  await rawClick(a, SIZE + 27 + 3, 0, 1)
  await assertUnchanged(a, expected, 'own inventory shift-click')
  await rawClick(a, 36, 0, 0) // locked helmet
  await rawClick(a, 42, 0, 0) // filler
  await rawClick(a, 44, 1, 0) // info
  await assertUnchanged(a, expected, 'locked slots')
  // with an item on the cursor: double click, drop outside, a drag over two slots; then put it back
  await clickSlot(a, item) // pick up
  await L.sleep(300)
  if (!win(a).selectedItem) fail('pick-up: nothing on the cursor')
  await rawClick(a, item, 0, 6) // double click (collect to cursor)
  await rawClick(a, -999, 0, 0) // click outside the window: drop the cursor
  await rawClick(a, -999, 1, 0) // right click outside: drop one
  if (desc(win(a).selectedItem) !== expected[item]) fail('the cursor stack changed: ' + desc(win(a).selectedItem))
  await clickSlot(a, item) // put the stack back where it was
  await assertUnchanged(a, expected, 'double click and drop outside with an item on the cursor')
  // a drag over two slots would split a stack (a drag over one slot is an ordinary click); needs a stack of 2+
  const stack = EDITABLE.filter(s => expected[s]).sort((x, y) => win(a).slots[y].count - win(a).slots[x].count)[0]
  const freeTwo = free.slice(0, 2)
  if (win(a).slots[stack].count >= 2 && freeTwo.length === 2) {
    await clickSlot(a, stack)
    await rawClick(a, -999, 0, 5) // left drag start
    for (const s of freeTwo) await rawClick(a, s, 1, 5)
    await rawClick(a, -999, 2, 5) // drag end
    await L.sleep(400)
    const midDrag = arrangement(win(a))
    for (const s of freeTwo) if (midDrag[s]) fail('the drag placed items: ' + JSON.stringify(midDrag))
    if (desc(win(a).selectedItem) !== expected[stack]) fail('the drag changed the cursor stack: ' + desc(win(a).selectedItem))
    await clickSlot(a, stack)
    await assertUnchanged(a, expected, 'drag over two slots')
  } else {
    L.log('test', 'note', 'no stack of 2+ and two free slots in this kit: drag test skipped (try bow)')
  }
  // right click picks up the whole stack (vanilla would take half) and puts it down whole (vanilla: one)
  if (empty !== undefined) {
    const before = expected[stack]
    await clickSlot(a, stack, 1)
    await clickSlot(a, empty, 1)
    await L.sleep(300)
    const moved = arrangement(win(a))
    if (moved[empty] !== before || moved[stack]) fail('right click did not move the whole stack: ' + JSON.stringify(moved))
    await clickSlot(a, empty, 0)
    await clickSlot(a, stack, 0)
    await assertUnchanged(a, expected, 'right-click round trip')
  }

  // 4. save: chat, editor closes, hub hotbar back
  ma = L.mark(a)
  const closed = new Promise(r => a.once('windowClose', r))
  await clickSlot(a, SAVE)
  await L.waitChat(a, /Saved your|Using the default layout/, 5000, ma.chat)
  await Promise.race([closed, L.sleep(3000)])
  await L.sleep(800)
  if (a.currentWindow) fail('Save did not close the editor')
  const back = hubHotbar(a)
  if (!same(back, hub)) fail('hub hotbar not restored: ' + back + ' vs ' + hub)
  const own = a.inventory.slots.filter(Boolean).map(x => x.name)
  if (own.some(n => !hub.includes(n))) fail('kit items leaked into the inventory: ' + own)
  L.log('test', 'saved', { hub: back })

  // the saved layout is what the editor opens with; Escape keeps it and gives the hotbar back
  w = await openEditor(a, kit)
  if (!same(arrangement(w), expected)) fail('re-opened editor is not the saved layout: ' + JSON.stringify(arrangement(w)))
  a.closeWindow(w)
  await L.sleep(1000)
  if (!same(hubHotbar(a), hub)) fail('hub hotbar not restored after Escape')
  L.log('test', 'reopen-ok')

  // 5. a duel: A in the saved slots, B in the default slots
  ma = L.mark(a)
  mb = L.mark(b)
  a.chat('/duel ' + B + ' ' + kit)
  await L.waitChat(b, /challenged you/, 8000, mb.chat)
  b.chat('/duel accept ' + A)
  await Promise.all([L.waitTitle(a, /Match found/i, 20000, ma.titles), L.waitTitle(b, /Match found/i, 20000, mb.titles)])
  const toPos = s => s === OFFHAND ? 36 : s >= 27 && s < 36 ? s - 27 : s + 9
  const check = (bot, layout) => {
    const wrong = []
    for (const s of EDITABLE) {
      const got = desc(bot.inventory.slots[invSlot(toPos(s))])
      if (got !== layout[s]) wrong.push({ pos: toPos(s), got, want: layout[s] })
    }
    return wrong
  }
  let wrongA = null
  let wrongB = null
  await L.waitFor(() => {
    wrongA = check(a, expected)
    wrongB = check(b, defaults)
    return wrongA.length === 0 && wrongB.length === 0
  }, 45000, 'kits in the arena').catch(() => {
    fail('kit layout in the match: A ' + JSON.stringify(wrongA) + ' B ' + JSON.stringify(wrongB))
  })
  L.log('test', 'match-layouts-ok')

  // end the match (B forfeits), back in the hub
  const forfeit = async () => {
    const m = L.mark(a)
    b.chat('/leave')
    await L.sleep(500)
    b.chat('/leave')
    await L.waitTitle(a, /Victory/, 30000, m.titles)
    await L.sleep(6000) // results, back to the hub
  }
  await forfeit()

  // 5b. a match found while editing: the editor closes and the new arrangement is saved and used
  w = await openEditor(a, kit)
  const next = { ...expected }
  const src = EDITABLE.find(s => next[s])
  const dst = [...hotbarSlots].reverse().find(s => !next[s] && s !== src) // (not back into its default slot)
  if (dst !== undefined) {
    await clickSlot(a, src)
    await clickSlot(a, dst)
    next[dst] = next[src]
    next[src] = null
  } else {
    // a full kit: swap two differently looking items
    const other = EDITABLE.find(s => next[s] && next[s] !== next[src])
    if (other === undefined) fail('nothing to rearrange')
    await swap(next, src, other)
  }
  await L.sleep(300)
  if (!same(arrangement(win(a)), next)) fail('second move not applied')
  ma = L.mark(a)
  mb = L.mark(b)
  b.chat('/duel ' + A + ' ' + kit)
  await L.waitChat(a, /challenged you/, 8000, ma.chat)
  a.chat('/duel accept ' + B) // a command typed while the editor is open
  await L.waitChat(a, /layout was saved/, 10000, ma.chat)
  await L.waitFor(() => check(a, next).length === 0 && check(b, defaults).length === 0, 45000, 'auto-saved layout in the arena')
    .catch(() => fail('auto-saved layout in the match: A ' + JSON.stringify(check(a, next)) + ' B ' + JSON.stringify(check(b, defaults))))
  L.log('test', 'auto-save-ok')
  await forfeit()

  // 6. Clear layout: back to the default
  ma = L.mark(a)
  w = await openEditor(a, kit)
  await clickSlot(a, CLEAR)
  await L.waitChat(a, /Cleared your/, 5000, ma.chat)
  await L.sleep(400)
  if (!same(arrangement(win(a)), defaults)) fail('Clear layout did not go back to the default')
  await clickSlot(a, CANCEL)
  await L.sleep(800)
  if (!same(hubHotbar(a), hub)) fail('hub hotbar not restored after Cancel')

  a.quit(); b.quit()
  await L.sleep(1000)
  L.log('test', 'PASS')
  process.exit(0)
}
main().catch(e => { L.log('test', 'FAIL', e.message); process.exit(1) })
setTimeout(() => { L.log('test', 'FAIL', 'global timeout'); process.exit(2) }, 300000)
