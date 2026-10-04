import importlib.util
from importlib.machinery import SourceFileLoader
from pathlib import Path
import tempfile
import unittest
from unittest.mock import patch

class F9Tests(unittest.TestCase):
    def setUp(self):
        path = Path(__file__).resolve().parents[1] / 'pc/f9-action'
        loader = SourceFileLoader('f9_test', str(path))
        spec = importlib.util.spec_from_loader(loader.name, loader)
        self.f9 = importlib.util.module_from_spec(spec)
        loader.exec_module(self.f9)
        self.temp = tempfile.TemporaryDirectory()
        self.f9.LOCK_FILE = Path(self.temp.name) / 'f9.lock'
        self.f9.STATE_FILE = Path(self.temp.name) / 'f9.json'
    def tearDown(self): self.temp.cleanup()
    def test_single_press_keeps_gfn_login(self):
        with patch.object(self.f9,'show_widget'), patch.object(self.f9,'restore_hotspot_status') as restore, \
             patch.object(self.f9.time,'sleep'), patch.object(self.f9.subprocess,'run') as login, \
             patch.object(self.f9,'toggle_hotspot') as toggle:
            self.f9.main()
            login.assert_called_once_with([str(self.f9.GFN_LOGIN), 'auto'], check=False)
            restore.assert_called_once(); toggle.assert_not_called()
    def test_double_and_accidental_triple_only_toggle_once(self):
        def second_and_third(_):
            self.f9.main(); self.f9.main()
        with patch.object(self.f9,'show_widget'), patch.object(self.f9.time,'sleep',side_effect=second_and_third), \
             patch.object(self.f9.subprocess,'run') as login, patch.object(self.f9,'toggle_hotspot') as toggle:
            self.f9.main()
            toggle.assert_called_once(); login.assert_not_called()

if __name__ == '__main__': unittest.main()
