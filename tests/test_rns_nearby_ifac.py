import base64
import hashlib
import json
import os
import tempfile
import unittest
from datetime import datetime, timezone
from pathlib import Path
from unittest.mock import patch

from cryptography.hazmat.primitives import hashes, serialization
from cryptography.hazmat.primitives.asymmetric import ec, ed25519, x25519
from fastapi.testclient import TestClient

from app.field_nearby import derive_channel_ifac
from app.main import create_app


DISPATCH = {"Authorization": "Bearer " + "D" * 40}
EVENT = "/api/field/events/golf-demo"


def _p256_b64():
    pub = ec.generate_private_key(ec.SECP256R1()).public_key()
    return base64.b64encode(
        pub.public_bytes(serialization.Encoding.DER, serialization.PublicFormat.SubjectPublicKeyInfo)
    ).decode("ascii")


def _rns_keys(dest_seed: bytes):
    ed_pub = ed25519.Ed25519PrivateKey.generate().public_key().public_bytes(
        serialization.Encoding.Raw, serialization.PublicFormat.Raw
    )
    x_pub = x25519.X25519PrivateKey.generate().public_key().public_bytes(
        serialization.Encoding.Raw, serialization.PublicFormat.Raw
    )
    identity_hash = hashlib.sha256(x_pub + ed_pub).hexdigest()[:32]
    dest_hash = hashlib.sha256(b"lxmf.delivery|" + dest_seed + bytes.fromhex(identity_hash)).hexdigest()[:32]
    return {
        "dest_hash": dest_hash,
        "identity_hash": identity_hash,
        "ed25519_pub": base64.b64encode(ed_pub).decode("ascii"),
        "x25519_pub": base64.b64encode(x_pub).decode("ascii"),
    }


