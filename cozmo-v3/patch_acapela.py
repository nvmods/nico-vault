#!/usr/bin/env python3
from pathlib import Path
import struct
import sys

# Cozmo 3.6.6 ARM64 libacattsandroid.so
# strerror() @ 0x447c0 contains at 0x447d0:
#   bl 0x447c0
# i.e. a recursive self-call which overflows the RelTransport thread stack
# whenever the error path is reached.
#
# We redirect that call to the function's existing fallback path @ 0x44814,
# which formats the numeric error instead of recursively calling strerror.
CALL_VA = 0x447D0
FALLBACK_VA = 0x44814
EXPECTED = bytes.fromhex("fcffff97")  # bl 0x447c0

def encode_b(pc, target):
    delta = target - pc
    if delta % 4:
        raise ValueError("unaligned AArch64 branch")
    imm26 = delta // 4
    if not -(1 << 25) <= imm26 < (1 << 25):
        raise ValueError("branch out of range")
    insn = 0x14000000 | (imm26 & 0x03FFFFFF)
    return struct.pack("<I", insn)

def va_to_offset(blob, va):
    # ELF64 little-endian program headers.
    if blob[:4] != b"\x7fELF" or blob[4] != 2 or blob[5] != 1:
        raise ValueError("not ELF64 little-endian")
    e_phoff = struct.unpack_from("<Q", blob, 0x20)[0]
    e_phentsize = struct.unpack_from("<H", blob, 0x36)[0]
    e_phnum = struct.unpack_from("<H", blob, 0x38)[0]
    for i in range(e_phnum):
        off = e_phoff + i * e_phentsize
        p_type, p_flags = struct.unpack_from("<II", blob, off)
        p_offset, p_vaddr, _p_paddr, p_filesz, p_memsz = struct.unpack_from("<QQQQQ", blob, off + 8)
        if p_type == 1 and p_vaddr <= va < p_vaddr + p_filesz:
            return p_offset + (va - p_vaddr)
    raise ValueError(f"VA 0x{va:x} is not inside a PT_LOAD segment")

path = Path(sys.argv[1])
data = bytearray(path.read_bytes())
foff = va_to_offset(data, CALL_VA)
before = bytes(data[foff:foff+4])
if before != EXPECTED:
    raise SystemExit(f"unexpected bytes at VA 0x{CALL_VA:x} / file 0x{foff:x}: {before.hex()} != {EXPECTED.hex()}")

replacement = encode_b(CALL_VA, FALLBACK_VA)
data[foff:foff+4] = replacement
path.write_bytes(data)

print(f"patched {path}")
print(f"VA 0x{CALL_VA:x}, file offset 0x{foff:x}: {before.hex()} -> {replacement.hex()}")
print(f"strerror recursion now branches to fallback 0x{FALLBACK_VA:x}")
