'use strict';

/**
 * Pembatas laju jendela-geser per kunci (IP, ID perangkat, dll).
 * hit() mengembalikan {allowed, retryAfterMs}. Memori dibersihkan berkala.
 */
function createLimiter({ windowMs, max, now = () => Date.now() }) {
  const hits = new Map();
  let lastPrune = now();

  function prune(t) {
    if (t - lastPrune < windowMs) return;
    lastPrune = t;
    for (const [key, list] of hits) {
      const fresh = list.filter((x) => t - x < windowMs);
      if (fresh.length) hits.set(key, fresh); else hits.delete(key);
    }
  }

  return {
    hit(key) {
      const t = now();
      prune(t);
      const list = (hits.get(key) || []).filter((x) => t - x < windowMs);
      if (list.length >= max) {
        hits.set(key, list);
        return { allowed: false, retryAfterMs: Math.max(1, windowMs - (t - list[0])) };
      }
      list.push(t);
      hits.set(key, list);
      return { allowed: true, retryAfterMs: 0 };
    },
    size: () => hits.size,
  };
}

module.exports = { createLimiter };
