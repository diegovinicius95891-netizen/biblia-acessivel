"""Testes do reinicio independente usado pelo atualizador Windows."""

import os
import unittest
from unittest.mock import patch

from biblia.update_ui import independent_process_environment


class IndependentProcessEnvironmentTests(unittest.TestCase):
    """Evita que uma versao nova reutilize a pasta temporaria da antiga."""

    def test_removes_pyinstaller_private_state_and_requests_new_instance(self):
        """Descarta marcadores privados, preservando as variaveis do usuario."""
        source = {
            "PATH": os.pathsep.join((r"C:\Windows\System32", r"C:\Aplicativo\_MEI123")),
            "USERPROFILE": r"C:\Users\Pessoa",
            "_PYI_ARCHIVE_FILE": r"C:\Aplicativo\BibliaAcessivel.exe",
            "_PYI_APPLICATION_HOME_DIR": r"C:\Aplicativo\_MEI123",
            "_MEIPASS2": r"C:\Aplicativo\_MEI123",
        }
        with patch("biblia.update_ui.sys._MEIPASS", r"C:\Aplicativo\_MEI123", create=True):
            result = independent_process_environment(source)

        self.assertEqual("1", result["PYINSTALLER_RESET_ENVIRONMENT"])
        self.assertEqual(r"C:\Users\Pessoa", result["USERPROFILE"])
        self.assertNotIn("_PYI_ARCHIVE_FILE", result)
        self.assertNotIn("_PYI_APPLICATION_HOME_DIR", result)
        self.assertNotIn("_MEIPASS2", result)
        self.assertEqual(r"C:\Windows\System32", result["PATH"])


if __name__ == "__main__":
    unittest.main()
