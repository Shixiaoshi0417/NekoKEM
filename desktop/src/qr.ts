import qrcode from 'qrcode-generator';

// The fingerprint QR code shared with Android: NEKOKEM-FP1: and the 64
// hexadecimal digits of the fingerprint, upper case. Every character is in
// the QR alphanumeric set, so the code is version 4 at level M. It carries
// only the fingerprint, never a key.
export const FINGERPRINT_QR_PREFIX = 'NEKOKEM-FP1:';
const DISPLAYED = /^[0-9A-F]{2}(?::[0-9A-F]{2}){31}$/;

export function isFingerprint(value: string): boolean { return DISPLAYED.test(value); }

export function fingerprintPayload(fingerprint: string): string {
  if (!isFingerprint(fingerprint)) throw new Error('Not a NekoKEM fingerprint');
  return FINGERPRINT_QR_PREFIX + fingerprint.replaceAll(':', '');
}

export interface QrPath { size: number; path: string }

// One SVG path of dark modules, without a quiet zone (the dialog draws it).
export function fingerprintQr(fingerprint: string): QrPath {
  const code = qrcode(0, 'M');
  code.addData(fingerprintPayload(fingerprint), 'Alphanumeric');
  code.make();
  const size = code.getModuleCount();
  let path = '';
  for (let row = 0; row < size; row++) {
    for (let col = 0; col < size; col++) {
      if (code.isDark(row, col)) path += `M${col} ${row}h1v1h-1z`;
    }
  }
  return { size, path };
}
