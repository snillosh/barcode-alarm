#!/usr/bin/env python3
"""Check release packaging for scanner classes that JVM unit tests cannot exercise."""
import sys
import zipfile
from pathlib import Path

apk = Path(sys.argv[1]) if len(sys.argv) > 1 else Path(__file__).resolve().parents[1] / 'app/build/outputs/apk/debug/app-debug.apk'
required = (
    b'Landroidx/core/content/ContextCompat;',
    b'Landroidx/core/app/ActivityCompat;',
    b'Lcom/journeyapps/barcodescanner/CaptureActivity;',
    b'Lcom/google/zxing/MultiFormatReader;',
)
# Read DEX class_defs rather than string references: a reference alone would also
# be present in the broken APK and would not establish that the class is defined.
import struct
with zipfile.ZipFile(apk) as archive:
    defined = set()
    for name in archive.namelist():
        if not (name.startswith('classes') and name.endswith('.dex')):
            continue
        data = archive.read(name)
        string_count, string_offset, type_count, type_offset = struct.unpack_from('<4I', data, 56)
        class_count, class_offset = struct.unpack_from('<2I', data, 96)
        for i in range(class_count):
            type_index = struct.unpack_from('<I', data, class_offset + i * 32)[0]
            string_index = struct.unpack_from('<I', data, type_offset + type_index * 4)[0]
            offset = struct.unpack_from('<I', data, string_offset + string_index * 4)[0]
            while data[offset] & 0x80:  # Skip ULEB128 UTF-16 length.
                offset += 1
            offset += 1
            defined.add(data[offset:data.index(b'\0', offset)])
missing = [item.decode() for item in required if item not in defined]
if missing:
    sys.exit('Missing scanner classes: ' + ', '.join(missing))
print('PASS: APK defines all four required scanner/runtime classes')
