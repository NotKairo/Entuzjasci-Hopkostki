// Weryfikacja podpisu Ed25519, którym Discord podpisuje każde żądanie interakcji.

const encoder = new TextEncoder();
const keyCache = new Map();

function hexToBytes(hex) {
  if (!/^[0-9a-f]*$/i.test(hex) || hex.length % 2) throw new Error('Niepoprawny hex');
  const bytes = new Uint8Array(hex.length / 2);
  for (let i = 0; i < bytes.length; i += 1) bytes[i] = Number.parseInt(hex.slice(i * 2, i * 2 + 2), 16);
  return bytes;
}

async function importKey(publicKeyHex) {
  if (!keyCache.has(publicKeyHex)) {
    keyCache.set(publicKeyHex, crypto.subtle.importKey('raw', hexToBytes(publicKeyHex), { name: 'Ed25519' }, false, ['verify']));
  }
  return keyCache.get(publicKeyHex);
}

export async function verifyDiscordRequest(publicKeyHex, signature, timestamp, rawBody) {
  if (!publicKeyHex || !signature || !timestamp) return false;
  try {
    const key = await importKey(publicKeyHex);
    return await crypto.subtle.verify({ name: 'Ed25519' }, key, hexToBytes(signature), encoder.encode(timestamp + rawBody));
  } catch {
    return false;
  }
}

export async function ed25519Supported() {
  try {
    await crypto.subtle.generateKey({ name: 'Ed25519' }, false, ['sign', 'verify']);
    return true;
  } catch {
    return false;
  }
}

// Porównanie haseł/sekretów w stałym czasie.
export async function safeEqual(a, b) {
  const [ha, hb] = await Promise.all([
    crypto.subtle.digest('SHA-256', encoder.encode(String(a ?? ''))),
    crypto.subtle.digest('SHA-256', encoder.encode(String(b ?? ''))),
  ]);
  const x = new Uint8Array(ha);
  const y = new Uint8Array(hb);
  let diff = 0;
  for (let i = 0; i < x.length; i += 1) diff |= x[i] ^ y[i];
  return diff === 0;
}

export async function sha256Hex(text) {
  const digest = await crypto.subtle.digest('SHA-256', encoder.encode(text));
  return [...new Uint8Array(digest)].map((b) => b.toString(16).padStart(2, '0')).join('');
}
