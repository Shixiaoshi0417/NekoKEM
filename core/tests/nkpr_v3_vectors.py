#!/usr/bin/env python3
"""Independent reference for the NKPR v3 known-answer vectors.

Prints the values that core/tests/nkpr_v3_tests.c checks. It uses argon2-cffi
and cryptography instead of OpenSSL, so a mistake shared by Core and the C test
would show up as a mismatch. CI does not run it; run it by hand after changing
the format (pip install argon2-cffi cryptography).
"""
import hashlib
import hmac
import struct

from argon2.low_level import Type, hash_secret_raw
from cryptography.hazmat.primitives.ciphers.aead import AESGCM

PASSWORD = b'NekoKEM NKPR v3 test password'
SALT = bytes(range(32))
NONCE = bytes(range(100, 112))
PLAINTEXT = b'NKPR v3 known-answer plaintext\n'


def hkdf_sha512(salt, ikm, info, length):
    prk = hmac.new(salt, ikm, hashlib.sha512).digest()
    output, block, counter = b'', b'', 1
    while len(output) < length:
        block = hmac.new(prk, block + info + bytes([counter]), hashlib.sha512).digest()
        output += block
        counter += 1
    return prk, output[:length]


master = hash_secret_raw(PASSWORD, SALT, time_cost=3, memory_cost=65536,
                         parallelism=4, hash_len=32, type=Type.ID, version=0x13)
prk, cipher_key = hkdf_sha512(SALT, master, b'NekoKEM NKPR v3 AES-256-GCM', 32)
_, commitment = hkdf_sha512(SALT, master, b'NekoKEM NKPR v3 key commitment', 32)
header = (b'NKPR' + bytes([3, 1, 1, 0]) +
          struct.pack('>IIII', 65536, 3, 4, 0x13) +
          struct.pack('>HBB', 32, 12, 16) +
          struct.pack('>Q', len(PLAINTEXT)))
sealed = AESGCM(cipher_key).encrypt(NONCE, PLAINTEXT, header + SALT + commitment + NONCE)
container = header + SALT + commitment + NONCE + sealed

for name, value in (('master', master), ('prk', prk), ('cipher key', cipher_key),
                    ('commitment', commitment), ('container', container)):
    print(f'{name}: {value.hex()}')
