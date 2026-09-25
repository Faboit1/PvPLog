// Friends (follow-based): follow by command, the followed player's clickable [Follow back] making both friends, the
// Friends dialog (entries, tooltips, filter cycling, "Add Back" for followers), following back from the dialog,
// /friends list, online/offline alerts and persistence across a rejoin. Exit code 0 = pass.
const L = require('./lib')

function spawned (bot) {
  return new Promise(r => bot.once('spawn', r))
}

/** Custom click events in chat messages, as { label, id, additions } for L.click. */
function watchChatClicks (bot) {
  bot.dc.chatClicks = []
  bot.once('login', () => {
    bot._client.on('system_chat', p => {
      const walk = x => {
        if (x && typeof x === 'object' && x.type && 'value' in x) {
          try { x = L.nbt.simplify(x) } catch { }
        }
        if (!x || typeof x !== 'object') return
        if (Array.isArray(x)) { x.forEach(walk); return }
        const ce = x.click_event || x.clickEvent
        if (ce && ce.action === 'custom' && ce.id) {
          const additions = {}
          const re = /([A-Za-z0-9_]+)\s*:\s*"((?:[^"\\]|\\.)*)"/g
          let m
          const s = typeof ce.payload === 'string' ? ce.payload : JSON.stringify(ce.payload || '')
          while ((m = re.exec(s)) !== null) additions[m[1]] = m[2]
          if (typeof ce.payload === 'object' && ce.payload) Object.assign(additions, ce.payload)
          bot.dc.chatClicks.push({ label: L.plain(x), id: ce.id, additions })
        }
        for (const v of Object.values(x)) if (v && typeof v === 'object') walk(v)
      }
      walk(p.content)
    })
  })
}

async function main () {
  const a = L.createBot('dcbot_fa')
  let b = L.createBot('dcbot_fb')
  const c = L.createBot('dcbot_fc')
  for (const bot of [a, b, c]) watchChatClicks(bot)
  await Promise.all([a, b, c].map(spawned))
  await L.sleep(4000)

  // clean slate: follows persist in the database between runs
  for (const [x, others] of [[a, ['dcbot_fb', 'dcbot_fc']], [b, ['dcbot_fa', 'dcbot_fc']], [c, ['dcbot_fa', 'dcbot_fb']]]) {
    for (const o of others) { x.chat('/unfollow ' + o); await L.sleep(300) }
  }
  await L.sleep(1500)

  // 1. a follows b; b gets a clickable [Follow back]; clicking it makes them friends
  let ma = L.mark(a)
  let mb = L.mark(b)
  const clicksBefore = b.dc.chatClicks.length
  a.chat('/follow dcbot_fb')
  await L.waitChat(a, /You now follow dcbot_fb/, 6000, ma.chat)
  await L.waitChat(b, /dcbot_fa followed you/, 6000, mb.chat)
  const back = await L.waitFor(() => b.dc.chatClicks.slice(clicksBefore).find(x => x.id.startsWith('duelcore:friend/')), 4000, 'follow-back click')
  L.log('test', 'follow-back-link', back)
  await L.sleep(300)
  L.click(b, back)
  await Promise.all([L.waitChat(a, /now friends/, 6000, ma.chat), L.waitChat(b, /now friends/, 6000, mb.chat)])
  L.log('test', 'friends-via-chat-link')

  // 2. a's Friends dialog lists b as a mutual friend
  ma = L.mark(a)
  a.chat('/friends')
  let d = await L.waitDialog(a, /Friends/, 6000, ma.dialogs)
  let labels = L.buttons(d).map(x => x.label)
  L.log('test', 'dialog', { labels, body: L.plain(d.body && (d.body.contents || d.body)) })
  for (const need of ['Add Friends', 'Refresh', 'Filter: All']) {
    if (!labels.some(l => l.includes(need))) throw new Error('missing button ' + need)
  }
  const entryB = L.buttons(d).find(x => x.label.includes('dcbot_fb'))
  if (!entryB) throw new Error('b not listed')
  if (!/Mutual/.test(L.dialogText(d))) throw new Error('no mutual tooltip')

  // 3. c follows a: a sees c under Followers with "Add Back", clicking it makes them friends
  const mc = L.mark(c)
  ma = L.mark(a)
  c.chat('/follow dcbot_fa')
  await L.waitChat(c, /You now follow dcbot_fa/, 6000, mc.chat)
  await L.waitChat(a, /dcbot_fc followed you/, 6000, ma.chat)
  let dlg = d
  for (let i = 0; i < 4; i++) {
    const filter = L.buttons(dlg).find(x => x.label.startsWith('Filter'))
    if (/Followers/.test(filter.label)) break
    const m = L.mark(a)
    await L.sleep(250)
    L.click(a, filter)
    dlg = await L.waitDialog(a, /Friends/, 5000, m.dialogs)
  }
  labels = L.buttons(dlg).map(x => x.label)
  L.log('test', 'followers-filter', labels)
  const addBack = L.buttons(dlg).find(x => x.label.includes('dcbot_fc') && /Add Back/.test(x.label))
  if (!addBack) throw new Error('no Add Back entry for c')
  ma = L.mark(a)
  await L.sleep(250)
  L.click(a, addBack)
  await L.waitChat(a, /now friends/, 6000, ma.chat)
  L.log('test', 'add-back-ok')

  // 4. /friends list
  ma = L.mark(a)
  a.chat('/friends list')
  const header = await L.waitChat(a, /Friends/, 5000, ma.chat)
  L.log('test', 'list', header)
  if (!/2 friends/.test(header)) throw new Error('expected 2 friends: ' + header)

  // 5. alerts + persistence: b leaves and comes back
  ma = L.mark(a)
  b.quit()
  await L.waitChat(a, /dcbot_fb went offline/, 8000, ma.chat)
  await L.sleep(3000)
  b = L.createBot('dcbot_fb')
  await spawned(b)
  await L.waitChat(a, /dcbot_fb is online/, 10000, ma.chat)
  await L.sleep(2500)
  mb = L.mark(b)
  b.chat('/friends list')
  const bl = await L.waitChat(b, /Friends/, 6000, mb.chat)
  L.log('test', 'b-list-after-rejoin', bl)
  if (!/1 friends|1 friend/.test(bl)) throw new Error('b lost its friendship: ' + bl)

  // cleanup
  for (const [x, others] of [[a, ['dcbot_fb', 'dcbot_fc']], [b, ['dcbot_fa']], [c, ['dcbot_fa']]]) {
    for (const o of others) { x.chat('/unfollow ' + o); await L.sleep(300) }
  }
  await L.sleep(1000)
  L.log('test', 'pass')
  process.exit(0)
}
main().catch(e => { L.log('test', 'fail', e.message); process.exit(1) })
setTimeout(() => { L.log('test', 'fail', 'global timeout'); process.exit(1) }, 150000)
