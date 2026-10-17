'use strict';
const fs = require('node:fs');
const { FcmSender } = require('./fcm');
const { ApnsSender } = require('./apns');

function readJson(file) {
  try { return JSON.parse(fs.readFileSync(file, 'utf8')); } catch { return null; }
}
function readText(file) {
  try { return fs.readFileSync(file, 'utf8'); } catch { return ''; }
}

/** Bangun pengirim dari konfigurasi. Mengembalikan {} bila kredensial belum diisi (push nonaktif). */
function buildSenders(config) {
  const senders = {};
  const f = { ...config.fcm };
  if (f.serviceAccountFile) {
    const sa = readJson(f.serviceAccountFile);
    if (sa) { f.projectId = f.projectId || sa.project_id; f.clientEmail = f.clientEmail || sa.client_email; f.privateKey = f.privateKey || sa.private_key; }
  }
  const fcm = FcmSender.fromConfig(f);
  if (fcm) senders.android = fcm;

  const a = { ...config.apns };
  if (a.keyFile && !a.privateKey) a.privateKey = readText(a.keyFile);
  const apns = ApnsSender.fromConfig(a);
  if (apns) senders.ios = apns;
  return senders;
}

module.exports = { buildSenders };
