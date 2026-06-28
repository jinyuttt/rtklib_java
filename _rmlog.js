const fs = require('fs');
let c = fs.readFileSync('src/main/java/org/rtklib/java/rtkpos/RtkCore.java', 'utf8');
let marker = 'if (LOG.isDebugEnabled()) {\r\n            StringBuilder sb = new StringBuilder("selsat';
let start = c.indexOf(marker);
if (start < 0) { console.log('Not found'); process.exit(0); }
let depth = 0;
let end = start;
for (let i = start; i < c.length; i++) {
    if (c[i] === '{') depth++;
    if (c[i] === '}') { depth--; if (depth === 0) { end = i + 1; break; } }
}
c = c.substring(0, start) + c.substring(end);
fs.writeFileSync('src/main/java/org/rtklib/java/rtkpos/RtkCore.java', c, 'utf8');
console.log('Removed selsat logging from ' + start + ' to ' + end);
