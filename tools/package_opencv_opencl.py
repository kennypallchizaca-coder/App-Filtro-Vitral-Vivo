"""Keep the official AAR interface and replace its ARM64 native binaries."""
import argparse
import json
import re
import struct
import tempfile
from pathlib import Path
from zipfile import ZIP_DEFLATED, ZipFile


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("official_aar", type=Path)
    parser.add_argument("opencv_library", type=Path)
    parser.add_argument("cpp_runtime", type=Path)
    args = parser.parse_args()

    native = args.opencv_library.read_bytes()
    runtime = args.cpp_runtime.read_bytes()
    for binary in (native, runtime):
        if binary[:5] != b"\x7fELF\x02" or struct.unpack_from("<H", binary, 18)[0] != 183:
            raise ValueError("Both native libraries must be 64-bit ARM ELF files")
    if not re.search(rb"OpenCL:[ \t]+YES", native):
        raise ValueError("OpenCV must be built with WITH_OPENCL=ON")

    output = Path(__file__).resolve().parents[1] / "app/libs/opencv-5.0.0-opencl-arm64.aar"
    output.parent.mkdir(parents=True, exist_ok=True)
    with tempfile.NamedTemporaryFile(dir=output.parent, suffix=".aar.tmp", delete=False) as temporary:
        temporary_path = Path(temporary.name)
    replacements = {
        "jni/arm64-v8a/libopencv_java5.so": native,
        "jni/arm64-v8a/libc++_shared.so": runtime,
        "prefab/modules/opencv_java5/libs/android.arm64-v8a/libopencv_java5.so": native,
    }
    replaced = set()
    try:
        with ZipFile(args.official_aar) as source, ZipFile(temporary_path, "w", ZIP_DEFLATED) as target:
            if source.testzip() is not None:
                raise ValueError("The official AAR is corrupt")
            for entry in source.infolist():
                name = entry.filename
                if name.startswith("jni/") and not name.startswith("jni/arm64-v8a/"):
                    continue
                if name.startswith("prefab/modules/opencv_java5/libs/android."):
                    if not name.startswith("prefab/modules/opencv_java5/libs/android.arm64-v8a/"):
                        continue
                data = source.read(name)
                if name in replacements:
                    data = replacements[name]
                    replaced.add(name)
                elif name.endswith("/android.arm64-v8a/abi.json"):
                    metadata = json.loads(data)
                    metadata.update(api=26, ndk=28, stl="c++_shared", static=False)
                    data = json.dumps(metadata).encode("utf-8")
                target.writestr(entry, data)
            if replaced != set(replacements):
                raise ValueError("The official AAR does not have the expected JNI/Prefab layout")
        with ZipFile(temporary_path) as check:
            if check.testzip() is not None:
                raise ValueError("The generated AAR is corrupt")
        temporary_path.replace(output)
    except BaseException:
        temporary_path.unlink(missing_ok=True)
        raise
    print(output)


if __name__ == "__main__":
    main()