class RnsNearbyIfacTests(unittest.TestCase):
    def setUp(self):
        self.tmp = tempfile.TemporaryDirectory()
        self.addCleanup(self.tmp.cleanup)
        self.db = Path(self.tmp.name) / "ifac.db"
        self.env = patch.dict(os.environ, {
            "OPS_PILOT_DISPATCHER_TOKEN": "D" * 40,
            "OPS_PILOT_STAFF_TOKEN": "S" * 40,
        })
        self.env.start()
        self.addCleanup(self.env.stop)
        self.client = TestClient(create_app(self.db))
        self.addCleanup(self.client.close)

    def _create_channel(self, name: str) -> str:
        res = self.client.post(f"{EVENT}/channels", headers=DISPATCH, json={"name": name})
        self.assertEqual(res.status_code, 201, res.text)
        return res.json()["id"]

    def _bind_person(self, name: str, channels: list[str], role: str = "staff") -> tuple[str, dict]:
        res = self.client.post(f"{EVENT}/people", headers=DISPATCH, json={
            "name": name, "callsign": name, "role": role, "channel_ids": channels,
        })
        self.assertEqual(res.status_code, 201, res.text)
        person = res.json()
        bound = self.client.post("/api/field/bind", json={"code": person["binding_code"]})
        self.assertEqual(bound.status_code, 200, bound.text)
        return person["actor"], {"Authorization": "Bearer " + bound.json()["session_token"]}

    def test_rns_identity_binding_ifac_derivation_and_peer_scoping(self):
        ch_a = self._create_channel("Alpha")
        ch_b = self._create_channel("Beta")
        a_id, a_hdr = self._bind_person("队员甲", [ch_a])
        b_id, b_hdr = self._bind_person("队员乙", [ch_a])
        c_id, c_hdr = self._bind_person("队员丙", [ch_b])

        rns_a = _rns_keys(b"a")
        rns_b = _rns_keys(b"b")
        rns_c = _rns_keys(b"c")

        # Invalid identity hash mismatch -> 422
        bad = self.client.post(f"{EVENT}/nearby", headers=a_hdr, json={
            "public_key": _p256_b64(),
            "rns_identity": {**rns_a, "identity_hash": "0" * 32},
        })
        self.assertEqual(bad.status_code, 422)

        res_a = self.client.post(f"{EVENT}/nearby", headers=a_hdr, json={
            "public_key": _p256_b64(), "agreement_key": _p256_b64(), "rns_identity": rns_a,
        })
        self.assertEqual(res_a.status_code, 200, res_a.text)
        data_a = res_a.json()
        cred_a = data_a["channels"][0]
        expected_ifac = derive_channel_ifac("golf-demo", ch_a, cred_a["secret"])
        self.assertEqual(cred_a["ifac"], expected_ifac)

        # Verify signed certificate claims include rns_dest_hash + network_id
        raw_claims, sig = map(base64.b64decode, cred_a["certificate"].split("."))
        authority_pub = serialization.load_der_public_key(base64.b64decode(data_a["authority"]))
        authority_pub.verify(sig, raw_claims, ec.ECDSA(hashes.SHA256()))
        claims = json.loads(raw_claims)
        self.assertEqual(claims["rns_dest_hash"], rns_a["dest_hash"])
        self.assertEqual(claims["network_id"], expected_ifac["network_name"])
        self.assertEqual(claims["actor"], a_id)
        self.assertGreater(claims["expires"], datetime.now(timezone.utc).timestamp())

        # Duplicate dest_hash by another active device -> 409
        dup = self.client.post(f"{EVENT}/nearby", headers=b_hdr, json={
            "public_key": _p256_b64(), "rns_identity": rns_a,
        })
        self.assertEqual(dup.status_code, 409)

        # Bind B & C properly; verify rns_peers scoping
        self.client.post(f"{EVENT}/nearby", headers=b_hdr, json={"public_key": _p256_b64(), "rns_identity": rns_b})
        self.client.post(f"{EVENT}/nearby", headers=c_hdr, json={"public_key": _p256_b64(), "rns_identity": rns_c})

        refreshed_a = self.client.post(f"{EVENT}/nearby", headers=a_hdr, json={
            "public_key": _p256_b64(), "rns_identity": rns_a,
        }).json()["channels"][0]
        self.assertEqual([p["actor"] for p in refreshed_a["rns_peers"]], [b_id])
        self.assertEqual(refreshed_a["rns_peers"][0]["dest_hash"], rns_b["dest_hash"])

    def test_revocation_suspension_and_logout_immediately_rotate_ifac_and_record_crl(self):
        ch = self._create_channel("RevokeGroup")
        a_id, a_hdr = self._bind_person("留守", [ch])
        b_id, b_hdr = self._bind_person("待撤销", [ch])
        c_id, c_hdr = self._bind_person("待停用", [ch])

        rns_a = _rns_keys(b"ra")
        rns_b = _rns_keys(b"rb")
        rns_c = _rns_keys(b"rc")

        cred1 = self.client.post(f"{EVENT}/nearby", headers=a_hdr, json={
            "public_key": _p256_b64(), "rns_identity": rns_a,
        }).json()["channels"][0]
        self.client.post(f"{EVENT}/nearby", headers=b_hdr, json={"public_key": _p256_b64(), "rns_identity": rns_b})
        self.client.post(f"{EVENT}/nearby", headers=c_hdr, json={"public_key": _p256_b64(), "rns_identity": rns_c})

        # Revoke B -> secret/IFAC rotates immediately and revocation entry is recorded
        rev_res = self.client.post(f"{EVENT}/people/{b_id}/revoke", headers=DISPATCH)
        self.assertEqual(rev_res.status_code, 200)
        revs = self.client.get(f"{EVENT}/nearby/revocations", headers=a_hdr).json()["revocations"]
        self.assertEqual(len(revs), 1)
        self.assertEqual(revs[0]["revoked_actor"], b_id)
        self.assertEqual(revs[0]["revoked_dest_hash"], rns_b["dest_hash"])
        self.assertEqual(revs[0]["reason"], "device_revoked")

        cred2 = self.client.post(f"{EVENT}/nearby", headers=a_hdr, json={
            "public_key": _p256_b64(), "rns_identity": rns_a,
        }).json()["channels"][0]
        self.assertNotEqual(cred2["secret"], cred1["secret"])
        self.assertNotEqual(cred2["ifac"]["network_name"], cred1["ifac"]["network_name"])
        self.assertEqual(cred2["ifac"]["generation"], revs[0]["min_generation"])
        self.assertEqual([p["actor"] for p in cred2["rns_peers"]], [c_id])

        # Suspend C -> secret/IFAC rotates again
        susp = self.client.post(f"{EVENT}/people/{c_id}/account-status", headers=DISPATCH, json={"suspended": True})
        self.assertEqual(susp.status_code, 200)
        revs2 = self.client.get(f"{EVENT}/nearby/revocations", headers=a_hdr).json()["revocations"]
        self.assertEqual(revs2[0]["revoked_actor"], c_id)
        self.assertEqual(revs2[0]["reason"], "account_suspended")

        cred3 = self.client.post(f"{EVENT}/nearby", headers=a_hdr, json={
            "public_key": _p256_b64(), "rns_identity": rns_a,
        }).json()["channels"][0]
        self.assertNotEqual(cred3["secret"], cred2["secret"])
        self.assertEqual(cred3["rns_peers"], [])


if __name__ == "__main__":
    unittest.main()
