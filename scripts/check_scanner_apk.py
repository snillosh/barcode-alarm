#!/usr/bin/env python3
"""Check that the built APK defines the scanner's required runtime classes.

JVM tests cannot detect a missing class inside an installed Android APK. Inspect
actual DEX definitions, because the broken APK also contained references to the
missing AndroidX classes. A matching string alone would be a false positive.
"""

import struct
import sys
import zipfile
from pathlib import Path

REQUIRED_CLASSES = (
    b"Landroidx/core/content/ContextCompat;",
    b"Landroidx/core/app/ActivityCompat;",
    b"Lcom/journeyapps/barcodescanner/CaptureActivity;",
    b"Lcom/google/zxing/MultiFormatReader;",
)
DEFAULT_APK = (
    Path(__file__).resolve().parents[1]
    / "app/build/outputs/apk/debug/app-debug.apk"
)


def read_defined_classes(dex_data: bytes) -> set[bytes]:
    """Follow DEX class -> type -> string indexes to collect defined class names."""
    # Fixed offsets in the DEX header locate the string, type, and class tables.
    _, string_table_offset, _, type_table_offset = struct.unpack_from(
        "<4I", dex_data, 56
    )
    class_count, class_table_offset = struct.unpack_from("<2I", dex_data, 96)
    class_names = set()

    for class_index in range(class_count):
        class_definition_offset = class_table_offset + class_index * 32
        type_index = struct.unpack_from("<I", dex_data, class_definition_offset)[0]
        string_index = struct.unpack_from(
            "<I", dex_data, type_table_offset + type_index * 4
        )[0]
        string_offset = struct.unpack_from(
            "<I", dex_data, string_table_offset + string_index * 4
        )[0]

        # Each string starts with its ULEB128-encoded UTF-16 character count.
        while dex_data[string_offset] & 0x80:
            string_offset += 1
        string_offset += 1
        string_end = dex_data.index(b"\0", string_offset)
        class_names.add(dex_data[string_offset:string_end])

    return class_names


def main() -> None:
    apk_path = Path(sys.argv[1]) if len(sys.argv) > 1 else DEFAULT_APK
    defined_classes = set()
    with zipfile.ZipFile(apk_path) as archive:
        for filename in archive.namelist():
            if filename.startswith("classes") and filename.endswith(".dex"):
                defined_classes.update(read_defined_classes(archive.read(filename)))

    missing_classes = [
        class_name.decode()
        for class_name in REQUIRED_CLASSES
        if class_name not in defined_classes
    ]
    if missing_classes:
        sys.exit("Missing scanner classes: " + ", ".join(missing_classes))
    print("PASS: APK defines all four required scanner/runtime classes")


if __name__ == "__main__":
    main()
