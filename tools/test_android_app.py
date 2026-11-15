"""Static mobile checks; these do not replace running the camera on a phone."""
import unittest
import xml.etree.ElementTree as ET
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
A = "{http://schemas.android.com/apk/res/android}"


def luminance(color):
    rgb = [int(color[i:i + 2], 16) / 255 for i in (1, 3, 5)]
    linear = [v / 12.92 if v <= 0.04045 else ((v + 0.055) / 1.055) ** 2.4 for v in rgb]
    return sum(v * weight for v, weight in zip(linear, (0.2126, 0.7152, 0.0722)))


class AndroidAppTest(unittest.TestCase):
    def test_camera_is_explicit_and_no_storage_or_network_permission(self):
        manifest = ET.parse(ROOT / "app/src/main/AndroidManifest.xml")
        permissions = {entry.get(A + "name") for entry in manifest.findall("uses-permission")}
        self.assertEqual(permissions, {"android.permission.CAMERA"})
        vendor = manifest.find("./application/uses-native-library")
        self.assertEqual(vendor.get(A + "required"), "false")

    def test_named_views_and_touch_targets(self):
        strings = ET.parse(ROOT / "app/src/main/res/values/strings.xml")
        views = strings.find("./string-array[@name='views']")
        self.assertEqual(len(views), 9)
        layout = ET.parse(ROOT / "app/src/main/res/layout/activity_main.xml")
        buttons = [node for node in layout.iter() if node.tag.endswith("Button")]
        self.assertGreaterEqual(len(buttons), 6)
        for button in buttons:
            self.assertGreaterEqual(float(button.get(A + "minHeight", "0dp")[:-2]), 48)
            self.assertTrue(button.get(A + "text"))
        camera = layout.find(".//org.opencv.android.JavaCamera2View")
        self.assertTrue(camera.get(A + "contentDescription"))

    def test_control_palette_contrast(self):
        colors = {node.get("name"): node.text for node in
                  ET.parse(ROOT / "app/src/main/res/values/paleta.xml").getroot()}
        for first, second in [(colors["tinta"], colors["papel"]),
                              (colors["acento"], colors["papel"]),
                              ("#FFFFFF", colors["acento"])]:
            low, high = sorted((luminance(first), luminance(second)))
            self.assertGreaterEqual((high + 0.05) / (low + 0.05), 4.5)


if __name__ == "__main__":
    unittest.main()
