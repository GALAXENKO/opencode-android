// Computes the opencode server session cookie exactly like OpenCodeAuth.kt
// usage: node cookie.js <port> <password>
const crypto = require('crypto');

const port = process.argv[2];
const password = process.argv[3];
const exp = Math.floor(Date.now() / 1000) + 2592000;
const inner = crypto.createHmac('sha256', password).update('opencode-session-v1').digest();
const sig = crypto.createHmac('sha256', inner).update(String(exp)).digest('base64url');
console.log(`opencode_session_${port}=${exp}.${sig}`);
