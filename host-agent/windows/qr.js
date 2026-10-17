/* KzDesk - encoder QR Code minimal (mode byte, versi 1-10, koreksi galat L/M).
 * Tanpa dependensi; jalan di browser (dipakai halaman pairing host) dan Node (tes).
 * Diverifikasi dengan decoder QR (OpenCV) lewat scripts/verify_qr.py. */
(function (root, factory) {
  if (typeof module === 'object' && module.exports) module.exports = factory();
  else root.KzQR = factory();
})(typeof self !== 'undefined' ? self : this, function () {
  'use strict';

  var ECC_PER_BLOCK = { L: [-1, 7, 10, 15, 20, 26, 18, 20, 24, 30, 18], M: [-1, 10, 16, 26, 18, 24, 16, 18, 22, 22, 26] };
  var NUM_BLOCKS = { L: [-1, 1, 1, 1, 1, 1, 2, 2, 2, 2, 4], M: [-1, 1, 1, 1, 2, 2, 4, 4, 4, 5, 5] };
  var FORMAT_BITS = { L: 1, M: 0 };

  // ---- GF(256) / Reed-Solomon ------------------------------------------------
  var EXP = new Array(512), LOG = new Array(256);
  (function () {
    var x = 1;
    for (var i = 0; i < 255; i++) { EXP[i] = x; LOG[x] = i; x <<= 1; if (x & 0x100) x ^= 0x11D; }
    for (var j = 255; j < 512; j++) EXP[j] = EXP[j - 255];
  })();
  function gfMul(a, b) { return a === 0 || b === 0 ? 0 : EXP[LOG[a] + LOG[b]]; }

  function rsGenerator(degree) {
    var poly = [1];
    for (var i = 0; i < degree; i++) {
      var next = new Array(poly.length + 1).fill(0);
      for (var j = 0; j < poly.length; j++) {
        next[j] ^= poly[j];
        next[j + 1] ^= gfMul(poly[j], EXP[i]);
      }
      poly = next;
    }
    return poly; // poly[0] = 1 (suku tertinggi)
  }

  function rsRemainder(data, degree) {
    var gen = rsGenerator(degree);
    var rem = new Array(degree).fill(0);
    for (var i = 0; i < data.length; i++) {
      var factor = data[i] ^ rem[0];
      rem.shift(); rem.push(0);
      for (var j = 0; j < degree; j++) rem[j] ^= gfMul(gen[j + 1], factor);
    }
    return rem;
  }

  // ---- kapasitas -------------------------------------------------------------
  function rawModules(ver) {
    var r = (16 * ver + 128) * ver + 64;
    if (ver >= 2) { var n = Math.floor(ver / 7) + 2; r -= (25 * n - 10) * n - 55; if (ver >= 7) r -= 36; }
    return r;
  }
  function dataCodewords(ver, ecl) { return Math.floor(rawModules(ver) / 8) - ECC_PER_BLOCK[ecl][ver] * NUM_BLOCKS[ecl][ver]; }

  // ---- bit buffer ------------------------------------------------------------
  function appendBits(buf, val, len) { for (var i = len - 1; i >= 0; i--) buf.push((val >>> i) & 1); }

  function utf8(str) {
    var out = [];
    var s = unescape(encodeURIComponent(str));
    for (var i = 0; i < s.length; i++) out.push(s.charCodeAt(i));
    return out;
  }

  function makeCodewords(bytes, ver, ecl) {
    var bits = [];
    appendBits(bits, 4, 4);
    appendBits(bits, bytes.length, ver < 10 ? 8 : 16);
    bytes.forEach(function (b) { appendBits(bits, b, 8); });
    var cap = dataCodewords(ver, ecl) * 8;
    appendBits(bits, 0, Math.min(4, cap - bits.length));
    while (bits.length % 8) bits.push(0);
    for (var pad = 0xEC; bits.length < cap; pad ^= 0xEC ^ 0x11) appendBits(bits, pad, 8);
    var data = [];
    for (var i = 0; i < bits.length; i += 8) {
      var v = 0; for (var k = 0; k < 8; k++) v = (v << 1) | bits[i + k]; data.push(v);
    }
    // blok + ECC + interleave
    var nb = NUM_BLOCKS[ecl][ver], ecc = ECC_PER_BLOCK[ecl][ver];
    var total = Math.floor(rawModules(ver) / 8);
    var shortBlocks = nb - (total % nb), shortLen = Math.floor(total / nb) - ecc;
    var blocks = [], eccs = [], pos = 0;
    for (var b = 0; b < nb; b++) {
      var len = shortLen + (b < shortBlocks ? 0 : 1);
      var d = data.slice(pos, pos + len); pos += len;
      blocks.push(d); eccs.push(rsRemainder(d, ecc));
    }
    var result = [];
    for (var c = 0; c < shortLen + 1; c++) blocks.forEach(function (blk, bi) { if (c < blk.length) result.push(blk[c]); });
    for (var e = 0; e < ecc; e++) eccs.forEach(function (er) { result.push(er[e]); });
    return result;
  }

  // ---- matriks ---------------------------------------------------------------
  function newGrid(size) {
    var g = []; for (var i = 0; i < size; i++) g.push(new Array(size).fill(false)); return g;
  }

  function alignPositions(ver) {
    if (ver === 1) return [];
    var n = Math.floor(ver / 7) + 2, size = ver * 4 + 17;
    var step = Math.floor((ver * 8 + n * 3 + 5) / (n * 4 - 4)) * 2;
    var res = [6];
    for (var p = size - 7; res.length < n; p -= step) res.splice(1, 0, p);
    return res;
  }

  function buildFunctionPatterns(ver, modules, isFn) {
    var size = ver * 4 + 17;
    function set(x, y, dark) { if (x >= 0 && x < size && y >= 0 && y < size) { modules[y][x] = dark; isFn[y][x] = true; } }
    for (var i = 0; i < size; i++) { set(6, i, i % 2 === 0); set(i, 6, i % 2 === 0); }
    function finder(cx, cy) {
      for (var dy = -4; dy <= 4; dy++) for (var dx = -4; dx <= 4; dx++) {
        var dist = Math.max(Math.abs(dx), Math.abs(dy));
        set(cx + dx, cy + dy, dist !== 2 && dist !== 4);
      }
    }
    finder(3, 3); finder(size - 4, 3); finder(3, size - 4);
    var ap = alignPositions(ver);
    for (var a = 0; a < ap.length; a++) for (var b = 0; b < ap.length; b++) {
      if ((a === 0 && b === 0) || (a === 0 && b === ap.length - 1) || (a === ap.length - 1 && b === 0)) continue;
      for (var dy = -2; dy <= 2; dy++) for (var dx = -2; dx <= 2; dx++) set(ap[a] + dx, ap[b] + dy, Math.max(Math.abs(dx), Math.abs(dy)) !== 1);
    }
    return size;
  }

  function drawFormat(modules, isFn, size, ecl, mask) {
    var data = (FORMAT_BITS[ecl] << 3) | mask, rem = data;
    for (var i = 0; i < 10; i++) rem = (rem << 1) ^ ((rem >>> 9) * 0x537);
    var bits = ((data << 10) | rem) ^ 0x5412;
    function bit(i) { return ((bits >>> i) & 1) !== 0; }
    function set(x, y, d) { modules[y][x] = d; isFn[y][x] = true; }
    for (var j = 0; j <= 5; j++) set(8, j, bit(j));
    set(8, 7, bit(6)); set(8, 8, bit(7)); set(7, 8, bit(8));
    for (var k = 9; k < 15; k++) set(14 - k, 8, bit(k));
    for (var m = 0; m < 8; m++) set(size - 1 - m, 8, bit(m));
    for (var n = 8; n < 15; n++) set(8, size - 15 + n, bit(n));
    set(8, size - 8, true);
  }

  function drawVersion(modules, isFn, size, ver) {
    if (ver < 7) return;
    var rem = ver;
    for (var i = 0; i < 12; i++) rem = (rem << 1) ^ ((rem >>> 11) * 0x1F25);
    var bits = (ver << 12) | rem;
    for (var j = 0; j < 18; j++) {
      var d = ((bits >>> j) & 1) !== 0, a = size - 11 + (j % 3), b = Math.floor(j / 3);
      modules[b][a] = d; isFn[b][a] = true; modules[a][b] = d; isFn[a][b] = true;
    }
  }

  function placeData(modules, isFn, size, codewords) {
    var i = 0;
    for (var right = size - 1; right >= 1; right -= 2) {
      if (right === 6) right = 5;
      for (var vert = 0; vert < size; vert++) for (var j = 0; j < 2; j++) {
        var x = right - j, upward = ((right + 1) & 2) === 0, y = upward ? size - 1 - vert : vert;
        if (!isFn[y][x] && i < codewords.length * 8) { modules[y][x] = ((codewords[i >>> 3] >>> (7 - (i & 7))) & 1) !== 0; i++; }
      }
    }
  }

  function applyMask(modules, isFn, size, mask) {
    for (var y = 0; y < size; y++) for (var x = 0; x < size; x++) {
      var inv;
      switch (mask) {
        case 0: inv = (x + y) % 2 === 0; break;
        case 1: inv = y % 2 === 0; break;
        case 2: inv = x % 3 === 0; break;
        case 3: inv = (x + y) % 3 === 0; break;
        case 4: inv = (Math.floor(x / 3) + Math.floor(y / 2)) % 2 === 0; break;
        case 5: inv = (x * y) % 2 + (x * y) % 3 === 0; break;
        case 6: inv = ((x * y) % 2 + (x * y) % 3) % 2 === 0; break;
        default: inv = ((x + y) % 2 + (x * y) % 3) % 2 === 0;
      }
      if (!isFn[y][x] && inv) modules[y][x] = !modules[y][x];
    }
  }

  function penalty(m, size) {
    var score = 0, x, y, run, dark = 0;
    function lineScore(get) {
      var s = 0, r = 1, hist = [];
      for (var i = 1; i < size; i++) {
        if (get(i) === get(i - 1)) { r++; if (r === 5) s += 3; else if (r > 5) s++; } else { r = 1; }
      }
      // pola 1:1:3:1:1 (mirip finder) dengan 4 modul terang di salah satu sisi
      var line = []; for (var k = 0; k < size; k++) line.push(get(k) ? 1 : 0);
      var str = '0000' + line.join('') + '0000';
      var re = /1011101/g, mt;
      while ((mt = re.exec(str)) !== null) {
        var before = str.substr(Math.max(0, mt.index - 4), 4), after = str.substr(mt.index + 7, 4);
        if (before === '0000' || after === '0000') s += 40;
        re.lastIndex = mt.index + 1;
      }
      return s;
    }
    for (y = 0; y < size; y++) score += lineScore(function (i) { return m[y][i]; });
    for (x = 0; x < size; x++) score += lineScore(function (i) { return m[i][x]; });
    for (y = 0; y < size - 1; y++) for (x = 0; x < size - 1; x++) {
      var c = m[y][x]; if (c === m[y][x + 1] && c === m[y + 1][x] && c === m[y + 1][x + 1]) score += 3;
    }
    for (y = 0; y < size; y++) for (x = 0; x < size; x++) if (m[y][x]) dark++;
    var k2 = Math.ceil(Math.abs(dark * 20 - size * size * 10) / (size * size)) - 1;
    return score + Math.max(0, k2) * 10;
  }

  /** @returns {{size:number, version:number, modules:boolean[][]}} */
  function encode(text, ecl) {
    ecl = ecl === 'L' ? 'L' : 'M';
    var bytes = utf8(String(text));
    var ver = 1;
    for (; ver <= 10; ver++) {
      var need = 4 + (ver < 10 ? 8 : 16) + bytes.length * 8;
      if (need <= dataCodewords(ver, ecl) * 8) break;
    }
    if (ver > 10) throw new Error('Teks terlalu panjang untuk QR (maks ~213 byte).');
    var codewords = makeCodewords(bytes, ver, ecl);
    var best = null, bestScore = Infinity;
    for (var mask = 0; mask < 8; mask++) {
      var size = ver * 4 + 17, modules = newGrid(size), isFn = newGrid(size);
      buildFunctionPatterns(ver, modules, isFn);
      drawFormat(modules, isFn, size, ecl, 0); // tandai area format sebagai fungsi sebelum menaruh data
      drawVersion(modules, isFn, size, ver);
      placeData(modules, isFn, size, codewords);
      applyMask(modules, isFn, size, mask);
      drawFormat(modules, isFn, size, ecl, mask);
      var sc = penalty(modules, size);
      if (sc < bestScore) { bestScore = sc; best = { size: size, version: ver, modules: modules }; }
    }
    return best;
  }

  /** SVG hitam-putih dengan zona tenang [border] modul. */
  function toSvg(qr, border, scale) {
    border = border === undefined ? 4 : border; scale = scale || 8;
    var dim = qr.size + border * 2, path = [];
    for (var y = 0; y < qr.size; y++) for (var x = 0; x < qr.size; x++)
      if (qr.modules[y][x]) path.push('M' + (x + border) + ',' + (y + border) + 'h1v1h-1z');
    return '<svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 ' + dim + ' ' + dim + '" width="' + dim * scale + '" height="' + dim * scale +
      '" shape-rendering="crispEdges"><rect width="100%" height="100%" fill="#fff"/><path d="' + path.join('') + '" fill="#000"/></svg>';
  }

  return { encode: encode, toSvg: toSvg };
});
