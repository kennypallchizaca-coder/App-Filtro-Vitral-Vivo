"""Comprobaciones estáticas; no sustituyen probar la cámara en el teléfono."""
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
    def test_ascii_has_seven_controls(self):
        controls = (ROOT / "app/src/main/java/com/example/interciclovision/ParametrosMoviles.kt").read_text(encoding="utf-8")
        self.assertEqual(controls.count('        ControlFiltro('), 7)
        self.assertIn('ControlFiltro("Tamaño de celda", 6, 24, 1, 9)', controls)

    def test_academic_build_excludes_arcore_prototype(self):
        settings = (ROOT / "settings.gradle.kts").read_text(encoding="utf-8")
        self.assertIn('include(":app")', settings)
        self.assertNotIn(":portalra", settings)

    def test_live_metrics_do_not_offer_file_export(self):
        activity = (ROOT / "app/src/main/java/com/example/interciclovision/MainActivity.kt").read_text(encoding="utf-8")
        layout = (ROOT / "app/src/main/res/layout/activity_main.xml").read_text(encoding="utf-8")
        self.assertNotIn("CreateDocument", activity)
        self.assertNotIn("RegistroMediciones", activity)
        self.assertNotIn("export_button", layout)
        self.assertNotIn("measure_button", layout)
        self.assertIn("R.string.live_metrics", activity)
        self.assertIn("actualizarRecursos()", activity)
        self.assertIn("parametros, usarGpu, vistaActual, true)", activity)

    def test_portal_does_not_require_transformation_slider(self):
        layout = ET.parse(ROOT / "app/src/main/res/layout/activity_main.xml")
        control = layout.find(".//SeekBar[@" + A + "id='@+id/transformation']")
        self.assertIsNone(control)
        activity = (ROOT / "app/src/main/java/com/example/interciclovision/MainActivity.kt").read_text(encoding="utf-8")
        self.assertNotIn("binding.transformation", activity)

    def test_camera_restart_restores_waiting_message(self):
        activity = (ROOT / "app/src/main/java/com/example/interciclovision/MainActivity.kt").read_text(encoding="utf-8")
        activate = activity.split("private fun activarCamara()", 1)[1].split("override fun onResume", 1)[0]
        self.assertIn("binding.emptyPreview.visibility = View.VISIBLE", activate.split("enableView()", 1)[0])
        started = activity.split("override fun onCameraViewStarted", 1)[1].split("override fun onCameraViewStopped", 1)[0]
        self.assertIn("binding.emptyPreview.visibility = View.GONE", started)

    def test_camera_is_explicit_and_no_storage_or_network_permission(self):
        manifest = ET.parse(ROOT / "app/src/main/AndroidManifest.xml")
        permissions = {entry.get(A + "name") for entry in manifest.findall("uses-permission")}
        self.assertEqual(permissions, {"android.permission.CAMERA"})
        vendor = manifest.find("./application/uses-native-library")
        self.assertEqual(vendor.get(A + "required"), "false")

    def test_named_views_and_touch_targets(self):
        strings = ET.parse(ROOT / "app/src/main/res/values/strings.xml")
        views = strings.find("./string-array[@name='views']")
        self.assertEqual(len(views), 10)
        self.assertEqual(views[1].text, "ASCII")
        self.assertEqual(views[7].text, "Respuesta del kernel propio")
        self.assertEqual(views[9].text, "Portal ASCII")
        layout = ET.parse(ROOT / "app/src/main/res/layout/activity_main.xml")
        buttons = [node for node in layout.iter() if node.tag.endswith("Button")]
        ids = {button.get(A + "id") for button in buttons}
        self.assertTrue({"@+id/permission_button", "@+id/cpu_button", "@+id/gpu_button",
                         "@+id/camera_button", "@+id/reset_button"}.issubset(ids))
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
