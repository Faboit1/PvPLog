// Logs in N bots once (creates their profiles), then quits. usage: node join.js <n> <prefix>
const L = require('./lib')
const n = parseInt(process.argv[2] || '12')
const prefix = process.argv[3] || 'dcbot_l'
let done = 0
for (let i = 0; i < n; i++) {
  setTimeout(() => {
    const b = L.createBot(prefix + i)
    b.once('spawn', () => setTimeout(() => { b.quit(); if (++done === n) setTimeout(() => process.exit(0), 500) }, 2000))
  }, i * 200)
}
setTimeout(() => process.exit(1), 60000)
