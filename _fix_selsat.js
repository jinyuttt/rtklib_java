const fs = require('fs');
let c = fs.readFileSync('src/main/java/org/rtklib/java/rtkpos/RtkCore.java', 'utf8');

const old_selsat = `    private static int selsat(Obsd[] obs, double[] azel, int nu, int nr, PrcOpt opt,
                              int[] sat, int[] iu, int[] ir) {
        int ns = 0;
        int i = 0, j = nu;
        while (i < nu && j < nu + nr) {
            if (obs[i].sat < obs[j].sat) {
                i++;
            } else if (obs[i].sat > obs[j].sat) {
                j++;
            } else {
                if (azel[1 + j * 2] >= opt.elmin) {
                    sat[ns] = obs[i].sat;
                    iu[ns] = i;
                    ir[ns] = j;
                    ns++;
                }
                i++;
                j++;
            }
        }
        return ns;
    }`;

const new_selsat = `    private static int selsat(Obsd[] obs, double[] azel, int nu, int nr, PrcOpt opt,
                              int[] sat, int[] iu, int[] ir) {
        int ns = 0;
        int i = 0, j = nu;
        while (i < nu && j < nu + nr) {
            if (obs[i].sat < obs[j].sat) {
                j++;
            } else if (obs[i].sat > obs[j].sat) {
                i++;
            } else {
                if (azel[1 + j * 2] >= opt.elmin) {
                    sat[ns] = obs[i].sat;
                    iu[ns] = i;
                    ir[ns] = j;
                    ns++;
                }
                i++;
                j++;
            }
        }
        return ns;
    }`;

if (c.includes(old_selsat)) {
    c = c.replace(old_selsat, new_selsat);
    fs.writeFileSync('src/main/java/org/rtklib/java/rtkpos/RtkCore.java', c, 'utf8');
    console.log('Fixed selsat: swapped i++ and j++ in sat comparison branches');
} else {
    console.log('Target not found - checking current selsat code...');
    const match = c.match(/private static int selsat[\s\S]{0,500}return ns;/);
    if (match) console.log('Current code:', match[0].substring(0, 400));
    else console.log('selsat not found at all');
}
