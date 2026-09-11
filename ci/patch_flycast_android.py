#!/usr/bin/env python3
"""Add Flycast's libandroid dependency without rewriting its ELF layout."""

from __future__ import annotations

import struct
import sys
from pathlib import Path


PT_LOAD = 1
PT_DYNAMIC = 2
DT_NULL = 0
DT_NEEDED = 1
DT_STRTAB = 5
DT_SONAME = 14
OLD_SONAME = b"flycast_libretro.so"
ANDROID_LIBRARY = b"libandroid.so"


def fail(message: str) -> None:
    raise SystemExit(f"Flycast ELF patch failed: {message}")


def main() -> None:
    if len(sys.argv) != 2:
        fail("usage: patch_flycast_android.py <libflycast.so>")

    path = Path(sys.argv[1])
    data = bytearray(path.read_bytes())
    if data[:6] != b"\x7fELF\x02\x01":
        fail("expected a little-endian ELF64 library")

    program_offset = struct.unpack_from("<Q", data, 32)[0]
    program_entry_size = struct.unpack_from("<H", data, 54)[0]
    program_count = struct.unpack_from("<H", data, 56)[0]
    if program_entry_size < 56:
        fail("invalid program-header entry size")

    load_segments: list[tuple[int, int, int]] = []
    dynamic_offset = None
    dynamic_size = None
    for index in range(program_count):
        offset = program_offset + index * program_entry_size
        segment_type, _flags, file_offset, virtual_address, _physical_address, file_size, _memory_size, _alignment = struct.unpack_from(
            "<IIQQQQQQ", data, offset
        )
        if segment_type == PT_LOAD:
            load_segments.append((virtual_address, file_offset, file_size))
        elif segment_type == PT_DYNAMIC:
            dynamic_offset = file_offset
            dynamic_size = file_size

    if dynamic_offset is None or dynamic_size is None:
        fail("missing PT_DYNAMIC segment")

    entries: list[tuple[int, int, int]] = []
    for offset in range(dynamic_offset, dynamic_offset + dynamic_size, 16):
        tag, value = struct.unpack_from("<QQ", data, offset)
        entries.append((offset, tag, value))
        if tag == DT_NULL:
            break

    string_table_address = next((value for _offset, tag, value in entries if tag == DT_STRTAB), None)
    if string_table_address is None:
        fail("missing DT_STRTAB")

    def virtual_to_file(address: int) -> int:
        for virtual_address, file_offset, file_size in load_segments:
            if virtual_address <= address < virtual_address + file_size:
                return file_offset + address - virtual_address
        fail(f"virtual address 0x{address:x} is outside file-backed PT_LOAD segments")

    string_table_offset = virtual_to_file(string_table_address)

    def dynamic_string(value: int) -> bytes:
        start = string_table_offset + value
        end = data.find(0, start)
        if end < 0:
            fail("unterminated dynamic string")
        return bytes(data[start:end])

    needed = [dynamic_string(value) for _offset, tag, value in entries if tag == DT_NEEDED]
    if ANDROID_LIBRARY in needed:
        print("Flycast already depends on libandroid.so")
        return

    soname_entry = next(((offset, value) for offset, tag, value in entries if tag == DT_SONAME), None)
    if soname_entry is None:
        fail("missing reusable DT_SONAME entry")
    entry_offset, string_offset = soname_entry
    soname = dynamic_string(string_offset)
    if soname != OLD_SONAME:
        fail(f"unexpected SONAME {soname!r}")
    if len(ANDROID_LIBRARY) > len(soname):
        fail("replacement library name does not fit in place")

    string_start = string_table_offset + string_offset
    data[string_start : string_start + len(soname) + 1] = ANDROID_LIBRARY.ljust(len(soname) + 1, b"\0")
    struct.pack_into("<Q", data, entry_offset, DT_NEEDED)
    path.write_bytes(data)
    print("Replaced Flycast DT_SONAME with DT_NEEDED libandroid.so without moving ELF data")


if __name__ == "__main__":
    main()
