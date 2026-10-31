// Precompress the build so the Go server can serve .br/.gz directly.
import { readdirSync, readFileSync, statSync, writeFileSync, existsSync } from "node:fs";
import { join } from "node:path";
import { brotliCompressSync, gzipSync, constants } from "node:zlib";

const dir = new URL("../../../server/internal/web/dist", import.meta.url).pathname;
let total = 0;
const walk = (d) => {
  for (const f of readdirSync(d)) {
    const p = join(d, f);
    if (statSync(p).isDirectory()) walk(p);
    else if (/\.(js|css|html|svg|json)$/.test(f)) {
      const buf = readFileSync(p);
      const br = brotliCompressSync(buf, { params: { [constants.BROTLI_PARAM_QUALITY]: 11 } });
      writeFileSync(p + ".br", br);
      writeFileSync(p + ".gz", gzipSync(buf, { level: 9 }));
      if (/\.js$/.test(f)) total += br.length;
    }
  }
};
walk(dir);
if (!existsSync(join(dir, ".keep"))) writeFileSync(join(dir, ".keep"), "");
console.log(`JS total (brotli): ${(total / 1024).toFixed(1)} KB`);
