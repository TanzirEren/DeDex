#!/usr/bin/env python3
"""Scan raw memory-dump chunks for embedded DEX files and slice them out.

Usage: python3 extract_dex_from_dumps.py <dumps_dir> <out_dir>
Looks at every *.bin file in dumps_dir, finds every "dex\nXXX\0" magic,
reads the DEX header's file_size field and slices that many bytes out.
Keeps a result only if it parses as a structurally sane DEX header.
"""
import os
import sys
import struct
import hashlib

MAGIC_PREFIX = b"dex\n"
HEADER_LEN = 0x70


def sane_header(buf: bytes) -> bool:
    if len(buf) < HEADER_LEN:
        return False
    if buf[0:4] != MAGIC_PREFIX or buf[7] != 0:
        return False
    file_size = struct.unpack_from("<I", buf, 0x20)[0]
    header_size = struct.unpack_from("<I", buf, 0x24)[0]
    endian = struct.unpack_from("<I", buf, 0x28)[0]
    string_ids_size, string_ids_off = struct.unpack_from("<II", buf, 0x38)
    if header_size != HEADER_LEN:
        return False
    if endian != 0x12345678:
        return False
    if file_size < HEADER_LEN or file_size > 200 * 1024 * 1024:
        return False
    if string_ids_size > 2_000_000:
        return False
    if string_ids_off and string_ids_off >= file_size:
        return False
    return True


def find_all(blob: bytes, sub: bytes):
    i = blob.find(sub)
    while i != -1:
        yield i
        i = blob.find(sub, i + 1)


def main():
    dumps_dir, out_dir = sys.argv[1], sys.argv[2]
    os.makedirs(out_dir, exist_ok=True)
    seen = set()
    found = 0
    files = sorted(f for f in os.listdir(dumps_dir) if f.endswith(".bin"))
    print(f"{len(files)} dump chunk(s) to scan")
    for name in files:
        path = os.path.join(dumps_dir, name)
        with open(path, "rb") as f:
            blob = f.read()
        for off in find_all(blob, MAGIC_PREFIX):
            header = blob[off:off + HEADER_LEN]
            if not sane_header(header):
                continue
            file_size = struct.unpack_from("<I", header, 0x20)[0]
            if off + file_size > len(blob):
                continue  # dex body cut off by chunk boundary, skip
            dex = blob[off:off + file_size]
            h = hashlib.sha256(dex).hexdigest()
            if h in seen:
                continue
            seen.add(h)
            found += 1
            out_name = f"recovered_{found:02d}_{h[:12]}.dex"
            with open(os.path.join(out_dir, out_name), "wb") as o:
                o.write(dex)
            print(f"  {name} @0x{off:x}  ->  {out_name}  ({file_size} bytes)")
    print(f"\nTotal unique DEX recovered: {found}")
    if found == 0:
        print("Nothing found. The DEX may be larger than a single scanned "
              "region, or the app may not have decrypted it yet when the "
              "dump was taken.")


if __name__ == "__main__":
    main()
