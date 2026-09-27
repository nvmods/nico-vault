#!/usr/bin/env python3
from pathlib import Path
import struct
import sys

# Cozmo 3.6.6 ARM64 libcozmoEngine.so
#
# Observed V3/V2 crash after TTS:
#   tlsf-like free/coalesce path receives a next-block size such as
#   0x9856c67c996e466c (impossible for this pool), then treats the
#   neighbour as a free-list node and dereferences its corrupted links.
#
# Normal path:
#   0x12d3f68  lsr  x9, x2, #32
#   0x12d3f6c  cbz  x9, 0x12d4090   ; all realistic (<4GiB) block sizes
#   0x12d3f70  mov  w7, #0x3a       ; >4GiB path, impossible here
#
# V4 changes only that impossible >4GiB path:
#   0x12d3f70 -> branch to an 8-byte executable padding cave
#   cave:
#       and x2, x3, #~3             ; recover current block size
#       b   0x12d3e4c               ; insert current block, skip corrupt neighbour merge
#
# This is deliberately narrow: valid 32-bit TLSF block sizes follow the
# untouched original path. It avoids blindly disabling free() globally.

PATCH_VA = 0x12D3F70
CAVE_VA  = 0x1339908
SAFE_VA  = 0x12D3E4C

EXPECTED_PATCH = bytes.fromhex("47078052")   # mov w7,#0x3a
EXPECTED_CAVE  = b"\x00" * 8
AND_X2_X3_MASK = bytes.fromhex("62f47e92")   # and x2,x3,#0xfffffffffffffffc

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

def encode_b(pc: int, target: int) -> bytes:
    delta = target - pc
    if delta % 4:
        raise ValueError("unaligned AArch64 branch")
    imm26 = delta // 4
    if not -(1 << 25) <= imm26 < (1 << 25):
        raise ValueError("AArch64 B target out of range")
    insn = 0x14000000 | (imm26 & 0x03ffffff)
    return struct.pack("<I", insn)

if len(sys.argv) != 2:
    raise SystemExit("usage: patch_engine_v4.py path/to/libcozmoEngine.so")

path = Path(sys.argv[1])
data = bytearray(path.read_bytes())
patch_off = va_to_offset(data, PATCH_VA)
cave_off = va_to_offset(data, CAVE_VA)

if bytes(data[patch_off:patch_off+4]) != EXPECTED_PATCH:
    raise SystemExit(
        f"refusing patch: bytes at 0x{PATCH_VA:x} are "
        f"{bytes(data[patch_off:patch_off+4]).hex()}, expected {EXPECTED_PATCH.hex()}"
    )
if bytes(data[cave_off:cave_off+8]) != EXPECTED_CAVE:
    raise SystemExit(
        f"refusing patch: code cave at 0x{CAVE_VA:x} is not empty: "
        f"{bytes(data[cave_off:cave_off+8]).hex()}"
    )

branch_to_cave = encode_b(PATCH_VA, CAVE_VA)
branch_to_safe = encode_b(CAVE_VA + 4, SAFE_VA)

data[patch_off:patch_off+4] = branch_to_cave
data[cave_off:cave_off+8] = AND_X2_X3_MASK + branch_to_safe
path.write_bytes(data)

print(f"patched {path}")
print(f"0x{PATCH_VA:x}: {EXPECTED_PATCH.hex()} -> {branch_to_cave.hex()} (guard invalid >4GiB neighbour)")
print(f"0x{CAVE_VA:x}: {AND_X2_X3_MASK.hex()} {branch_to_safe.hex()} (recover current size -> 0x{SAFE_VA:x})")
