#!/usr/bin/env python3
"""BlueZ GATT peripheral. Pairing is a short, locally approved session."""
import asyncio
import json
import logging
import os
from pathlib import Path
import signal
import struct
import time
import uuid

from dbus_next import BusType, Variant, DBusError, PropertyAccess, Message, MessageType
from dbus_next.aio import MessageBus
from dbus_next.service import ServiceInterface, method, dbus_property
from hotspot_control import STATE_DIR, ControlError, current_state, set_hotspot

SERVICE_UUID = 'af937001-6d7b-4b87-93ea-9a6b7dc44e10'
STATE_UUID = 'af937002-6d7b-4b87-93ea-9a6b7dc44e10'
COMMAND_UUID = 'af937003-6d7b-4b87-93ea-9a6b7dc44e10'
RESULT_UUID = 'af937004-6d7b-4b87-93ea-9a6b7dc44e10'
BASE = '/kr/pc/hotspot'
SOCKET = Path(os.environ.get('XDG_RUNTIME_DIR', f'/run/user/{os.getuid()}')) / 'pc-hotspot.sock'
ALLOWED = STATE_DIR / 'phones.json'
ENROLL_RECOVERY = STATE_DIR / 'enrollment-recovery.json'
LOG = logging.getLogger('pc-hotspot')

def denied():
    return DBusError('org.bluez.Error.NotAuthorized', 'PC 등록이 필요합니다.')

def decode_command(value):
    if len(value) != 10 or value[0] != 1 or value[1] not in (0, 1):
        raise DBusError('org.bluez.Error.InvalidValueLength', 'Expected version, enabled, 8-byte request ID')
    return bool(value[1]), bytes(value[2:])

class GattService(ServiceInterface):
    def __init__(self):
        super().__init__('org.bluez.GattService1')
    @dbus_property(access=PropertyAccess.READ)
    def UUID(self) -> 's': return SERVICE_UUID
    @dbus_property(access=PropertyAccess.READ)
    def Primary(self) -> 'b': return True
    @dbus_property(access=PropertyAccess.READ)
    def Includes(self) -> 'ao': return []

class Characteristic(ServiceInterface):
    def __init__(self, server, uuid, flags):
        super().__init__('org.bluez.GattCharacteristic1')
        self.server, self.uuid, self.flags = server, uuid, flags
        self.value = bytes([1, 2, 0]) + bytes(8)
        self.notifying = False
    @dbus_property(access=PropertyAccess.READ)
    def UUID(self) -> 's': return self.uuid
    @dbus_property(access=PropertyAccess.READ)
    def Service(self) -> 'o': return BASE + '/service0'
    @dbus_property(access=PropertyAccess.READ)
    def Flags(self) -> 'as': return self.flags
    @dbus_property(access=PropertyAccess.READ)
    def Value(self) -> 'ay': return self.value
    @dbus_property(access=PropertyAccess.READ)
    def Notifying(self) -> 'b': return self.notifying
    @method()
    async def ReadValue(self, options: 'a{sv}') -> 'ay':
        await self.server.authorize(options)
        if self.uuid == COMMAND_UUID:
            raise DBusError('org.bluez.Error.NotPermitted', 'Write only')
        if self.uuid == STATE_UUID:
            try:
                state = int(await asyncio.to_thread(current_state))
                value = bytes([1, state, 0])
            except ControlError:
                value = bytes([1, 2, 3])
        else:
            value = self.value
        offset = options.get('offset', Variant('q', 0)).value
        if offset > len(value):
            raise DBusError('org.bluez.Error.InvalidOffset', 'Offset exceeds value')
        return value[offset:]
    @method()
    async def WriteValue(self, value: 'ay', options: 'a{sv}'):
        address = await self.server.authorize(options)
        if self.uuid != COMMAND_UUID:
            raise DBusError('org.bluez.Error.NotPermitted', 'Read only')
        if options.get('offset', Variant('q', 0)).value or options.get('prepare-authorize', Variant('b', False)).value:
            raise DBusError('org.bluez.Error.NotSupported', 'No partial or queued writes')
        enabled, request = decode_command(value)
        # Only one executing request; no deferred commands or retries.
        if self.server.busy:
            self.server.result.publish(bytes([1, 2, 1]) + request)
            return
        key = (address, request)
        if key in self.server.completed:
            old_enabled, result = self.server.completed[key]
            if old_enabled != enabled:
                raise DBusError('org.bluez.Error.NotPermitted', 'Request ID reused')
            self.server.result.publish(result)
            return
        self.server.busy = True
        try:
            try:
                state = int(await asyncio.to_thread(set_hotspot, enabled))
                code = 0
            except ControlError as exc:
                code = exc.code
                try:
                    state = int(await asyncio.to_thread(current_state))
                except ControlError:
                    state = 2
            result = bytes([1, state, code]) + request
            self.server.completed[key] = (enabled, result)
            if len(self.server.completed) > 128:
                del self.server.completed[next(iter(self.server.completed))]
            self.server.result.publish(result)
            LOG.info('command completed code=%d state=%d', code, state)
        finally:
            self.server.busy = False
    @method()
    def StartNotify(self):
        if self.uuid not in (RESULT_UUID, STATE_UUID):
            raise DBusError('org.bluez.Error.NotSupported', 'No notifications')
        # BlueZ applies authenticated encryption to the CCC write. This API has
        # no device argument, so never use a notification as authorization.
        self.notifying = True
        self.emit_properties_changed({'Notifying': True})
    @method()
    def StopNotify(self):
        self.notifying = False
        self.emit_properties_changed({'Notifying': False})
    def publish(self, value):
        self.value = value
        if self.notifying:
            self.emit_properties_changed({'Value': value})

