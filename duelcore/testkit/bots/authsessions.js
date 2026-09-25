// Puts back the AuthMe session flags the migration reset: hasSession = 1 for accounts that had a session in the old
// database and haven't logged in since the switch (lastlogin unchanged). Prints counts only.
//   node authsessions.js
const fs = require('fs')
const path = require('path')
;(async () => {
  const props = Object.fromEntries(fs.readFileSync(path.join(__dirname, '..', 'authmig.properties'), 'utf8')
    .split('\n').filter(l => l.includes('=')).map(l => [l.slice(0, l.indexOf('=')).trim(), l.slice(l.indexOf('=') + 1).trim()]))
  const my = await require('mysql2/promise').createConnection({ host: props.host, port: +props.port, user: props.user, password: props.password, database: props.database })
  const [[before]] = await my.query('SELECT COUNT(*) AS n FROM `authme` WHERE hasSession = 1')
  const [res] = await my.query('UPDATE `authme` a JOIN `authme_smp` s ON a.`username` = s.`username` ' +
    'SET a.`hasSession` = 1 WHERE s.`hasSession` = 1 AND a.`hasSession` = 0 AND (a.`lastlogin` <=> s.`lastlogin`)')
  const [[after]] = await my.query('SELECT COUNT(*) AS n FROM `authme` WHERE hasSession = 1')
  console.log(JSON.stringify({ sessions: { before: before.n, restored: res.affectedRows, after: after.n } }))
  await my.end()
})().catch(e => { console.log(JSON.stringify({ error: e.message })); process.exit(1) })
