// Login guard check (offline test mode). Operator prepares: /tester add guardok1 ; allow.txt has "guardlock1 1.2.3.4".
// Expect: randomguy (unknown) refused, dcbot_guard allowed, guardok1 allowed (and locked to 127.0.0.1),
// guardlock1 (locked to another IP) refused.
const L = require('./lib')
function attempt (name) {
  return new Promise(resolve => {
    const bot = L.createBot(name)
    let done = false
    const finish = r => { if (!done) { done = true; try { bot.quit() } catch (e) {} ; resolve(r) } }
    bot.once('spawn', () => finish({ name, joined: true }))
    bot.once('kicked', reason => finish({ name, joined: false, reason: L.plain(reason) }))
    bot.once('end', r => finish({ name, joined: false, reason: String(r) }))
    setTimeout(() => finish({ name, joined: false, reason: 'timeout' }), 15000)
  })
}
async function main () {
  const expect = { randomguy: false, dcbot_guard: true, guardok1: true, guardlock1: false }
  let ok = true
  for (const [name, want] of Object.entries(expect)) {
    const r = await attempt(name)
    L.log('test', 'attempt', r)
    if (r.joined !== want) { ok = false; L.log('test', 'WRONG', { name, want }) }
    await L.sleep(1500)
  }
  L.log('test', ok ? 'PASS' : 'FAIL')
  process.exit(ok ? 0 : 1)
}
main()
