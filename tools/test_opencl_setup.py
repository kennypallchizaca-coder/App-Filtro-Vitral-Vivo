"""Check local OpenCV packaging without connecting to an Android device."""
import json
import struct
import unittest
import xml.etree.ElementTree as ET
from pathlib import Path
from zipfile import ZipFile

ROOT = Path(__file__).resolve().parents[1]
AAR = ROOT / "app/libs/opencv-5.0.0-opencl-arm64.aar"
ANDROID = "{http://schemas.android.com/apk/res/android}"


class OpenCLSetupTest(unittest.TestCase):
    def test_native_package(self):
        self.assertTrue(AAR.is_file(), "Build and package the OpenCL-enabled AAR first")
        with ZipFile(AAR) as archive:
            self.assertIsNone(archive.testzip())
            native = archive.read("jni/arm64-v8a/libopencv_java5.so")
            prefab = archive.read("prefab/modules/opencv_java5/libs/android.arm64-v8a/libopencv_java5.so")
            self.assertEqual(native, prefab)
            metadata = json.loads(archive.read("prefab/modules/opencv_java5/libs/android.arm64-v8a/abi.json"))
            self.assertEqual((metadata["api"], metadata["ndk"], metadata["stl"], metadata["static"]), (26, 28, "c++_shared", False))
            self.assertEqual(native[:4], b"\x7fELF")
            self.assertEqual(struct.unpack_from("<H", native, 18)[0], 183)
            self.assertRegex(native, rb"OpenCL:[ \t]+YES")
            libraries = {name for name in archive.namelist() if name.startswith("jni/") and name.endswith(".so")}
            self.assertEqual(libraries, {"jni/arm64-v8a/libopencv_java5.so", "jni/arm64-v8a/libc++_shared.so"})
            for name in libraries:
                data = archive.read(name)
                phoff = struct.unpack_from("<Q", data, 32)[0]
                phsize, phcount = struct.unpack_from("<HH", data, 54)
                loads = [struct.unpack_from("<IIQQQQQQ", data, phoff + i * phsize) for i in range(phcount)]
                loads = [entry for entry in loads if entry[0] == 1]
                self.assertTrue(loads)
                self.assertTrue(all(entry[7] >= 16384 for entry in loads), name)

    def test_gradle_dependency(self):
        gradle = (ROOT / "app/build.gradle.kts").read_text()
        self.assertIn('implementation(files("libs/opencv-5.0.0-opencl-arm64.aar"))', gradle)
        self.assertNotIn('implementation("org.opencv:opencv:', gradle)
        self.assertIn('abiFilters += "arm64-v8a"', gradle)
        self.assertIn('ndkVersion = "28.2.13676358"', gradle)

    def test_optional_vendor_library(self):
        manifest = ET.parse(ROOT / "app/src/main/AndroidManifest.xml")
        libraries = manifest.findall("./application/uses-native-library")
        opencl = [entry for entry in libraries if entry.get(ANDROID + "name") == "libOpenCL.so"]
        self.assertEqual(len(opencl), 1)
        self.assertEqual(opencl[0].get(ANDROID + "required"), "false")


if __name__ == "__main__":
    unittest.main()