class Advertisement(ServiceInterface):
    def __init__(self): super().__init__('org.bluez.LEAdvertisement1')
    @dbus_property(access=PropertyAccess.READ)
    def Type(self) -> 's': return 'peripheral'
    @dbus_property(access=PropertyAccess.READ)
    def ServiceUUIDs(self) -> 'as': return [SERVICE_UUID]
    @dbus_property(access=PropertyAccess.READ)
    def LocalName(self) -> 's': return 'PC Hotspot'
    @method()
    def Release(self): LOG.info('Advertisement released')

class Agent(ServiceInterface):
    def __init__(self, server):
        super().__init__('org.bluez.Agent1')
        self.server = server
    @method()
    async def RequestConfirmation(self, device: 'o', passkey: 'u'):
        s = self.server
        if s.enroll_until <= time.monotonic() or s.candidate is not None:
            raise denied()
        props = await s.device_props(device)
        LOG.info('Numeric comparison requested for %s', props['Address'].value)
        candidate = {'device': device, 'address': props['Address'].value,
                     'passkey': f'{passkey:06d}', 'approved': False, 'id': uuid.uuid4().hex}
        s.candidate = candidate
        s.confirmation = asyncio.get_running_loop().create_future()
        try:
            if not await asyncio.wait_for(s.confirmation, min(60, s.enroll_until - time.monotonic())):
                raise denied()
        except asyncio.TimeoutError:
            LOG.info('Numeric comparison timed out')
            raise denied()
        finally:
            if not candidate['approved'] and s.candidate is candidate:
                s.candidate = None
    @method()
    def RequestAuthorization(self, device: 'o'):
        # Reject Just Works; require numeric comparison on both PC and phone.
        LOG.warning('Rejected pairing without numeric comparison: %s', device)
        raise denied()
    @method()
    async def AuthorizeService(self, device: 'o', uuid: 's'):
        # Do not approve unrelated services while acting as registration agent.
        if uuid.lower() != SERVICE_UUID:
            raise denied()
        await self.server.authorize({'device': Variant('o', device)})
    @method()
    def Cancel(self):
        LOG.info('Pairing request cancelled by BlueZ')
        self.server.candidate = None
        if self.server.confirmation and not self.server.confirmation.done():
            self.server.confirmation.set_result(False)
    @method()
    def Release(self): self.Cancel()

