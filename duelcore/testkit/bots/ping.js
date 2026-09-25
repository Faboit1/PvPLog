// Server list ping against the local server; prints the MOTD (plain + raw) and the hover sample.
const mc = require('minecraft-protocol')
const port = parseInt(process.env.DC_PORT || process.argv[2] || process.env.MC_PORT || '25569', 10)
mc.ping({ host: '127.0.0.1', port, version: '1.21.11' }, (err, res) => {
  if (err) { console.log(JSON.stringify({ who: 'test', event: 'FAIL', data: err.message })); process.exit(1) }
  console.log(JSON.stringify({ who: 'test', event: 'motd', data: res.description }))
  console.log(JSON.stringify({ who: 'test', event: 'players', data: res.players }))
  console.log(JSON.stringify({ who: 'test', event: 'PASS' }))
  process.exit(0)
})
