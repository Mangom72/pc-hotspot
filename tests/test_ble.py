import asyncio
import json
from pathlib import Path
import sys
import tempfile
import time
import unittest
from unittest.mock import AsyncMock, patch
sys.path.insert(0, str(Path(__file__).resolve().parents[1] / 'pc'))
from dbus_next import Variant, DBusError
from dbus_next.service import ServiceInterface
import ble_server as b

# dbus-next decorators do not expose direct async invocation. Test the actual
# exported method functions, exactly as MessageBus dispatches them.
def exported(obj, name, *args):
    m = next(m for m in ServiceInterface._get_methods(obj) if m.name == name)
    return m.fn(obj, *args)

class BleTests(unittest.IsolatedAsyncioTestCase):
    async def asyncSetUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.file_patch = patch.object(b, 'ALLOWED', Path(self.temp.name) / 'phones.json')
        self.dir_patch = patch.object(b, 'STATE_DIR', Path(self.temp.name))
        self.recovery_patch = patch.object(b, 'ENROLL_RECOVERY', Path(self.temp.name) / 'recovery.json')
        self.file_patch.start(); self.dir_patch.start(); self.recovery_patch.start()
        self.server = b.Server()
        self.device = '/org/bluez/hci0/dev_AA_BB_CC_DD_EE_FF'
        self.props = {'Address': Variant('s','AA:BB:CC:DD:EE:FF'), 'Bonded': Variant('b', True), 'Connected': Variant('b', True)}
        self.server.device_props = AsyncMock(return_value=self.props)
        self.server.close_enrollment = AsyncMock()
        self.options = {'device': Variant('o', self.device)}
    async def asyncTearDown(self):
        self.file_patch.stop(); self.dir_patch.stop(); self.recovery_patch.stop(); self.temp.cleanup()
    async def test_unknown_phone_denied(self):
        with self.assertRaises(DBusError): await self.server.authorize(self.options)
    async def test_unbonded_phone_denied_even_if_allowlisted(self):
        self.server.save_allowed({'AA:BB:CC:DD:EE:FF'})
        self.props['Bonded'] = Variant('b',False)
        with self.assertRaises(DBusError): await self.server.authorize(self.options)
    async def test_local_confirmation_required_and_session_closes(self):
        self.server.enroll_until = time.monotonic() + 30
        self.server.candidate = dict(device=self.device, approved=False)
        with self.assertRaises(DBusError): await self.server.authorize(self.options)
        self.server.candidate['approved'] = True
        await self.server.authorize(self.options)
        self.assertEqual(self.server.allowed(), {'AA:BB:CC:DD:EE:FF'})
        self.server.close_enrollment.assert_awaited_once()
        self.assertEqual(b.ALLOWED.stat().st_mode & 0o777, 0o600)
    async def test_expired_registration_denied(self):
        self.server.enroll_until = time.monotonic() - 1
        self.server.candidate = dict(device=self.device, approved=True)
        with self.assertRaises(DBusError): await self.server.authorize(self.options)
    async def test_duplicate_request_deduplicated_and_conflict_denied(self):
        self.server.save_allowed({'AA:BB:CC:DD:EE:FF'})
        command = bytes([1,1]) + bytes(range(8))
        with patch.object(b, 'set_hotspot', return_value=True) as control:
            await exported(self.server.command, 'WriteValue', command, self.options)
            await exported(self.server.command, 'WriteValue', command, self.options)
            self.assertEqual(control.call_count, 1)
            with self.assertRaises(DBusError):
                await exported(self.server.command, 'WriteValue', bytes([1,0])+command[2:], self.options)
        self.assertEqual(self.server.result.value, bytes([1,1,0])+command[2:])
    async def test_busy_rejected_without_later_execution(self):
        self.server.save_allowed({'AA:BB:CC:DD:EE:FF'})
        self.server.busy = True
        with patch.object(b, 'set_hotspot') as control:
            await exported(self.server.command, 'WriteValue', bytes([1,1])+bytes(8), self.options)
            control.assert_not_called()
        self.assertEqual(self.server.result.value[2], 1)
    async def test_invalid_and_partial_requests_denied(self):
        self.server.save_allowed({'AA:BB:CC:DD:EE:FF'})
        with patch.object(b, 'set_hotspot') as control:
            for command in [b'', bytes([2,1])+bytes(8), bytes([1,3])+bytes(8), bytes(100)]:
                with self.assertRaises(DBusError): await exported(self.server.command,'WriteValue', command, self.options)
            with self.assertRaises(DBusError):
                await exported(self.server.command,'WriteValue', bytes([1,1])+bytes(8), dict(self.options, offset=Variant('q',1)))
            control.assert_not_called()
    async def test_unknown_phone_never_reaches_control(self):
        with patch.object(b, 'set_hotspot') as control:
            with self.assertRaises(DBusError):
                await exported(self.server.command, 'WriteValue', bytes([1,1])+bytes(8), self.options)
            control.assert_not_called()
    async def test_numeric_confirmation_waits_for_local_approval(self):
        self.server.enroll_until = time.monotonic() + 10
        agent = b.Agent(self.server)
        task = asyncio.create_task(exported(agent, 'RequestConfirmation', self.device, 123456))
        await asyncio.sleep(0)
        self.assertFalse(task.done())
        self.assertFalse(self.server.candidate['approved'])
        self.assertEqual(self.server.candidate['passkey'], '123456')
        self.server.candidate['approved'] = True
        self.server.confirmation.set_result(True)
        await task
        self.assertTrue(self.server.candidate['approved'])
    async def test_cancel_allows_a_fresh_pairing_attempt(self):
        self.server.enroll_until = time.monotonic() + 10
        agent = b.Agent(self.server)
        task = asyncio.create_task(exported(agent, 'RequestConfirmation', self.device, 123456))
        await asyncio.sleep(0)
        old_id = self.server.candidate['id']
        exported(agent, 'Cancel')
        with self.assertRaises(DBusError): await task
        self.assertIsNone(self.server.candidate)
        task = asyncio.create_task(exported(agent, 'RequestConfirmation', self.device, 654321))
        await asyncio.sleep(0)
        self.assertNotEqual(old_id, self.server.candidate['id'])
        exported(agent, 'Cancel')
        with self.assertRaises(DBusError): await task
    async def test_stale_terminal_approval_cannot_approve_new_candidate(self):
        self.server.enroll_until = time.monotonic() + 10
        self.server.candidate = dict(id='new-request', approved=False)
        self.server.confirmation = asyncio.get_running_loop().create_future()
        class Writer:
            def __init__(self): self.body = b''
            def write(self, body): self.body += body
            async def drain(self): pass
            def close(self): pass
            async def wait_closed(self): pass
        reader = asyncio.StreamReader()
        reader.feed_data(b'{"op":"approve","yes":true,"candidate_id":"old-request"}\n')
        reader.feed_eof()
        writer = Writer()
        await self.server.rpc(reader, writer)
        self.assertFalse(json.loads(writer.body)['ok'])
        self.assertFalse(self.server.confirmation.done())
        self.assertFalse(self.server.candidate['approved'])
    async def test_just_works_is_rejected(self):
        with self.assertRaises(DBusError): exported(b.Agent(self.server), 'RequestAuthorization', self.device)
    async def test_security_flags(self):
        self.assertIn('encrypt-authenticated-read', self.server.state.flags)
        self.assertIn('encrypt-authenticated-write', self.server.command.flags)
        self.assertIn('encrypt-authenticated-notify', self.server.result.flags)

if __name__ == '__main__': unittest.main()