class Server:
    def __init__(self):
        self.busy = False
        self.completed = {}
        self.enroll_until = 0
        self.candidate = None
        self.confirmation = None
        self.agent_registered = False
        self.previous_pairable = None
        self.previous_pairable_timeout = None
        self.result = Characteristic(self, RESULT_UUID, ['read', 'notify', 'encrypt-authenticated-read', 'encrypt-authenticated-notify'])
        self.state = Characteristic(self, STATE_UUID, ['read', 'notify', 'encrypt-authenticated-read', 'encrypt-authenticated-notify'])
        self.command = Characteristic(self, COMMAND_UUID, ['write', 'encrypt-authenticated-write'])
        self.stop = asyncio.Event()
    def allowed(self):
        try:
            data = json.loads(ALLOWED.read_text())
            return set(data['addresses'])
        except FileNotFoundError:
            return set()
        # Corrupt state fails closed; do not silently enroll over it.
    def save_allowed(self, addresses):
        STATE_DIR.mkdir(parents=True, exist_ok=True, mode=0o700)
        temp = ALLOWED.with_suffix('.tmp')
        temp.write_text(json.dumps({'addresses': sorted(addresses)}))
        temp.chmod(0o600)
        temp.replace(ALLOWED)
    async def device_props(self, path):
        if not path.startswith(self.adapter_path + '/dev_'):
            raise denied()
        intro = await self.bus.introspect('org.bluez', path)
        obj = self.bus.get_proxy_object('org.bluez', path, intro)
        return await obj.get_interface('org.freedesktop.DBus.Properties').call_get_all('org.bluez.Device1')
    async def authorize(self, options):
        if 'device' not in options:
            raise denied()
        path = options['device'].value
        props = await self.device_props(path)
        address = props['Address'].value
        # Bonded (BlueZ) is stronger than an in-progress Paired flag.
        if not props.get('Bonded', Variant('b', False)).value or not props.get('Connected', Variant('b', False)).value:
            raise denied()
        if address in self.allowed():
            return address
        c = self.candidate
        if (self.enroll_until > time.monotonic() and c and c['approved'] and c['device'] == path):
            self.save_allowed({address})
            await self.close_enrollment()
            LOG.info('Phone enrolled; enrollment closed')
            return address
        raise denied()
    async def close_enrollment(self):
        self.enroll_until = 0
        if self.confirmation and not self.confirmation.done():
            self.confirmation.set_result(False)
        if self.agent_registered:
            await self.agent_manager.call_unregister_agent(BASE + '/agent')
            self.agent_registered = False
        if self.previous_pairable is not None:
            await self.adapter_props.call_set('org.bluez.Adapter1', 'Pairable', Variant('b', self.previous_pairable))
            self.previous_pairable = None
        if self.previous_pairable_timeout is not None:
            await self.adapter_props.call_set('org.bluez.Adapter1', 'PairableTimeout', Variant('u', self.previous_pairable_timeout))
            self.previous_pairable_timeout = None
        ENROLL_RECOVERY.unlink(missing_ok=True)
        self.candidate = None
    async def rpc(self, reader, writer):
        try:
            line = await asyncio.wait_for(reader.readline(), 5)
            req = json.loads(line)
            op = req.get('op')
            if op == 'enroll':
                if self.allowed():
                    raise ValueError('이미 등록된 폰이 있습니다. 먼저 revoke를 실행하세요.')
                await self.close_enrollment()
                self.previous_pairable = (await self.adapter_props.call_get('org.bluez.Adapter1', 'Pairable')).value
                self.previous_pairable_timeout = (await self.adapter_props.call_get('org.bluez.Adapter1', 'PairableTimeout')).value
                STATE_DIR.mkdir(parents=True, exist_ok=True, mode=0o700)
                ENROLL_RECOVERY.write_text(json.dumps({'pairable': self.previous_pairable, 'timeout': self.previous_pairable_timeout}))
                ENROLL_RECOVERY.chmod(0o600)
                await self.adapter_props.call_set('org.bluez.Adapter1', 'PairableTimeout', Variant('u', 120))
                await self.agent_manager.call_register_agent(BASE + '/agent', 'DisplayYesNo')
                self.agent_registered = True
                await self.agent_manager.call_request_default_agent(BASE + '/agent')
                await self.adapter_props.call_set('org.bluez.Adapter1', 'Pairable', Variant('b', True))
                self.enroll_until = time.monotonic() + 120
                LOG.info('Enrollment opened for 120 seconds')
                out = {'ok': True}
            elif op == 'approve':
                c = self.candidate
                if not c or not self.confirmation or self.confirmation.done() or self.enroll_until <= time.monotonic():
                    raise ValueError('유효한 페어링 요청이 없습니다.')
                if req.get('candidate_id') != c['id']:
                    raise ValueError('페어링 요청이 변경되었습니다. 현재 번호를 다시 확인하세요.')
                yes = req.get('yes') is True
                c['approved'] = yes
                self.confirmation.set_result(yes)
                out = {'ok': True}
            elif op == 'close':
                await self.close_enrollment()
                out = {'ok': True}
            elif op == 'revoke':
                await self.close_enrollment()
                self.save_allowed(set())
                self.completed.clear()
                out = {'ok': True}
            elif op == 'status':
                out = {'ok': True, 'addresses': sorted(self.allowed()), 'enrolling': self.enroll_until > time.monotonic(),
                       'candidate': self.candidate, 'busy': self.busy}
            else:
                raise ValueError('Unknown operation')
        except Exception as exc:
            LOG.warning('Local control: %s', exc)
            out = {'ok': False, 'error': str(exc)}
        writer.write((json.dumps(out) + '\n').encode())
        await writer.drain()
        writer.close()
        await writer.wait_closed()
    async def run(self):
        self.bus = await MessageBus(bus_type=BusType.SYSTEM).connect()
        dbus = self.bus.get_proxy_object('org.freedesktop.DBus', '/org/freedesktop/DBus',
                    await self.bus.introspect('org.freedesktop.DBus', '/org/freedesktop/DBus')).get_interface('org.freedesktop.DBus')
        bluez_owner = await dbus.call_get_name_owner('org.bluez')
        def guard(message):
            if (message.message_type == MessageType.METHOD_CALL and message.path and
                    message.path.startswith(BASE + '/') and
                    message.interface in ('org.bluez.GattCharacteristic1', 'org.bluez.Agent1') and
                    message.sender != bluez_owner):
                return Message.new_error(message, 'org.bluez.Error.NotAuthorized', 'BlueZ calls only')
        # BlueZ probes this optional advertisement property. Reply without
        # dbus-next treating an ordinary unsupported-property probe as a crash.
        def optional_property(message):
            if (message.message_type == MessageType.METHOD_CALL and message.path == BASE + '/advertisement' and
                    message.interface in (None, 'org.freedesktop.DBus.Properties') and message.member in ('Get', 'Set') and
                    message.body[:2] == ['org.bluez.LEAdvertisement1', 'TxPower']):
                return Message.new_error(message, 'org.freedesktop.DBus.Error.UnknownProperty', 'Optional TxPower omitted')
        self.bus.add_message_handler(guard)
        self.bus.add_message_handler(optional_property)
        intro = await self.bus.introspect('org.bluez', '/')
        manager = self.bus.get_proxy_object('org.bluez', '/', intro).get_interface('org.freedesktop.DBus.ObjectManager')
        objects = await manager.call_get_managed_objects()
        adapters = [p for p, i in objects.items() if 'org.bluez.GattManager1' in i and 'org.bluez.LEAdvertisingManager1' in i]
        if not adapters: raise RuntimeError('BLE peripheral adapter unavailable')
        self.adapter_path = adapters[0]
        obj = self.bus.get_proxy_object('org.bluez', self.adapter_path, await self.bus.introspect('org.bluez', self.adapter_path))
        self.adapter_props = obj.get_interface('org.freedesktop.DBus.Properties')
        if ENROLL_RECOVERY.exists():
            recovery = json.loads(ENROLL_RECOVERY.read_text())
            await self.adapter_props.call_set('org.bluez.Adapter1', 'Pairable', Variant('b', recovery['pairable']))
            await self.adapter_props.call_set('org.bluez.Adapter1', 'PairableTimeout', Variant('u', recovery['timeout']))
            ENROLL_RECOVERY.unlink()
        if not (await self.adapter_props.call_get('org.bluez.Adapter1', 'Powered')).value:
            raise RuntimeError('Bluetooth is off; turn it on locally')
        agent_obj = self.bus.get_proxy_object('org.bluez', '/org/bluez', await self.bus.introspect('org.bluez', '/org/bluez'))
        self.agent_manager = agent_obj.get_interface('org.bluez.AgentManager1')
        self.bus.export(BASE, ServiceInterface('kr.pc.Hotspot.Application'))
        self.bus.export(BASE + '/service0', GattService())
        for name, c in [('state', self.state), ('command', self.command), ('result', self.result)]:
            self.bus.export(BASE + '/service0/' + name, c)
        self.bus.export(BASE + '/advertisement', Advertisement())
        self.bus.export(BASE + '/agent', Agent(self))
        self.gatt = obj.get_interface('org.bluez.GattManager1')
        self.advertising = obj.get_interface('org.bluez.LEAdvertisingManager1')
        await self.gatt.call_register_application(BASE, {})
        await self.advertising.call_register_advertisement(BASE + '/advertisement', {})
        SOCKET.unlink(missing_ok=True)
        local = await asyncio.start_unix_server(self.rpc, str(SOCKET), limit=4096)
        SOCKET.chmod(0o600)
        LOG.info('BLE registered on %s; phones=%d', self.adapter_path, len(self.allowed()))
        # Restart through systemd when BlueZ disappears or adapter is powered off.
        def changed(interface, changed, invalidated):
            if interface == 'org.bluez.Adapter1' and 'Powered' in changed and not changed['Powered'].value:
                self.stop.set()
        self.adapter_props.on_properties_changed(changed)
        async def disconnected():
            await self.bus.wait_for_disconnect()
            self.stop.set()
        watcher = asyncio.create_task(disconnected())
        async def check_bluez():
            while not self.stop.is_set():
                try:
                    await self.adapter_props.call_get('org.bluez.Adapter1', 'Powered')
                except Exception:
                    self.stop.set()
                    return
                if self.enroll_until and time.monotonic() >= self.enroll_until:
                    try:
                        await self.close_enrollment()
                    except Exception:
                        self.stop.set()
                        return
                # Observe actual NM state, including changes made by F9 or the UI.
                # No control commands are issued by this observer.
                if self.state.notifying:
                    try:
                        value = bytes([1, int(await asyncio.to_thread(current_state)), 0])
                    except ControlError:
                        value = bytes([1, 2, 3])
                    if value != self.state.value:
                        self.state.publish(value)
                await asyncio.sleep(2)
        health = asyncio.create_task(check_bluez())
        for sig in (signal.SIGINT, signal.SIGTERM):
            asyncio.get_running_loop().add_signal_handler(sig, self.stop.set)
        try:
            async with local:
                await self.stop.wait()
        finally:
            watcher.cancel()
            health.cancel()
            try:
                await self.close_enrollment()
                await self.advertising.call_unregister_advertisement(BASE + '/advertisement')
                await self.gatt.call_unregister_application(BASE)
            except Exception:
                pass
            SOCKET.unlink(missing_ok=True)
            self.bus.disconnect()

if __name__ == '__main__':
    logging.basicConfig(level=logging.INFO, format='%(asctime)s %(levelname)s %(message)s')
    asyncio.run(Server().run())
