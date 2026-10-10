#!/usr/bin/env python3
"""Wraps a raw filesystem image (NTFS / exFAT / FAT32 volume, no partition table) in a BitLocker container so the
BitLocker reader can be tested without Windows. The layout follows cryptsetup's `bitlk` reader (an independent
implementation that opens real Windows volumes); tools/validate-bitlocker.sh opens the result with it.

usage: make-bitlocker.py raw.img out.img --method 0x8004 [--togo] [--password PW]
Only throwaway test passwords are used."""
import argparse, hashlib, os, struct, sys, zlib
from cryptography.hazmat.primitives.ciphers import Cipher, algorithms, modes
from cryptography.hazmat.primitives.ciphers.aead import AESCCM

PASSWORD = "VolumeX-Test-1"
RECOVERY = "123456-234567-345678-456789-567891-678912-789123-891234"   # each group divisible by 11 is required below
def recovery_key():
    parts = [11 * n for n in (1111, 2222, 3333, 4444, 5555, 6666, 7777, 8888)]
    return "-".join("%06d" % p for p in parts)

def kdf(initial: bytes, salt: bytes) -> bytes:
    last = b"\0" * 32
    for count in range(0x100000):
        last = hashlib.sha256(last + initial + salt + struct.pack("<Q", count)).digest()
    return last

def stretch_password(pw: str, salt: bytes) -> bytes:
    return kdf(hashlib.sha256(hashlib.sha256(pw.encode("utf-16-le")).digest()).digest(), salt)

