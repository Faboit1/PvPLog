// Quit locations in the shared AuthMe table that came from the PvP server (arena world, or PvP's hub) instead of
// the SMP. "report" prints counts; "restore" puts back the location from the pre-migration copy (authme_smp) for
// those rows. Prints counts only.
//   node authlocations.js report|restore
const fs = require('fs')
const path = require('path')
const mode = process.argv[2] || 'report'
;(async () => {
  const props = Object.fromEntries(fs.readFileSync(path.join(__dirname, '..', 'authmig.properties'), 'utf8')
    .split('\n').filter(l => l.includes('=')).map(l => [l.slice(0, l.indexOf('=')).trim(), l.slice(l.indexOf('=') + 1).trim()]))
  const my = await require('mysql2/promise').createConnection({ host: props.host, port: +props.port, user: props.user, password: props.password, database: props.database })
  const [snapWorlds] = await my.query('SELECT world, COUNT(*) AS n FROM `authme_smp` GROUP BY world ORDER BY n DESC LIMIT 12')
  const [nowWorlds] = await my.query('SELECT world, COUNT(*) AS n FROM `authme` GROUP BY world ORDER BY n DESC LIMIT 12')
  // changed since the migration: the location differs from the pre-migration copy
  const changed = 'FROM `authme` a JOIN `authme_smp` s ON a.`username` = s.`username` WHERE ' +
    '(a.`world` <> s.`world` OR a.`x` <> s.`x` OR a.`y` <> s.`y` OR a.`z` <> s.`z`)'
  const pvp = " AND (a.`world` = 'duelcore_arenas' OR (a.`world` = 'world' AND a.`x` = 0.5 AND a.`z` = 0.5 AND a.`y` = 100))"
  const [[c1]] = await my.query('SELECT COUNT(*) AS n ' + changed)
  const [[c2]] = await my.query('SELECT COUNT(*) AS n ' + changed + pvp)
  const near = " AND a.`world` = 'world' AND ABS(a.`x`) < 64 AND ABS(a.`z`) < 64 AND a.`y` BETWEEN 90 AND 115"
  const [[c3]] = await my.query('SELECT COUNT(*) AS n ' + changed + near)
  const [[c4]] = await my.query("SELECT COUNT(*) AS n FROM `authme_smp` WHERE world = 'world' AND ABS(x) < 64 AND ABS(z) < 64 AND y BETWEEN 90 AND 115")
  console.log(JSON.stringify({ nearOriginChanged: c3.n, nearOriginInSnapshot: c4.n }))
  console.log(JSON.stringify({ snapshotWorlds: snapWorlds, currentWorlds: nowWorlds, changedSinceMigration: c1.n, pvpLooking: c2.n }))
  if (mode === 'restore') {
    const [res] = await my.query('UPDATE `authme` a JOIN `authme_smp` s ON a.`username` = s.`username` SET a.`world` = s.`world`, ' +
      'a.`x` = s.`x`, a.`y` = s.`y`, a.`z` = s.`z`, a.`yaw` = s.`yaw`, a.`pitch` = s.`pitch` WHERE ' +
      '(a.`world` <> s.`world` OR a.`x` <> s.`x` OR a.`y` <> s.`y` OR a.`z` <> s.`z`)' + pvp)
    console.log(JSON.stringify({ restored: res.affectedRows }))
  }
  await my.end()
})().catch(e => { console.log(JSON.stringify({ error: e.message })); process.exit(1) })
