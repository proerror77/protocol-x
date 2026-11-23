"""Single-channel push-to-talk floor. Only a granted lease permits publishing."""
import asyncio
from dataclasses import dataclass
from time import monotonic


class FloorBusy(Exception):
    pass


class LeaseExpired(Exception):
    pass


@dataclass
class Lease:
    actor: str
    press_id: str
    expires_at: float = 0

    def matches(self, actor, press_id):
        return self.actor == actor and self.press_id == press_id


class PushToTalkFloor:
    def __init__(self, media, lease_seconds=8, clock=monotonic):
        self.media = media
        self.lease_seconds = lease_seconds
        self.clock = clock
        self.lock = asyncio.Lock()
        self.lease = None
        self.initialized = False

    async def claim(self, actor, press_id):
        async with self.lock:
            await self._initialize_and_reap()
            if self.lease:
                if self.lease.matches(actor, press_id):
                    return self._remaining_ms()
                raise FloorBusy
            # Retain an expired lease while the remote result is uncertain.
            # It must be revoked before granting the floor to anyone else.
            self.lease = Lease(actor, press_id)
            try:
                await self.media.set_publish_permission(actor, True)
            except LeaseExpired:
                self.lease = None
                raise
            self.lease.expires_at = self.clock() + self.lease_seconds
            return self._remaining_ms()

    async def renew(self, actor, press_id):
        async with self.lock:
            if not self.lease or not self.lease.matches(actor, press_id) or self.lease.expires_at <= self.clock():
                raise LeaseExpired
            self.lease.expires_at = self.clock() + self.lease_seconds
            return self._remaining_ms()

    async def release(self, actor, press_id):
        async with self.lock:
            if self.lease and self.lease.matches(actor, press_id):
                self.lease.expires_at = 0
                await self._reap()
            return 0

    async def reap(self):
        async with self.lock:
            await self._initialize_and_reap()

    async def close(self):
        async with self.lock:
            if self.lease:
                self.lease.expires_at = 0
                await self._reap()

    async def _reap(self):
        if self.lease and self.lease.expires_at <= self.clock():
            await self.media.set_publish_permission(self.lease.actor, False)
            self.lease = None

    async def _initialize_and_reap(self):
        if not self.initialized:
            await self.media.clear_publishers()
            self.initialized = True
        await self._reap()

    def _remaining_ms(self):
        return max(0, int((self.lease.expires_at - self.clock()) * 1000))
