const fs = require('fs');

function parsePosFile(filePath) {
    const lines = fs.readFileSync(filePath, 'utf8').split('\n')
        .filter(l => !l.startsWith('%') && !l.startsWith('#') && l.trim());
    const results = [];
    for (const line of lines) {
        const parts = line.trim().split(/\s+/);
        if (parts.length < 7) continue;
        const timeMatch = parts[1] && parts[1].match(/(\d+):(\d+):(\d+)/);
        if (!timeMatch) continue;
        const sow = parseInt(timeMatch[1]) * 3600 + parseInt(timeMatch[2]) * 60 + parseFloat(timeMatch[3]);
        results.push({ sow, ns: parseInt(parts[6]), q: parseInt(parts[5]) });
    }
    return results;
}

const javaData = parsePosFile('C:/Users/admin/Desktop/rtklib_java_results/3_rtk_result.pos');
const cData = parsePosFile('C:/Users/admin/Desktop/rtklib_java_results/rtk_c_bds_aroff.pos');

console.log('=== Satellite count comparison ===');
console.log('Epoch  SOW     ns_Java  ns_C  diff');
const cMap = new Map();
cData.forEach(d => cMap.set(d.sow, d));

let idx = 0;
const diffs = [];
for (const j of javaData) {
    const c = cMap.get(j.sow);
    if (!c) continue;
    idx++;
    const diff = j.ns - c.ns;
    diffs.push(diff);
    if (idx <= 10 || idx > javaData.length - 5 || diff !== 0) {
        console.log(`${String(idx).padStart(4)}  ${j.sow.toFixed(0).padStart(6)}  ${String(j.ns).padStart(5)}  ${String(c.ns).padStart(4)}  ${diff >= 0 ? '+' : ''}${diff}`);
    }
}

const meanDiff = diffs.reduce((a,b) => a+b, 0) / diffs.length;
const nsDiff0 = diffs.filter(d => d === 0).length;
const nsDiffNeg = diffs.filter(d => d < 0).length;
const nsDiffPos = diffs.filter(d => d > 0).length;
console.log(`\nMean ns diff: ${meanDiff.toFixed(2)}`);
console.log(`ns_Java == ns_C: ${nsDiff0} epochs`);
console.log(`ns_Java < ns_C: ${nsDiffNeg} epochs`);
console.log(`ns_Java > ns_C: ${nsDiffPos} epochs`);
