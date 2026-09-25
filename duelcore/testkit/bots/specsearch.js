// Spectate menu: search, always sorted by Elo then name. Two ranked matches run at the same time (a high-rated sword pair
// and a low-rated shield pair, ratings set by the operator beforehand), a fifth bot opens the spectate menu and
// checks: the high-Elo match is listed first, a name search narrows to one match, a search for nothing shows the
// no-results text, there is no sort input, and a click on a result starts spectating. Exit code 0 = pass.
// args: <prefix> (bots <prefix>0..3 fight, <prefix>spec watches)
const L = require('./lib')
const P = process.argv[2] || 'dcbot_s'
const names = [0, 1, 2, 3].map(i => P + i)
const SPEC = P + 'spec'

async function main () {
  const bots = names.map(n => L.createBot(n))
  const spec = L.createBot(SPEC)
  await Promise.all([...bots, spec].map(b => new Promise(r => b.once('spawn', r))))
  await L.sleep(3000)
  const marks = bots.map(b => L.mark(b))
  bots[0].chat('/queue sword ranked'); bots[1].chat('/queue sword ranked')
  bots[2].chat('/queue shield ranked'); bots[3].chat('/queue shield ranked')
  await Promise.all(bots.map((b, i) => L.waitTitle(b, /Fight/i, 60000, marks[i].titles)))
  L.log('test', 'both-matches-fighting')

  const d = await L.openFromHotbar(spec, 8, /Live/)
  const btns = L.buttons(d)
  const labels = btns.map(x => x.label)
  L.log('test', 'spectate-menu', { labels, inputs: (d.inputs || []).map(i => i.key) })
  if (btns[0].id !== 'duelcore:spectate/search') throw new Error('search button is not first: ' + labels[0])
  const keys = (d.inputs || []).map(i => i.key)
  if (!keys.includes('search') || keys.includes('sort')) throw new Error('expected only a search input: ' + keys)
  const matchBtns = btns.filter(x => x.id === 'duelcore:spectate/match')
  if (matchBtns.length < 2) throw new Error('expected 2 live matches, got ' + matchBtns.length)
  if (!matchBtns[0].label.includes(names[0]) && !matchBtns[0].label.includes(names[1])) {
    throw new Error('high-Elo sword match should be first: ' + matchBtns.map(x => x.label).join(' | '))
  }
  L.log('test', 'elo-sort-ok', { first: matchBtns[0].label })

  // search by name → only the shield match
  await L.sleep(400)
  let m = L.mark(spec)
  L.click(spec, btns[0], { search: names[2] })
  const d2 = await L.waitDialog(spec, /Live/, 5000, m.dialogs)
  const found = L.buttons(d2).filter(x => x.id === 'duelcore:spectate/match')
  L.log('test', 'search-name', { query: names[2], results: found.map(x => x.label) })
  if (found.length !== 1 || !found[0].label.includes(names[2])) throw new Error('name search failed')

  // search by kit name → the shield match too
  await L.sleep(400) // server ignores clicks within 150 ms of the previous one
  m = L.mark(spec)
  L.click(spec, btns[0], { search: 'shield' })
  const d3 = await L.waitDialog(spec, /Live/, 5000, m.dialogs)
  const byKit = L.buttons(d3).filter(x => x.id === 'duelcore:spectate/match')
  L.log('test', 'search-kit', { results: byKit.map(x => x.label) })
  if (byKit.length !== 1) throw new Error('kit search failed')

  // nonsense → no results text, search still available
  await L.sleep(400) // server ignores clicks within 150 ms of the previous one
  m = L.mark(spec)
  L.click(spec, btns[0], { search: 'zzzz_nobody' })
  const d4 = await L.waitDialog(spec, /Live/, 5000, m.dialogs)
  const body = JSON.stringify(d4.body || '')
  L.log('test', 'search-none', { body: body.slice(0, 200), buttons: L.buttons(d4).map(x => x.label) })
  if (!/No live match/.test(body)) throw new Error('no-results text missing')

  // spectate the first result
  await L.sleep(400) // server ignores clicks within 150 ms of the previous one
  m = L.mark(spec)
  L.click(spec, found[0])
  await L.waitChat(spec, /Spectating/, 8000, m.chat)
  L.log('test', 'spectating-ok')

  for (const b of [...bots, spec]) b.quit()
  await L.sleep(1000)
  L.log('test', 'PASS')
  process.exit(0)
}
main().catch(e => { L.log('test', 'FAIL', e.message); process.exit(1) })
setTimeout(() => { L.log('test', 'FAIL', 'global timeout'); process.exit(2) }, 180000)
