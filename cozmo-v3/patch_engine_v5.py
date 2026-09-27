#!/usr/bin/env python3
from pathlib import Path
import struct
import sys

# Cozmo 3.6.6 ARM64 - V5 diagnostic guard
#
# Latest V4 crash occurs earlier than the V4 neighbour guard:
#   0x12d3e04  ldur x2,[x1,#-8]    ; current block header/size
#   ...
#   0x12d3e18  str  x4,[x3,x2]     ; crashes because x2 is absurd
#
# Observed corrupt x2:
#   0x95b6db579716a060
#
# Any valid TLSF block in this app is far below 4 GiB. V5 therefore
# guards the *current* block header before any pointer arithmetic.
#
# If upper 32 bits are non-zero: return immediately from the TLSF free
# routine. This intentionally leaks that one corrupt block, but avoids
# dereferencing a bogus size. It is a diagnostic compatibility guard,
# not the claimed root-cause fix.
#
# We use two verified executable padding areas in this exact binary:
#   0x1339908..0x133990f : 8-byte gap between .text and .plt
#   0x1339924..0x133992f : 3 unreachable NOPs after PLT0's BR
#
# Entry hook:
#   0x12d3e04 -> b 0x1339908
#
# Stage 1 @ 0x1339908:
#   ldur x2,[x1,#-8]
#   b    0x1339924
#
# Stage 2 @ 0x1339924:
#   lsr  x6,x2,#32
#   cbnz x6,0x13398e4   ; existing RET, preserves LR from caller
#   b    0x12d3e08      ; normal original path

ENTRY_VA  = 0x12D3E04
STAGE1_VA = 0x1339908
STAGE2_VA = 0x1339924
RET_VA    = 0x13398E4
GOOD_VA   = 0x12D3E08

EXPECTED_ENTRY = bytes.fromhex("22805ff8")  # ldur x2,[x1,#-8]
EXPECTED_STAGE1 = b"\x00" * 8
NOP = bytes.fromhex("1f2003d5")
EXPECTED_STAGE2 = NOP * 3
LDUR_X2 = EXPECTED_ENTRY
LSR_X6_X2_32 = bytes.fromhex("46fc60d3")

def parse_load_segments(blob: bytes):
    if blob[:4] != b"\x7fELF" or blob[4] != 2 or blob[5] != 1:
        raise ValueError("not ELF64 little-endian")
    e_phoff = struct.unpack_from("<Q", blob, 0x20)[0]
    e_phentsize = struct.unpack_from("<H", blob, 0x36)[0]
    e_phnum = struct.unpack_from("<H", blob, 0x38)[0]
    segs = []
    for i in range(e_phnum):
        off = e_phoff + i * e_phentsize
        p_type, p_flags = struct.unpack_from("<II", blob, off)
        p_offset, p_vaddr, _p_paddr, p_filesz, p_memsz = struct.unpack_from("<QQQQQ", blob, off + 8)
        if p_type == 1:
            segs.append((p_offset, p_vaddr, p_filesz, p_memsz, p_flags))
    return segs

def va_to_offset(blob: bytes, va: int) -> int:
    for p_offset, p_vaddr, p_filesz, _p_memsz, _flags in parse_load_segments(blob):
        if p_vaddr <= va < p_vaddr + p_filesz:
            return p_offset + (va - p_vaddr)
    raise ValueError(f"VA 0x{va:x} not in a file-backed PT_LOAD")

def enc_b(pc: int, target: int) -> bytes:
    delta = target - pc
    if delta % 4:
        raise ValueError("unaligned branch")
    imm26 = delta // 4
    if not -(1 << 25) <= imm26 < (1 << 25):
        raise ValueError("B target out of range")
    return struct.pack("<I", 0x14000000 | (imm26 & 0x03ffffff))

def enc_cbnz_x(rt: int, pc: int, target: int) -> bytes:
    delta = target - pc
    if delta % 4:
        raise ValueError("unaligned cbnz target")
    imm19 = delta // 4
    if not -(1 << 18) <= imm19 < (1 << 18):
        raise ValueError("CBNZ target out of range")
    insn = 0xB5000000 | ((imm19 & 0x7ffff) << 5) | (rt & 0x1f)
    return struct.pack("<I", insn)

if len(sys.argv) != 2:
    raise SystemExit("usage: patch_engine_v5.py path/to/libcozmoEngine.so")

path = Path(sys.argv[1])
data = bytearray(path.read_bytes())

entry_off = va_to_offset(data, ENTRY_VA)
s1_off = va_to_offset(data, STAGE1_VA)
s2_off = va_to_offset(data, STAGE2_VA)

if bytes(data[entry_off:entry_off+4]) != EXPECTED_ENTRY:
    raise SystemExit(f"unexpected entry bytes: {bytes(data[entry_off:entry_off+4]).hex()}")
if bytes(data[s1_off:s1_off+8]) != EXPECTED_STAGE1:
    raise SystemExit(f"stage1 padding not empty: {bytes(data[s1_off:s1_off+8]).hex()}")
if bytes(data[s2_off:s2_off+12]) != EXPECTED_STAGE2:
    raise SystemExit(f"stage2 padding not NOPs: {bytes(data[s2_off:s2_off+12]).hex()}")

entry = enc_b(ENTRY_VA, STAGE1_VA)
stage1 = LDUR_X2 + enc_b(STAGE1_VA + 4, STAGE2_VA)
stage2 = (
    LSR_X6_X2_32 +
    enc_cbnz_x(6, STAGE2_VA + 4, RET_VA) +
    enc_b(STAGE2_VA + 8, GOOD_VA)
)

data[entry_off:entry_off+4] = entry
data[s1_off:s1_off+8] = stage1
data[s2_off:s2_off+12] = stage2
path.write_bytes(data)

print(f"patched {path}")
print(f"entry  0x{ENTRY_VA:x}: {entry.hex()}")
print(f"stage1 0x{STAGE1_VA:x}: {stage1.hex()}")
print(f"stage2 0x{STAGE2_VA:x}: {stage2.hex()}")
