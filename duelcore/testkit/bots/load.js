// Parallel load: N bots spread over several kits keep playing ranked matches and re-queue through the
// results dialog's "Play again" button until the total number of finished matches reaches the target.
// usage: node load.js <bots> <kits comma separated> <targetMatches> [prefix]
const L = require('./lib')
const N = parseInt(process.argv[2] || '12')
const kits = (process.argv[3] || 'sword,shield,spear').split(',')
const target = parseInt(process.argv[4] || '50')
const prefix = process.argv[5] || 'dcbot_l'
let finished = 0
const perBot = {}
const startedAt = Date.now()

async function runBot (i) {
  const name = prefix + i
  const kit = kits[i % kits.length]
  perBot[name] = 0
  const bot = L.createBot(name)
  await new Promise(r => bot.once('spawn', r))
  await L.sleep(2000 + i * 150)
  let viaDialog = i % 2 === 0
  while (finished < target) {
    const m = L.mark(bot)
    if (perBot[name] === 0) {
      if (viaDialog) {
        await L.queueViaMenu(bot, kit)
      } else {
        bot.chat('/queue ' + kit + ' ranked')
      }
    }
    try {
      await L.waitTitle(bot, /Match found/i, 240000, m.titles)
    } catch (e) {
      L.log(name, 'no-match', e.message)
      if (finished >= target) break
      bot.chat('/queue ' + kit + ' ranked')
      continue
    }
    await L.waitTitle(bot, /Fight/i, 60000, m.titles)
    const f = L.fighter(bot, { slot: 0, cooldownMs: 600 + (i % 3) * 60 })
    // mineflayer can get stuck on terrain steps; a pair that makes no round progress for 60 s forfeits so the
    // load keeps cycling matches (create, paste/reuse, reset, persist, release)
    let lastTitles = bot.dc.titles.length
    let lastChange = Date.now()
    const watchdog = setInterval(() => {
      if (bot.dc.titles.length !== lastTitles) {
        lastTitles = bot.dc.titles.length
        lastChange = Date.now()
      } else if (Date.now() - lastChange > 60000) {
        lastChange = Date.now()
        L.log(name, 'forfeit-stuck', {})
        bot.chat('/leave')
        setTimeout(() => bot.chat('/leave'), 800)
      }
    }, 2000)
    let outcome
    try {
      // a player who forfeits gets a chat line instead of the results title
      outcome = await Promise.race([
        L.waitTitle(bot, /Victory|Defeat|Draw/, 600000, m.titles),
        L.waitChat(bot, /You forfeited the match/, 600000, m.chat).then(() => 'Defeat (forfeit)')
      ])
    } finally {
      f.stop()
      clearInterval(watchdog)
    }
    if (/forfeit/.test(outcome)) {
      perBot[name]++
      L.log(name, 'match-done', { kit, outcome, n: perBot[name] })
      if (finished >= target) break
      await L.sleep(1500)
      bot.chat('/queue ' + kit + ' ranked')
      continue
    }
    perBot[name]++
    if (/Victory/.test(outcome)) finished++ // exactly one Victory per decided match
    else if (/Draw/.test(outcome)) finished += 0.5
    const rd = await L.waitDialog(bot, /Victory|Defeat|Draw/, 30000, m.dialogs)
    L.log(name, 'match-done', { kit, outcome, n: perBot[name] })
    if (finished >= target) break
    await L.sleep(300 + Math.random() * 1500)
    const again = L.buttons(rd).find(x => x.id === 'duelcore:queue/join')
    if (!again) throw new Error('no play-again button')
    const m2 = L.mark(bot)
    L.click(bot, again)
    await L.waitChat(bot, /Searching/, 5000, m2.chat).catch(() => bot.chat('/queue ' + kit + ' ranked'))
  }
  await L.sleep(2000)
  bot.quit()
}

async function main () {
  const runs = []
  for (let i = 0; i < N; i++) {
    runs.push(runBot(i).catch(e => L.log(prefix + i, 'bot-error', e.message)))
    await L.sleep(250)
  }
  const ticker = setInterval(() => L.log('load', 'progress', { finished, perBot, minutes: ((Date.now() - startedAt) / 60000).toFixed(1) }), 30000)
  await Promise.all(runs)
  clearInterval(ticker)
  L.log('load', 'DONE', { finished, perBot, minutes: ((Date.now() - startedAt) / 60000).toFixed(1) })
  process.exit(0)
}
main()
setTimeout(() => { L.log('load', 'TIMEOUT', { finished, perBot }); process.exit(2) }, 45 * 60000)
