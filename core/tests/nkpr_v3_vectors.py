#!/usr/bin/env python3
"""Independent reference for the NKPR v3 known-answer vectors.

Prints the values that core/tests/nkpr_v3_tests.c checks, and the crafted
NKPR v2 container whose AES-GCM tag verifies under two passwords. It uses argon2-cffi
and cryptography instead of OpenSSL, so a mistake shared by Core and the C test
would show up as a mismatch. CI does not run it; run it by hand after changing
the format (pip install argon2-cffi cryptography).
"""
import hashlib
import hmac
import struct

from argon2.low_level import Type, hash_secret_raw
from cryptography.hazmat.primitives.ciphers import Cipher, algorithms, modes
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


# A crafted NKPR v2 container that opens under two passwords: the partitioning
# oracle that NKPR v3 closes. One ciphertext block is solved over GF(2^128) so
# that the GCM tag is the same under both Argon2id keys.
GCM_R = 0xE1 << 120


def gf_mul(x, y):
    z, v = 0, x
    for bit in range(127, -1, -1):
        if (y >> bit) & 1:
            z ^= v
        v = (v >> 1) ^ GCM_R if v & 1 else v >> 1
    return z


def gf_pow(x, n):
    result = 1 << 127
    while n:
        if n & 1:
            result = gf_mul(result, x)
        x, n = gf_mul(x, x), n >> 1
    return result


def aes_block(key, block):
    encryptor = Cipher(algorithms.AES(key), modes.ECB()).encryptor()
    return int.from_bytes(encryptor.update(block) + encryptor.finalize(), 'big')


TWO_PASSWORDS = (b'first candidate password', b'second candidate password')
TWO_SALT = bytes(range(32, 64))
TWO_NONCE = bytes(range(200, 212))
two_header = (b'NKPR' + bytes([2, 1, 1, 0]) +
              struct.pack('>IIII', 65536, 3, 4, 0x13) +
              struct.pack('>HBB', 32, 12, 16) + struct.pack('>Q', 16))
two_aad = two_header + TWO_SALT + TWO_NONCE
# GHASH input: five AAD blocks, the one ciphertext block, the length block.
ghash_blocks = [int.from_bytes(two_aad[i:i + 16], 'big') for i in range(0, 80, 16)]
ghash_blocks += [None, (len(two_aad) * 8 << 64) | 128]
terms = []
for candidate in TWO_PASSWORDS:
    key = hash_secret_raw(candidate, TWO_SALT, time_cost=3, memory_cost=65536,
                          parallelism=4, hash_len=32, type=Type.ID, version=0x13)
    h = aes_block(key, bytes(16))
    fixed, coefficient = aes_block(key, TWO_NONCE + b'\0\0\0\1'), None
    for index, block in enumerate(ghash_blocks):
        power = gf_pow(h, len(ghash_blocks) - index)
        if block is None:
            coefficient = power
        else:
            fixed ^= gf_mul(block, power)
    terms.append((key, fixed, coefficient))
(key_a, fixed_a, coef_a), (key_b, fixed_b, coef_b) = terms
block = gf_mul(fixed_a ^ fixed_b, gf_pow(coef_a ^ coef_b, (1 << 128) - 2))
tag = fixed_a ^ gf_mul(block, coef_a)
sealed = block.to_bytes(16, 'big') + tag.to_bytes(16, 'big')
for key in (key_a, key_b):
    AESGCM(key).decrypt(TWO_NONCE, sealed, two_aad)
print(f'two-password v2 container: {(two_aad + sealed).hex()}')
