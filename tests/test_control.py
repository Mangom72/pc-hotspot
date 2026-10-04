import os
from pathlib import Path
import subprocess
import sys
import tempfile
import unittest
from unittest.mock import patch
sys.path.insert(0, str(Path(__file__).resolve().parents[1] / 'pc'))
import hotspot_control as h

class ControlTests(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.dir_patch = patch.object(h, 'STATE_DIR', Path(self.temp.name))
        self.dir_patch.start()
        self.widget_patch = patch.object(h, 'show_widget')
        self.widget_patch.start()
        self.active = ''
        self.connection = '--'
        self.mutations = []
        self.nm_patch = patch.object(h, 'nm', side_effect=self.nm)
        self.nm_patch.start()
    def tearDown(self):
        self.nm_patch.stop(); self.widget_patch.stop(); self.dir_patch.stop(); self.temp.cleanup()
    def nm(self, *args):
        if args[0] == '-g':
            return {'GENERAL.CON-UUID': self.active, 'GENERAL.CONNECTION': self.connection,
                    'connection.uuid': 'profile-uuid', '802-11-wireless.mode': 'ap'}[args[1]]
        self.mutations.append(args)
        self.active = 'profile-uuid' if args[1] == 'up' else ''
        self.connection = 'Hotspot' if args[1] == 'up' else '--'
        return ''
    def test_set_is_explicit_and_idempotent(self):
        self.assertTrue(h.set_hotspot(True)); self.assertTrue(h.set_hotspot(True))
        self.assertFalse(h.set_hotspot(False)); self.assertFalse(h.set_hotspot(False))
        self.assertEqual(len(self.mutations), 2)
    def test_other_wifi_never_disconnected(self):
        self.active = 'other-uuid'; self.connection = 'Home'
        with self.assertRaises(h.ControlError) as e: h.set_hotspot(True)
        self.assertEqual(e.exception.code, 2)
        self.assertFalse(h.set_hotspot(False))
        self.assertEqual(self.mutations, [])
    def test_duplicate_profile_name_not_mistaken_for_hotspot(self):
        self.active = 'other-uuid'; self.connection = 'Hotspot'
        with self.assertRaises(h.ControlError): h.set_hotspot(True)
        self.assertEqual(self.mutations, [])
    def test_contending_process_is_rejected_not_queued(self):
        with h.control_lock():
            code = "import sys; sys.path.insert(0, sys.argv[1]); import hotspot_control as h\nh.STATE_DIR=__import__('pathlib').Path(sys.argv[2])\ntry:\n h.set_hotspot(True)\nexcept h.ControlError as e:\n sys.exit(0 if e.code==1 else 2)\nsys.exit(3)"
            result = subprocess.run([sys.executable, '-c', code, str(Path(h.__file__).parent), self.temp.name], timeout=2)
            self.assertEqual(result.returncode, 0)
        self.assertTrue(h.set_hotspot(True))
    def test_failed_command_does_not_retry(self):
        with patch.object(h, 'nm', side_effect=h.ControlError(3, 'fail')) as nm:
            with self.assertRaises(h.ControlError): h.set_hotspot(True)
        self.assertEqual(nm.call_count, 1)
    def test_expired_deadline_never_launches_a_command(self):
        self.nm_patch.stop()
        token = h.COMMAND_DEADLINE.set(0)
        try:
            with patch.object(h.subprocess, 'run') as command:
                with self.assertRaises(h.ControlError): h.nm('connection', 'up', 'Hotspot')
                command.assert_not_called()
        finally:
            h.COMMAND_DEADLINE.reset(token)
    def test_toggle_reads_inside_lock(self):
        self.assertTrue(h.toggle_hotspot()); self.assertFalse(h.toggle_hotspot())
        self.assertEqual(len(self.mutations), 2)

if __name__ == '__main__': unittest.main()