def stretch_recovery(rk: str, salt: bytes) -> bytes:
    parts = [int(p) // 11 for p in rk.split("-")]
    raw = b"".join(struct.pack("<H", p) for p in parts)
    return kdf(hashlib.sha256(raw).digest(), salt)

def ccm_encrypt(key, nonce, payload):
    out = AESCCM(key, tag_length=16).encrypt(nonce, payload, None)      # ciphertext || tag
    return out[-16:] + out[:-16]                                          # BitLocker stores tag || ciphertext

def entry(etype, vtype, data, version=1):
    return struct.pack("<HHHH", 8 + len(data), etype, vtype, version) + data

def key_payload(key: bytes, method: int = 0x2000) -> bytes:
    """A decrypted key is itself an entry: size, entry type 0 (property), value type 1 (key), version, then method + key."""
    n = 12 + len(key)
    return struct.pack("<HHHHI", n, 0, 1, 1, method) + key

def sector_cipher(method, fvek):
    if method in (0x8004, 0x8005):
        def enc(sector_no, data):
            tweak = struct.pack("<Q", sector_no) + b"\0" * 8
            e = Cipher(algorithms.AES(fvek), modes.XTS(tweak)).encryptor()
            return e.update(data) + e.finalize()
    elif method in (0x8002, 0x8003):
        def enc(sector_no, data):
            iv = Cipher(algorithms.AES(fvek), modes.ECB()).encryptor().update(struct.pack("<Q", sector_no * 512) + b"\0" * 8)
            e = Cipher(algorithms.AES(fvek), modes.CBC(iv)).encryptor()
            return e.update(data) + e.finalize()
    else:
        sys.exit("unsupported method")
    return enc

def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("raw"); ap.add_argument("out")
    ap.add_argument("--method", default="0x8004"); ap.add_argument("--togo", action="store_true")
    a = ap.parse_args()
    method = int(a.method, 16)
    key_len = {0x8002: 16, 0x8003: 32, 0x8004: 32, 0x8005: 64}[method]
    plain = bytearray(open(a.raw, "rb").read())
    N = len(plain); assert N % 512 == 0
    TAIL = 1 << 20
    V = N + TAIL
    HDR = 8192
    M = [N, N + 65536, N + 131072]; H = N + 196608
    volume_guid = os.urandom(16)
    fvek = os.urandom(key_len); vmk = os.urandom(32)
    ft = 134000000000000000 + 1234567
    entries = b""
    entries += entry(7, 2, "VolumeX BitLocker test volume".encode("utf-16-le") + b"\0\0")
    entries += entry(0xf, 0xf, struct.pack("<QQ", H, HDR))
    def vmk_entry(protection, salt, stretched):
        guid = os.urandom(16)
        nonce = struct.pack("<Q", ft) + struct.pack("<I", 1)
        body = guid + struct.pack("<Q", ft) + struct.pack("<HH", 0, protection)
        body += entry(0, 3, struct.pack("<I", 0x1000) + salt)
        body += entry(0, 5, nonce + ccm_encrypt(stretched, nonce, key_payload(vmk)))
        return entry(2, 8, body)
    s1, s2 = os.urandom(16), os.urandom(16)
    entries += vmk_entry(0x2000, s1, stretch_password(PASSWORD, s1))
    entries += vmk_entry(0x0800, s2, stretch_recovery(recovery_key(), s2))
    nonce = struct.pack("<Q", ft) + struct.pack("<I", 7)
    entries += entry(3, 5, nonce + ccm_encrypt(vmk, nonce, key_payload(fvek, method)))

    meta_size = 48 + len(entries)
    real = 64 + meta_size
    real += (-real) % 16
    block = bytearray(real)
    block[0:8] = b"-FVE-FS-"
    struct.pack_into("<HHHHQII", block, 8, real // 16, 2, 4, 4, V, 0, HDR // 512)
    struct.pack_into("<QQQQ", block, 32, M[0], M[1], M[2], H)
    struct.pack_into("<IIII", block, 64, meta_size, 1, 48, meta_size)
    block[80:96] = volume_guid
    struct.pack_into("<IHHQ", block, 96, 1, method, 0, ft)
    block[112:112 + len(entries)] = entries
    block = bytes(block)

    vnonce = struct.pack("<Q", ft) + struct.pack("<I", 9)
    hash_struct = struct.pack("<HHHHHH", 44, 0, 1, 0, 0x2005, 0) + hashlib.sha256(block).digest()
    validation_data = vnonce + ccm_encrypt(vmk, vnonce, hash_struct)
    validation = struct.pack("<HHI", 88, 2, zlib.crc32(block) & 0xFFFFFFFF) + struct.pack("<HHHH", 80, 0, 5, 0) + validation_data
    assert len(validation_data) == 72 and len(validation) == 88

    enc = sector_cipher(method, fvek)
    out = bytearray(V)
    # logical sectors 0..HDR/512 live (encrypted) in the header storage area; every other sector is encrypted in place
    for s in range(HDR // 512):
        out[H + s * 512:H + (s + 1) * 512] = enc(H // 512 + s, bytes(plain[s * 512:(s + 1) * 512]))
    for s in range(HDR // 512, N // 512):
        out[s * 512:(s + 1) * 512] = enc(s, bytes(plain[s * 512:(s + 1) * 512]))
    for m in M:
        out[m:m + len(block)] = block
        out[m + real:m + real + len(validation)] = validation
    # BitLocker boot sector replaces the original first sector
    boot = bytearray(plain[:512])
    boot[0:3] = b"\xeb\x58\x90"
    clu = max(boot[0x0d], 1) * 512
    if not a.togo:                       # the "metadata LCN" field (0x38) holds the first FVE metadata block's cluster
        boot[0x38:0x40] = struct.pack("<Q", M[0] // clu)
    guid = bytes([0x3b, 0xd6, 0x67, 0x49, 0x29, 0x2e, 0xd8, 0x4a, 0x83, 0x99, 0xf6, 0xa3, 0x39, 0xe3, 0xd0, 0x01])
    if a.togo:
        boot[3:11] = b"MSWIN4.1"; boot[424:424 + 16 + 24] = guid + struct.pack("<QQQ", *M)
    else:
        boot[3:11] = b"-FVE-FS-"; boot[160:160 + 16 + 24] = guid + struct.pack("<QQQ", *M)
    out[0:512] = boot
    # wrap with an MBR holding one NTFS/exFAT-type partition at sector 2048 (type 0x07)
    disk = bytearray(2048 * 512 + len(out) + (1 << 20))
    disk[2048 * 512:2048 * 512 + len(out)] = out
    part = struct.pack("<B3sB3sII", 0, b"\x00\x00\x00", 0x07, b"\x00\x00\x00", 2048, len(out) // 512)
    disk[446:446 + 16] = part; disk[510:512] = b"\x55\xaa"
    open(a.out, "wb").write(disk)
    print("wrote", a.out, "method %#x" % method, "togo" if a.togo else "", "password", PASSWORD, "recovery", recovery_key())

main()
