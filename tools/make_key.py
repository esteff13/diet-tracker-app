"""
Rebuilds the app's permanent signing key from the SIGNING_SEED secret, on every build.

Android only installs an update over the old app when both are signed with the same key.
Instead of storing a key file anywhere, the key is derived from one secret phrase
(GitHub > Settings > Secrets > Actions > SIGNING_SEED). Same phrase = same key, every time.
The key only ever exists inside the build machine for a few seconds.

Usage (in the workflow): python tools/make_key.py <out.p12>  with SIGNING_SEED and SIGN_PASS in the env.
"""
import datetime
import hashlib
import os
import sys

from Crypto.PublicKey import RSA
from cryptography import x509
from cryptography.hazmat.primitives import hashes, serialization
from cryptography.hazmat.primitives.serialization import pkcs12
from cryptography.x509.oid import NameOID


class SeededRandom:
    """Deterministic byte stream (SHA-256 in counter mode) seeded by the secret phrase."""

    def __init__(self, seed: bytes):
        self.key = hashlib.sha256(b"diet-tracker-signing-v1|" + seed).digest()
        self.counter = 0
        self.buf = b""

    def __call__(self, n: int) -> bytes:
        while len(self.buf) < n:
            self.counter += 1
            self.buf += hashlib.sha256(self.key + self.counter.to_bytes(8, "big")).digest()
        out, self.buf = self.buf[:n], self.buf[n:]
        return out


def main():
    seed = os.environ.get("SIGNING_SEED", "").encode()
    password = os.environ.get("SIGN_PASS", "").encode()
    if len(seed) < 16:
        sys.exit("SIGNING_SEED is missing or too short (use at least 16 characters).")
    out = sys.argv[1]

    rsa = RSA.generate(3072, randfunc=SeededRandom(seed))
    key = serialization.load_pem_private_key(rsa.export_key("PEM"), password=None)

    name = x509.Name([x509.NameAttribute(NameOID.COMMON_NAME, "Diet Tracker"),
                      x509.NameAttribute(NameOID.COUNTRY_NAME, "PH")])
    serial = int.from_bytes(hashlib.sha256(seed + b"|serial").digest()[:16], "big") >> 1
    start = datetime.datetime(2026, 1, 1, tzinfo=datetime.timezone.utc)
    cert = (x509.CertificateBuilder()
            .subject_name(name).issuer_name(name)
            .public_key(key.public_key())
            .serial_number(serial)
            .not_valid_before(start)
            .not_valid_after(start + datetime.timedelta(days=365 * 40))
            .sign(key, hashes.SHA256()))   # RSA PKCS#1 v1.5: deterministic, so the cert is identical every build

    data = pkcs12.serialize_key_and_certificates(
        b"diettracker", key, cert, None, serialization.BestAvailableEncryption(password))
    with open(out, "wb") as f:
        f.write(data)
    print("Signing cert SHA-256:", cert.fingerprint(hashes.SHA256()).hex()[:16] + "...")


if __name__ == "__main__":
    main()
