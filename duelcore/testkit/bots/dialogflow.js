// Dialog flow: dialogs stay open after a click (after-action "none") and the next dialog replaces them, open menus
// refresh themselves. Checks:
//  1. queue menu → click a tab: the next menu arrives without a clear_dialog in between; every kit row has a ✎
//     (duelcore:kiteditor/open for that kit); Close is a duelcore:dialog/close click and brings a clear_dialog;
//  2. queued with the menu open: at least two refreshed menus with a changing search timer within ~3 s, none after
//     Close;
//  3. the Friends dialog is sent again (no click, no clear_dialog) when a friend comes online, showing them online.
// args: [kit] [prefix] (bots <prefix>a and <prefix>b; the kit must have nobody else searching). Exit code 0 = pass.
const L = require('./lib')
const KIT = process.argv[2] || 'sword'
const P = process.argv[3] || 'dcbot_df'
const A = P + 'a'
const B = P + 'b'

function spawned (bot) {
  return new Promise(r => bot.once('spawn', r))
}

function closeButton (d) {
  const close = L.buttons(d).find(x => x.id === 'duelcore:dialog/close')
  if (!close) throw new Error('no duelcore:dialog/close button in ' + L.plain(d.title) + ': ' + JSON.stringify(L.buttons(d)))
  return close
}

/** The search timer ("0:07") of the queued kit in a queue menu, or null. */
function timer (d) {
  const m = L.dialogText(d).match(/"(\d+:\d{2})"/)
  return m ? m[1] : null
}

/** The tooltip text of the Friends dialog entry for `name`, or null. */
function entryTooltip (d, name) {
  const b = (d.actions || []).find(x => L.plain(x.label).includes(name))
  return b ? L.plain(b.tooltip) : null
}

async function main () {
  const a = L.createBot(A)
  let b = L.createBot(B)
  await Promise.all([a, b].map(spawned))
  await L.sleep(3000)

  // ---- 1. tab switch without close-then-reopen; ✎ per kit row; Close clears
  let d = await L.openFromHotbar(a, 0, /Queue/)
  const rows = L.bodyClicks(d).filter(x => x.id === 'duelcore:queue/toggle').map(x => x.additions.kit)
  const edits = L.bodyClicks(d).filter(x => x.id === 'duelcore:kiteditor/open').map(x => x.additions.kit)
  L.log('test', 'queue-menu', { rows, edits })
  if (!rows.length) throw new Error('no kit rows in the queue menu')
  for (const kit of rows) if (!edits.includes(kit)) throw new Error('kit row without ✎: ' + kit)
  const tabs = L.bodyClicks(d).filter(x => x.id === 'duelcore:queue/tab')
  if (tabs.length < 2) throw new Error('expected several tabs: ' + JSON.stringify(tabs))
  for (const tab of tabs.slice(-2)) {
    const from = a.dc.flow.length
    await L.sleep(250) // server click spam guard
    L.click(a, tab)
    d = await L.waitReplaced(a, /Queue/, 5000, from)
    L.log('test', 'tab-replaced', { tab: tab.additions.tab })
  }
  let from = a.dc.flow.length
  await L.sleep(250)
  L.click(a, closeButton(d))
  await L.waitClear(a, 3000, from)
  L.log('test', 'close-clears')

  // ---- 2. queued with the menu open: refreshed with a ticking timer, nothing after Close
  await L.sleep(500)
  d = await L.queueViaMenu(a, KIT)
  const start = a.dc.dialogs.length
  from = a.dc.flow.length
  await L.sleep(3300)
  const refreshed = a.dc.dialogs.slice(start).filter(x => /Queue/.test(L.plain(x.title)))
  const timers = refreshed.map(timer)
  L.log('test', 'refreshed', { count: refreshed.length, timers })
  if (refreshed.length < 2) throw new Error('expected at least 2 refreshed queue menus in 3.3 s, got ' + refreshed.length)
  if (new Set(timers.filter(Boolean)).size < 2) throw new Error('the search timer did not change: ' + timers)
  if (a.dc.flow.slice(from).some(e => e.type === 'clear')) throw new Error('clear_dialog during the refresh')
  from = a.dc.flow.length
  await L.sleep(250)
  L.click(a, closeButton(refreshed[refreshed.length - 1]))
  await L.waitClear(a, 3000, from)
  const afterClose = a.dc.dialogs.length
  await L.sleep(2500)
  if (a.dc.dialogs.length !== afterClose) throw new Error('dialogs arrived after Close: ' + (a.dc.dialogs.length - afterClose))
  L.log('test', 'no-refresh-after-close')
  a.chat('/leave')
  await L.sleep(1000)

  // ---- 3. the Friends dialog follows a friend coming online
  let ma = L.mark(a)
  a.chat('/follow ' + B)
  await L.waitChat(a, new RegExp('follow ' + B), 6000, ma.chat)
  await L.sleep(400)
  const mb = L.mark(b)
  b.chat('/follow ' + A)
  await L.waitChat(b, /now friends|follow/, 6000, mb.chat)
  await L.sleep(500)
  b.quit()
  await L.sleep(3000)
  ma = L.mark(a)
  a.chat('/friends')
  d = await L.waitDialog(a, /Friends/, 6000, ma.dialogs)
  const offline = entryTooltip(d, B)
  L.log('test', 'friends-offline', offline)
  if (!offline || !/Offline/.test(offline)) throw new Error(B + ' should be listed offline: ' + offline)
  from = a.dc.flow.length
  const beforeJoin = a.dc.dialogs.length
  b = L.createBot(B)
  await spawned(b)
  const online = await L.waitFor(() => a.dc.dialogs.slice(beforeJoin)
    .find(x => /Friends/.test(L.plain(x.title)) && /Online/.test(entryTooltip(x, B) || '')), 8000, 'refreshed Friends dialog')
  if (a.dc.flow.slice(from).some(e => e.type === 'clear')) throw new Error('clear_dialog before the refreshed Friends dialog')
  L.log('test', 'friends-refreshed', entryTooltip(online, B))
  from = a.dc.flow.length
  await L.sleep(250)
  L.click(a, closeButton(online))
  await L.waitClear(a, 3000, from)

  // cleanup: follows persist in the database between runs
  a.chat('/unfollow ' + B)
  await L.sleep(300)
  b.chat('/unfollow ' + A)
  await L.sleep(1000)
  L.log('test', 'pass')
  process.exit(0)
}
main().catch(e => { L.log('test', 'fail', e.message); process.exit(1) })
setTimeout(() => { L.log('test', 'fail', 'global timeout'); process.exit(1) }, 120000)
